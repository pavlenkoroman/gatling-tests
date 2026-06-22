#!/bin/bash
# Оркестрация Эксперимента 2 под k8s: вместо docker compose stop/start redis
# масштабирует StatefulSet redis до 0 на 3-й минуте и обратно до 1 на 8-й.
set -uo pipefail

cd "$(dirname "$0")"

IP=${1:?"Usage: ./run-exp2.sh <server-ip> [duration-min]"}
DURATION=${2:-15}
FEED_URL="http://$IP:8083"
SSH="ssh -o StrictHostKeyChecking=no root@$IP"

echo "=== Эксперимент 2: Redis failover (${DURATION} мин, 500 RPS) ==="
echo "t=0:   старт нагрузки"

mvn -q io.gatling:gatling-maven-plugin:4.21.7:test \
    -Dgatling.simulationClass=Experiment2RedisFailoverSimulation \
    "-DbaseUrlFeed=$FEED_URL" \
    "-DdurationMin=$DURATION" &
MVN_PID=$!

sleep 180
echo "t=3min: kubectl scale statefulset/redis --replicas=0 (Redis DOWN)"
$SSH kubectl scale statefulset/redis -n newsfeed --replicas=0

sleep 300
echo "t=8min: kubectl scale statefulset/redis --replicas=1 (Redis UP)"
$SSH kubectl scale statefulset/redis -n newsfeed --replicas=1
$SSH kubectl rollout status statefulset/redis -n newsfeed --timeout=60s

echo "Ожидаю завершения Gatling (до конца ${DURATION} мин)..."
wait "$MVN_PID"

echo ""
echo "=== Эксперимент 2 завершён. Отчёт: target/gatling/ ==="
