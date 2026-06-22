#!/bin/bash
# Оркестрация Эксперимента 2 под k8s: вместо docker compose stop/start redis
# масштабирует StatefulSet redis до 0 на 3-й минуте и обратно до 1 на 8-й.
set -uo pipefail

cd "$(dirname "$0")"

DURATION=${1:-15}
FEED_URL="http://localhost:8083"

echo "=== Эксперимент 2: Redis failover (${DURATION} мин, 500 RPS) ==="
echo "t=0:   старт нагрузки"

mvn -q gatling:test \
    -Dgatling.simulationClass=Experiment2RedisFailoverSimulation \
    "-DbaseUrlFeed=$FEED_URL" \
    "-DdurationMin=$DURATION" &
MVN_PID=$!

sleep 180
echo "t=3min: kubectl scale statefulset/redis --replicas=0 (Redis DOWN)"
kubectl scale statefulset/redis -n newsfeed --replicas=0

sleep 300
echo "t=8min: kubectl scale statefulset/redis --replicas=1 (Redis UP)"
kubectl scale statefulset/redis -n newsfeed --replicas=1
kubectl rollout status statefulset/redis -n newsfeed --timeout=60s

echo "Ожидаю завершения Gatling (до конца ${DURATION} мин)..."
wait "$MVN_PID"

echo ""
echo "=== Эксперимент 2 завершён. Отчёт: target/gatling/ ==="
