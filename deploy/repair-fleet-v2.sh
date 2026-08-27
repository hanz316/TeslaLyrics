#!/usr/bin/env bash
set -Eeuo pipefail
FQDN="${1:?fqdn required}"
RELAY_TOKEN="${2:?relay token required}"
BASE=/opt/teslalyrics
CERT_LIVE="/etc/letsencrypt/live/$FQDN"

echo '[repair] Recreating Fleet Telemetry + Relay only...'
docker network inspect teslalyrics >/dev/null 2>&1 || docker network create teslalyrics >/dev/null
docker rm -f fleet-telemetry relay 2>/dev/null || true

docker run -d --name fleet-telemetry --restart unless-stopped --network teslalyrics -p 443:443 \
  -v "$BASE/fleet/config.json:/etc/fleet-telemetry/config.json:ro" \
  -v "$CERT_LIVE:/certs:ro" \
  tesla/fleet-telemetry:v0.9.4 \
  /fleet-telemetry -config=/etc/fleet-telemetry/config.json >/dev/null

docker run -d --name relay --restart unless-stopped --network teslalyrics -p 8443:8443 \
  -e "RELAY_TOKEN=$RELAY_TOKEN" \
  -e "TELEMETRY_HOST=$FQDN" \
  -e "COMMAND_PROXY_URL=https://vehicle-command:4443" \
  -e "NODE_EXTRA_CA_CERTS=/command/proxy-tls.crt" \
  -v "$CERT_LIVE:/certs:ro" \
  -v "$BASE/command:/command:ro" \
  teslalyrics-relay:local >/dev/null

sleep 5

echo '[repair] Container status:'
docker ps -a --format '{{.Names}} {{.Status}}' | grep -E '^(mosquitto|fleet-telemetry|vehicle-command|relay) ' || true

echo '[repair] Fleet log tail:'
docker logs --tail 30 fleet-telemetry 2>&1 || true

echo '[repair] Relay health:'
curl -sk --max-time 5 "https://127.0.0.1:8443/health" || true
echo

echo "FQDN=$FQDN"
echo "WSS_BASE=wss://$FQDN:8443/device/"
echo "PUBLIC_KEY_URL=https://$FQDN:8443/setup/public-key.pem"
echo "HEALTH_URL=https://$FQDN:8443/health"
echo "RELAY_TOKEN=$RELAY_TOKEN"
