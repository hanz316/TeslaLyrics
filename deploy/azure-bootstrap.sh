#!/usr/bin/env bash
set -Eeuo pipefail

FQDN="${1:-}"
RELAY_TOKEN="${2:-}"
if [[ -z "$FQDN" || -z "$RELAY_TOKEN" ]]; then
  echo "Usage: $0 <fqdn> <relay-token>" >&2
  exit 2
fi

BASE=/opt/teslalyrics
CERT_LIVE="/etc/letsencrypt/live/$FQDN"
export DEBIAN_FRONTEND=noninteractive

echo '[1/8] Installing Docker + Certbot...'
apt-get update -y >/dev/null
apt-get install -y docker.io certbot openssl curl ca-certificates >/dev/null
systemctl enable --now docker >/dev/null

mkdir -p "$BASE"/{fleet,command,relay,mosquitto}
chmod 700 "$BASE/command"

echo '[2/8] Getting public TLS certificate...'
docker rm -f relay fleet-telemetry mosquitto vehicle-command 2>/dev/null || true
if [[ ! -s "$CERT_LIVE/fullchain.pem" ]]; then
  certbot certonly --standalone --non-interactive --agree-tos --register-unsafely-without-email -d "$FQDN"
else
  certbot renew --dry-run >/dev/null 2>&1 || true
fi

echo '[3/8] Generating Tesla command key + internal proxy certificate...'
if [[ ! -s "$BASE/command/fleet-key.pem" ]]; then
  openssl ecparam -genkey -name prime256v1 -noout -out "$BASE/command/fleet-key.pem"
  openssl ec -in "$BASE/command/fleet-key.pem" -pubout -out "$BASE/command/public-key.pem" >/dev/null 2>&1
fi
cat > "$BASE/command/proxy-openssl.cnf" <<'EOF'
[req]
distinguished_name=req_dn
x509_extensions=v3_req
prompt=no
[req_dn]
CN=vehicle-command
[v3_req]
subjectAltName=DNS:vehicle-command
extendedKeyUsage=serverAuth
keyUsage=digitalSignature,keyEncipherment
EOF
openssl req -x509 -nodes -newkey ec \
  -pkeyopt ec_paramgen_curve:secp384r1 \
  -pkeyopt ec_param_enc:named_curve \
  -keyout "$BASE/command/proxy-tls.key" \
  -out "$BASE/command/proxy-tls.crt" \
  -sha256 -days 3650 -config "$BASE/command/proxy-openssl.cnf" >/dev/null 2>&1
chmod 600 "$BASE/command"/*.key "$BASE/command/fleet-key.pem"

cat > "$BASE/mosquitto/mosquitto.conf" <<'EOF'
listener 1883 0.0.0.0
allow_anonymous true
persistence false
log_dest stdout
EOF

cat > "$BASE/fleet/config.json" <<'EOF'
{
  "host": "0.0.0.0",
  "port": 443,
  "status_port": 8080,
  "log_level": "info",
  "json_log_enable": true,
  "namespace": "teslalyrics",
  "rate_limit": {
    "enabled": true,
    "message_interval_time": 30,
    "message_limit": 1000
  },
  "mqtt": {
    "broker": "mosquitto:1883",
    "client_id": "teslalyrics-fleet-telemetry",
    "topic_base": "teslalyrics",
    "qos": 0,
    "retained": false,
    "connect_timeout_ms": 10000,
    "publish_timeout_ms": 2500,
    "disconnect_timeout_ms": 250,
    "connect_retry_interval_ms": 5000,
    "keep_alive_seconds": 30
  },
  "records": {
    "V": ["mqtt"],
    "connectivity": ["logger"],
    "errors": ["logger"]
  },
  "tls": {
    "server_cert": "/certs/fullchain.pem",
    "server_key": "/certs/privkey.pem"
  }
}
EOF

cat > "$BASE/relay/package.json" <<'EOF'
{
  "name":"teslalyrics-relay",
  "private":true,
  "type":"module",
  "dependencies":{"mqtt":"^5.10.4","ws":"^8.18.3"}
}
EOF

cat > "$BASE/relay/Dockerfile" <<'EOF'
FROM node:20-alpine
WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev
COPY server.js ./
CMD ["node","server.js"]
EOF

cat > "$BASE/relay/server.js" <<'EOF'
import fs from 'node:fs';
import https from 'node:https';
import { WebSocketServer } from 'ws';
import mqtt from 'mqtt';

const PORT=8443;
const TOKEN=process.env.RELAY_TOKEN||'';
const FQDN=process.env.TELEMETRY_HOST||'';
const COMMAND_PROXY=(process.env.COMMAND_PROXY_URL||'https://vehicle-command:4443').replace(/\/$/,'');
const cert=fs.readFileSync('/certs/fullchain.pem');
const key=fs.readFileSync('/certs/privkey.pem');
const ca=fs.readFileSync('/certs/chain.pem','utf8');
const pubkey=fs.readFileSync('/command/public-key.pem');
const MEDIA=new Set(['MediaNowPlayingTitle','MediaNowPlayingArtist','MediaNowPlayingAlbum','MediaNowPlayingDuration','MediaNowPlayingElapsed','MediaPlaybackStatus','MediaPlaybackSource']);
const clients=new Map();
const latest=new Map();
function auth(req){const b=req.headers.authorization||'';return TOKEN&&((req.headers['x-relay-token']===TOKEN)||(b===`Bearer ${TOKEN}`));}
function sendJson(res,code,obj){const body=JSON.stringify(obj);res.writeHead(code,{'content-type':'application/json','cache-control':'no-store'});res.end(body);}
function readBody(req){return new Promise((resolve,reject)=>{let b='';req.on('data',d=>{b+=d;if(b.length>65536){reject(new Error('body too large'));req.destroy();}});req.on('end',()=>resolve(b));req.on('error',reject);});}
function tokenFrom(req){const h=req.headers.authorization||'';return h.startsWith('Bearer ')?h.slice(7):'';}
function broadcast(vin,fields){const merged={...(latest.get(vin)||{}),...fields};latest.set(vin,merged);const text=JSON.stringify({type:'media',vin,ts:Date.now(),fields});for(const [ws,wvin] of clients){if(wvin===vin&&ws.readyState===1)ws.send(text);}}
const server=https.createServer({cert,key},async(req,res)=>{
  const u=new URL(req.url,'https://local');
  if(u.pathname==='/health')return sendJson(res,200,{ok:true,clients:clients.size,telemetry_host:FQDN});
  if(u.pathname==='/setup/public-key.pem'){res.writeHead(200,{'content-type':'application/x-pem-file','cache-control':'no-store'});return res.end(pubkey);}
  if(u.pathname==='/api/tesla/configure-telemetry'&&req.method==='POST'){
    if(!auth(req))return sendJson(res,401,{error:'relay_auth'});
    const teslaToken=tokenFrom(req);if(!teslaToken)return sendJson(res,401,{error:'tesla_auth'});
    try{
      const data=JSON.parse(await readBody(req)||'{}');const vin=String(data.vin||'');if(!vin)return sendJson(res,400,{error:'missing_vin'});
      const config={vins:[vin],config:{hostname:FQDN,port:443,ca,delivery_policy:'latest',fields:{MediaNowPlayingTitle:{interval_seconds:1},MediaNowPlayingArtist:{interval_seconds:1},MediaNowPlayingAlbum:{interval_seconds:1},MediaNowPlayingDuration:{interval_seconds:1},MediaNowPlayingElapsed:{interval_seconds:1},MediaPlaybackStatus:{interval_seconds:1},MediaPlaybackSource:{interval_seconds:1}}}};
      const r=await fetch(`${COMMAND_PROXY}/api/1/vehicles/fleet_telemetry_config`,{method:'POST',headers:{authorization:`Bearer ${teslaToken}`,'content-type':'application/json'},body:JSON.stringify(config)});
      const text=await r.text();res.writeHead(r.status,{'content-type':r.headers.get('content-type')||'application/json','cache-control':'no-store'});return res.end(text);
    }catch(e){return sendJson(res,500,{error:'configure_failed',detail:String(e?.message||e)});}
  }
  if(u.pathname==='/api/tesla/telemetry-status'&&req.method==='GET'){
    if(!auth(req))return sendJson(res,401,{error:'relay_auth'});
    const teslaToken=tokenFrom(req),vin=u.searchParams.get('vin')||'';if(!teslaToken||!vin)return sendJson(res,400,{error:'missing_auth_or_vin'});
    try{const r=await fetch(`${COMMAND_PROXY}/api/1/vehicles/${encodeURIComponent(vin)}/fleet_telemetry_config`,{headers:{authorization:`Bearer ${teslaToken}`}});const text=await r.text();res.writeHead(r.status,{'content-type':r.headers.get('content-type')||'application/json','cache-control':'no-store'});return res.end(text);}catch(e){return sendJson(res,502,{error:'proxy_unreachable'});}
  }
  sendJson(res,404,{error:'not_found'});
});
const wss=new WebSocketServer({noServer:true,perMessageDeflate:false,maxPayload:16384});
server.on('upgrade',(req,socket,head)=>{const u=new URL(req.url,'https://local');const m=u.pathname.match(/^\/device\/([^/]+)$/);const vin=m?decodeURIComponent(m[1]):'';if(!m||!auth(req)){socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');return socket.destroy();}wss.handleUpgrade(req,socket,head,ws=>wss.emit('connection',ws,req,vin));});
wss.on('connection',(ws,req,vin)=>{clients.set(ws,vin);const state=latest.get(vin);if(state)ws.send(JSON.stringify({type:'media',vin,fields:state}));ws.on('close',()=>clients.delete(ws));ws.on('error',()=>clients.delete(ws));});
const mq=mqtt.connect('mqtt://mosquitto:1883',{clientId:'teslalyrics-relay',reconnectPeriod:1000});
mq.on('connect',()=>mq.subscribe('teslalyrics/+/v/+'));
mq.on('message',(topic,payload)=>{const p=topic.split('/');if(p.length<4)return;const vin=p[1],field=p.slice(3).join('/');if(!MEDIA.has(field))return;let value=payload.toString();try{value=JSON.parse(value);}catch{};if(value&&typeof value==='object'&&'value' in value)value=value.value;if(field==='MediaPlaybackStatus'&&typeof value==='string')value=value.replace(/^MediaStatus/,'');broadcast(vin,{[field]:value});});
mq.on('error',e=>console.error('mqtt',e.message));
server.listen(PORT,'0.0.0.0',()=>console.log(`TeslaLyrics relay https :${PORT}`));
EOF

cat > "$BASE/renew-hook.sh" <<'EOF'
#!/usr/bin/env bash
docker restart fleet-telemetry relay >/dev/null 2>&1 || true
EOF
chmod +x "$BASE/renew-hook.sh"
mkdir -p /etc/letsencrypt/renewal-hooks/deploy
ln -sf "$BASE/renew-hook.sh" /etc/letsencrypt/renewal-hooks/deploy/teslalyrics.sh

echo '[4/8] Building relay image...'
docker build -t teslalyrics-relay:local "$BASE/relay" >/dev/null

echo '[5/8] Starting services...'
docker network inspect teslalyrics >/dev/null 2>&1 || docker network create teslalyrics >/dev/null
docker rm -f mosquitto fleet-telemetry vehicle-command relay 2>/dev/null || true

docker run -d --name mosquitto --restart unless-stopped --network teslalyrics -v "$BASE/mosquitto/mosquitto.conf:/mosquitto/config/mosquitto.conf:ro" eclipse-mosquitto:2 >/dev/null

docker run -d --name vehicle-command --restart unless-stopped --network teslalyrics --security-opt no-new-privileges:true -v "$BASE/command:/config:ro" tesla/vehicle-command:latest -tls-key /config/proxy-tls.key -cert /config/proxy-tls.crt -key-file /config/fleet-key.pem -host 0.0.0.0 -port 4443 >/dev/null

docker run -d --name fleet-telemetry --restart unless-stopped --network teslalyrics -p 443:443 -v "$BASE/fleet/config.json:/etc/fleet-telemetry/config.json:ro" -v "$CERT_LIVE:/certs:ro" tesla/fleet-telemetry:v0.9.4 /fleet-telemetry -config=/etc/fleet-telemetry/config.json >/dev/null

docker run -d --name relay --restart unless-stopped --network teslalyrics -p 8443:8443 -e "RELAY_TOKEN=$RELAY_TOKEN" -e "TELEMETRY_HOST=$FQDN" -e "COMMAND_PROXY_URL=https://vehicle-command:4443" -e "NODE_EXTRA_CA_CERTS=/command/proxy-tls.crt" -v "$CERT_LIVE:/certs:ro" -v "$BASE/command:/command:ro" teslalyrics-relay:local >/dev/null

echo '[6/8] Waiting for services...'
for i in {1..30}; do
  if curl -sk --max-time 2 "https://127.0.0.1:8443/health" | grep -q '"ok":true'; then break; fi
  sleep 1
done

echo '[7/8] Basic checks...'
docker ps --format '{{.Names}} {{.Status}}' | grep -E '^(mosquitto|fleet-telemetry|vehicle-command|relay) '
HEALTH=$(curl -sk --max-time 5 "https://127.0.0.1:8443/health" || true)
if [[ "$HEALTH" != *'"ok":true'* ]]; then
  echo 'Relay health check failed.' >&2
  docker logs --tail 80 relay >&2 || true
  exit 1
fi

echo '[8/8] Ready.'
echo "FQDN=$FQDN"
echo "WSS_URL=wss://$FQDN:8443/device/YOUR_VIN"
echo "PUBLIC_KEY_URL=https://$FQDN:8443/setup/public-key.pem"
echo "HEALTH_URL=https://$FQDN:8443/health"
echo "RELAY_TOKEN=$RELAY_TOKEN"
echo 'IMPORTANT=Update the developer-domain public key to the new PUBLIC_KEY_URL contents, then pair Virtual Key once more.'