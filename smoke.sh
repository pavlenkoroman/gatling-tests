#!/bin/bash
set -euo pipefail

IP=${1:?"Usage: ./smoke.sh <server-ip>"}

USER_URL="http://$IP:8080"
POST_URL="http://$IP:8081"
FEED_URL="http://$IP:8083"

echo "=== Smoke test ==="
mvn io.gatling:gatling-maven-plugin:4.21.7:test \
    -Dgatling.simulationClass=NewsFeedSmokeSimulation \
    -DbaseUrlUser="$USER_URL" \
    -DbaseUrlPost="$POST_URL" \
    -DbaseUrlFeed="$FEED_URL"

echo ""
echo "=== Готово. Отчёт: target/gatling/ ==="
