#!/usr/bin/env bash
set -Eeuo pipefail
FQDN="${1:?fqdn required}"
RELAY_TOKEN="${2:?relay token required}"
BASE=/opt/teslalyrics
CERT_SRC="/etc/letsencrypt/live/$FQDN"
CERT_DIR="$BASE/certs"

mkdir -p "$CERT_DIR"
cp -L "$CERT_SRC/fullchain.pem" "$CERT_DIR/fullchain.pem"
cp -L "$CERT_SRC/privkey.pem" "$CERT_DIR/privkey.pem"
cp -L "$CERT_SRC/chain.pem" "$CERT_DIR/chain.pem"
chown 65532:65532 "$CERT_DIR/fullchain.pem" "$CERT_DIR/privkey.pem" "$CERT_DIR/chain.pem"
chmod 644 "$CERT_DIR/fullchain.pem" "$CERT_DIR/chain.pem"
chmod 640 "$CERT_DIR/privkey.pem"

cat > "$BASE/renew-hook.sh" <<EOF
#!/usr/bin/env bash
set -e
cp -L "/etc/letsencrypt/live/$FQDN/fullchain.pem" "$CERT_DIR/fullchain.pem"
cp -L "/etc/letsencrypt/live/$FQDN/privkey.pem" "$CERT_DIR/privkey.pem"
cp -L "/etc/letsencrypt/live/$FQDN/chain.pem" "$CERT_DIR/chain.pem"
chown 65532:65532 "$CERT_DIR/fullchain.pem" "$CERT_DIR/privkey.pem" "$CERT_DIR/chain.pem"
chmod 644 "$CERT_DIR/fullchain.pem" "$CERT_DIR/chain.pem"
chmod 640 "$CERT_DIR/privkey.pem"
docker restart fleet-telemetry relay >/dev/null 2>&1 || true
EOF
chmod +x "$BASE/renew-hook.sh"
mkdir -p /etc/letsencrypt/renewal-hooks/deploy
ln -sf "$BASE/renew-hook.sh" /etc/letsencrypt/renewal-hooks/deploy/teslalyrics.sh

echo '[repair-v4] Restarting Fleet Telemetry + Relay with readable certs...'
docker rm -f fleet-telemetry relay 2>/dev/null || true

docker run -d --name fleet-telemetry --restart unless-stopped --network teslalyrics -p 443:443 \
  -v "$BASE/fleet/config.json:/etc/fleet-telemetry/config.json:ro" \
  -v "$CERT_DIR:/certs:ro" \
  tesla/fleet-telemetry:v0.9.4 \
  /fleet-telemetry -config=/etc/fleet-telemetry/config.json >/dev/null

docker run -d --name relay --restart unless-stopped --network teslalyrics -p 8443:8443 \
  -e "RELAY_TOKEN=$RELAY_TOKEN" \
  -e "TELEMETRY_HOST=$FQDN" \
  -e "COMMAND_PROXY_URL=https://vehicle-command:4443" \
  -e "NODE_EXTRA_CA_CERTS=/command/proxy-tls.crt" \
  -v "$CERT_DIR:/certs:ro" \
  -v "$BASE/command:/command:ro" \
  teslalyrics-relay:local >/dev/null

sleep 6

echo '[repair-v4] Container status:'
docker ps -a --format '{{.Names}} {{.Status}}' | grep -E '^(mosquitto|fleet-telemetry|vehicle-command|relay) ' || true

echo '[repair-v4] Fleet log tail:'
docker logs --tail 40 fleet-telemetry 2>&1 || true

echo '[repair-v4] Relay health:'
curl -sk --max-time 5 "https://127.0.0.1:8443/health" || true
echo

FLEET_RUNNING=$(docker inspect -f '{{.State.Running}}' fleet-telemetry 2>/dev/null || echo false)
RELAY_RUNNING=$(docker inspect -f '{{.State.Running}}' relay 2>/dev/null || echo false)
if [[ "$FLEET_RUNNING" != "true" || "$RELAY_RUNNING" != "true" ]]; then
  echo "REPAIR_V4_FAILED fleet=$FLEET_RUNNING relay=$RELAY_RUNNING" >&2
  exit 1
fi

HEALTH=$(curl -sk --max-time 5 "https://127.0.0.1:8443/health" || true)
if [[ "$HEALTH" != *'"ok":true'* ]]; then
  echo 'REPAIR_V4_FAILED relay_health' >&2
  exit 1
fi

echo 'REPAIR_V4_OK'
echo "FQDN=$FQDN"
echo "WSS_BASE=wss://$FQDN:8443/device/"
echo "PUBLIC_KEY_URL=https://$FQDN:8443/setup/public-key.pem"
echo "HEALTH_URL=https://$FQDN:8443/health"
echo "RELAY_TOKEN=$RELAY_TOKEN"
