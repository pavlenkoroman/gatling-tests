#!/bin/bash
# Эксперимент 1 — пиковая нагрузка.
# Использование: ./exp1.sh <server-ip> [ramp-min] [peak-min]
#
# По умолчанию: ramp=5 мин, peak=20 мин (~9 800 RPS суммарно).
# Для baseline: ./exp1.sh <ip> 1 5

set -euo pipefail

IP=${1:?"Usage: ./exp1.sh <server-ip> [ramp-min] [peak-min]"}
RAMP=${2:-5}
PEAK=${3:-20}

USER_URL="http://$IP:8080"
POST_URL="http://$IP:8081"
FEED_URL="http://$IP:8083"

echo "=== Эксперимент 1: пиковая нагрузка (ramp=${RAMP}m, peak=${PEAK}m) ==="
mvn -q gatling:test \
    -Dgatling.simulationClass=Experiment1PeakLoadSimulation \
    -DbaseUrlFeed="$FEED_URL" \
    -DbaseUrlUser="$USER_URL" \
    -DbaseUrlPost="$POST_URL" \
    -DrampMin="$RAMP" \
    -DpeakMin="$PEAK"

echo ""
echo "=== Готово. Отчёт: target/gatling/ ==="
