#!/bin/bash
# Засев данных перед нагрузочными тестами.
# Использование: ./setup.sh <server-ip>
#
# Создаёт 10 seed-авторов, 1 celebrity, 10 000 пользователей,
# подписки и 100 постов. Записывает CSV feeders в src/test/resources/.
# Продолжительность: ~10 минут.

set -euo pipefail

IP=${1:?"Usage: ./setup.sh <server-ip>"}

USER_URL="http://$IP:8080"
POST_URL="http://$IP:8081"
SSH="ssh -o StrictHostKeyChecking=no root@$IP"

echo "=== Setup: засев данных ==="
mvn -q gatling:test \
    -Dgatling.simulationClass=SetupSimulation \
    -DbaseUrlUser="$USER_URL" \
    -DbaseUrlPost="$POST_URL"

echo ""
echo "=== Ожидание дренирования Kafka (LAG → 0) ==="
MAX_WAIT=300
ELAPSED=0
while [ $ELAPSED -lt $MAX_WAIT ]; do
    LAG=$($SSH "kubectl exec -n newsfeed kafka-0 -- \
        kafka-consumer-groups --bootstrap-server localhost:9092 \
        --describe --group fanout-service 2>/dev/null \
        | awk 'NR>1 && \$6 ~ /^[0-9]+\$/ { sum += \$6 } END { print sum+0 }'")
    if [ "$LAG" = "0" ]; then
        echo "  LAG=0 — все события обработаны."
        break
    fi
    echo "  LAG=$LAG, ждём 10s..."
    sleep 10
    ELAPSED=$((ELAPSED + 10))
done
if [ $ELAPSED -ge $MAX_WAIT ]; then
    echo "  WARN: LAG не дошёл до 0 за ${MAX_WAIT}s, продолжаем."
fi

echo ""
echo "Feeders записаны в src/test/resources/"
echo "  user-ids.csv     — $(wc -l < src/test/resources/user-ids.csv) строк"
echo "  post-ids.csv     — $(wc -l < src/test/resources/post-ids.csv) строк"
echo "  celebrity-id.txt — $(cat src/test/resources/celebrity-id.txt)"
