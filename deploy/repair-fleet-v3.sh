#!/usr/bin/env bash
set -Eeuo pipefail

FQDN="${1:?fqdn required}"
RELAY_TOKEN="${2:?relay token required}"
BASE=/opt/teslalyrics
CERT_LIVE="/etc/letsencrypt/live/$FQDN"
TLS_DIR="$BASE/tls"

if [[ ! -s "$CERT_LIVE/fullchain.pem" || ! -s "$CERT_LIVE/privkey.pem" ]]; then
  echo "Certificate not found under $CERT_LIVE" >&2
  exit 1
fi

mkdir -p "$TLS_DIR"
cp -L "$CERT_LIVE/fullchain.pem" "$TLS_DIR/fullchain.pem"
cp -L "$CERT_LIVE/privkey.pem" "$TLS_DIR/privkey.pem"
cp -L "$CERT_LIVE/chain.pem" "$TLS_DIR/chain.pem"
chmod 644 "$TLS_DIR/fullchain.pem" "$TLS_DIR/chain.pem"
chmod 600 "$TLS_DIR/privkey.pem"

cat > "$BASE/renew-hook.sh" <<EOF
#!/usr/bin/env bash
set -e
cp -L /etc/letsencrypt/live/$FQDN/fullchain.pem $TLS_DIR/fullchain.pem
cp -L /etc/letsencrypt/live/$FQDN/privkey.pem $TLS_DIR/privkey.pem
cp -L /etc/letsencrypt/live/$FQDN/chain.pem $TLS_DIR/chain.pem
chmod 644 $TLS_DIR/fullchain.pem $TLS_DIR/chain.pem
chmod 600 $TLS_DIR/privkey.pem
docker restart fleet-telemetry relay >/dev/null 2>&1 || true
EOF
chmod +x "$BASE/renew-hook.sh"
mkdir -p /etc/letsencrypt/renewal-hooks/deploy
ln -sf "$BASE/renew-hook.sh" /etc/letsencrypt/renewal-hooks/deploy/teslalyrics.sh

echo '[repair-v3] Recreating Fleet Telemetry + Relay with real certificate files...'
docker network inspect teslalyrics >/dev/null 2>&1 || docker network create teslalyrics >/dev/null
docker rm -f fleet-telemetry relay 2>/dev/null || true

docker run -d --name fleet-telemetry --restart unless-stopped --network teslalyrics -p 443:443 \
  -v "$BASE/fleet/config.json:/etc/fleet-telemetry/config.json:ro" \
  -v "$TLS_DIR:/certs:ro" \
  tesla/fleet-telemetry:v0.9.4 \
  /fleet-telemetry -config=/etc/fleet-telemetry/config.json >/dev/null

docker run -d --name relay --restart unless-stopped --network teslalyrics -p 8443:8443 \
  -e "RELAY_TOKEN=$RELAY_TOKEN" \
  -e "TELEMETRY_HOST=$FQDN" \
  -e "COMMAND_PROXY_URL=https://vehicle-command:4443" \
  -e "NODE_EXTRA_CA_CERTS=/command/proxy-tls.crt" \
  -v "$TLS_DIR:/certs:ro" \
  -v "$BASE/command:/command:ro" \
  teslalyrics-relay:local >/dev/null

for i in $(seq 1 20); do
  if curl -sk --max-time 2 https://127.0.0.1:8443/health 2>/dev/null | grep -q '"ok":true'; then
    break
  fi
  sleep 1
done

echo '[repair-v3] Container status:'
docker ps -a --format '{{.Names}} {{.Status}}' | grep -E '^(mosquitto|fleet-telemetry|vehicle-command|relay) ' || true

echo '[repair-v3] Fleet log tail:'
docker logs --tail 40 fleet-telemetry 2>&1 || true

echo '[repair-v3] Relay log tail:'
docker logs --tail 30 relay 2>&1 || true

echo '[repair-v3] Relay health:'
HEALTH="$(curl -sk --max-time 5 https://127.0.0.1:8443/health || true)"
echo "$HEALTH"

if [[ "$HEALTH" != *'"ok":true'* ]]; then
  echo 'REPAIR_V3_FAILED' >&2
  exit 1
fi

echo 'REPAIR_V3_OK'
echo "FQDN=$FQDN"
echo "WSS_BASE=wss://$FQDN:8443/device/"
echo "PUBLIC_KEY_URL=https://$FQDN:8443/setup/public-key.pem"
echo "HEALTH_URL=https://$FQDN:8443/health"
echo "RELAY_TOKEN=$RELAY_TOKEN"
