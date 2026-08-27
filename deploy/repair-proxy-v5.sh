#!/usr/bin/env bash
set -Eeuo pipefail
FQDN="${1:?fqdn required}"
RELAY_TOKEN="${2:?relay token required}"
BASE=/opt/teslalyrics
CMD="$BASE/command"
CERT_DIR="$BASE/certs"

echo '[repair-v5] Fixing vehicle-command nonroot permissions...'
chown -R 65532:65532 "$CMD"
chmod 750 "$CMD"
chmod 640 "$CMD/fleet-key.pem" "$CMD/proxy-tls.key"
chmod 644 "$CMD/proxy-tls.crt" "$CMD/public-key.pem"

echo '[repair-v5] Restarting vehicle-command + relay...'
docker rm -f vehicle-command relay 2>/dev/null || true

docker run -d --name vehicle-command --restart unless-stopped --network teslalyrics \
  --security-opt no-new-privileges:true \
  -v "$CMD:/config:ro" \
  tesla/vehicle-command:latest \
  -tls-key /config/proxy-tls.key \
  -cert /config/proxy-tls.crt \
  -key-file /config/fleet-key.pem \
  -host 0.0.0.0 \
  -port 4443 \
  -verbose >/dev/null

docker run -d --name relay --restart unless-stopped --network teslalyrics -p 8443:8443 \
  -e "RELAY_TOKEN=$RELAY_TOKEN" \
  -e "TELEMETRY_HOST=$FQDN" \
  -e "COMMAND_PROXY_URL=https://vehicle-command:4443" \
  -e "NODE_EXTRA_CA_CERTS=/command/proxy-tls.crt" \
  -v "$CERT_DIR:/certs:ro" \
  -v "$CMD:/command:ro" \
  teslalyrics-relay:local >/dev/null

sleep 5

echo '[repair-v5] Container status:'
docker ps -a --format '{{.Names}} {{.Status}}' | grep -E '^(mosquitto|fleet-telemetry|vehicle-command|relay) ' || true

echo '[repair-v5] vehicle-command log tail:'
docker logs --tail 40 vehicle-command 2>&1 || true

VC_RUNNING=$(docker inspect -f '{{.State.Running}}' vehicle-command 2>/dev/null || echo false)
RELAY_RUNNING=$(docker inspect -f '{{.State.Running}}' relay 2>/dev/null || echo false)
FLEET_RUNNING=$(docker inspect -f '{{.State.Running}}' fleet-telemetry 2>/dev/null || echo false)
if [[ "$VC_RUNNING" != "true" || "$RELAY_RUNNING" != "true" || "$FLEET_RUNNING" != "true" ]]; then
  echo "REPAIR_V5_FAILED fleet=$FLEET_RUNNING vehicle_command=$VC_RUNNING relay=$RELAY_RUNNING" >&2
  exit 1
fi

echo '[repair-v5] Testing Relay -> vehicle-command TLS/DNS...'
set +e
PROXY_TEST=$(docker exec relay node -e "fetch('https://vehicle-command:4443/api/1/vehicles').then(async r=>{console.log('PROXY_HTTP='+r.status);console.log((await r.text()).slice(0,300))}).catch(e=>{console.error('PROXY_FETCH_FAILED '+(e&&e.cause?e.cause:e));process.exit(2)})" 2>&1)
RC=$?
set -e
echo "$PROXY_TEST"
if [[ $RC -ne 0 || "$PROXY_TEST" == *'PROXY_FETCH_FAILED'* ]]; then
  echo 'REPAIR_V5_FAILED relay_to_proxy' >&2
  exit 1
fi

HEALTH=$(curl -sk --max-time 5 https://127.0.0.1:8443/health || true)
echo "[repair-v5] Relay health: $HEALTH"
if [[ "$HEALTH" != *'"ok":true'* ]]; then
  echo 'REPAIR_V5_FAILED relay_health' >&2
  exit 1
fi

echo 'REPAIR_V5_OK'
echo 'NEXT=Return to Tesla Lyrics app and press configure Fleet Telemetry again.'
