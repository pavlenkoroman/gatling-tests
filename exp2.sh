#!/bin/bash
# Эксперимент 2 — Redis failover.
# Использование: ./exp2.sh <server-ip> [duration-min]
#
# По умолчанию: 15 минут при 500 RPS.
# Redis останавливается и поднимается ВРУЧНУЮ во время прогона:
#   t=3min:  docker compose stop redis
#   t=8min:  docker compose start redis

set -euo pipefail

IP=${1:?"Usage: ./exp2.sh <server-ip> [duration-min]"}
DURATION=${2:-15}

FEED_URL="http://$IP:32402"

echo "=== Эксперимент 2: Redis failover (${DURATION} мин, 500 RPS) ==="
echo ""
echo "В ДРУГОМ терминале на сервере выполни в нужные моменты:"
echo "  t=3min:  kubectl scale statefulset redis -n newsfeed --replicas=0"
echo "  t=8min:  kubectl scale statefulset redis -n newsfeed --replicas=1"
echo ""
echo "Нажми Enter чтобы запустить тест."
read -r

mvn -q io.gatling:gatling-maven-plugin:4.21.7:test \
    -Dgatling.simulationClass=Experiment2RedisFailoverSimulation \
    -DbaseUrlFeed="$FEED_URL" \
    -DdurationMin="$DURATION"

echo ""
echo "=== Готово. Отчёт: target/gatling/ ==="
