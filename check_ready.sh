#!/bin/bash
# Проверка готовности кластера перед запуском эксперимента.
# Использование: ./check_ready.sh <server-ip>
#
# Проверяет: все поды Running, Kafka LAG fanout-service = 0.

set -euo pipefail

APP_IP=${1:?"Usage: ./check_ready.sh <server-ip>"}

SSH="ssh -o StrictHostKeyChecking=no root@$APP_IP"

echo "=== Проверка кластера ==="

NOT_READY=$($SSH "kubectl get pods -n newsfeed --no-headers 2>/dev/null \
    | grep -v Completed | grep -v Running | wc -l")
if [ "$NOT_READY" -gt "0" ]; then
    echo "FAIL: есть поды не в Running/Completed:"
    $SSH "kubectl get pods -n newsfeed --no-headers | grep -v Completed | grep -v Running"
    exit 1
fi
echo "  Поды: все Running/Completed"

LAG=$($SSH "kubectl exec -n newsfeed kafka-0 -- \
    kafka-consumer-groups --bootstrap-server localhost:9092 \
    --describe --group fanout-service 2>/dev/null \
    | awk 'NR>1 && \$6 ~ /^[0-9]+\$/ { sum += \$6 } END { print sum+0 }'")
if [ "$LAG" != "0" ]; then
    echo "FAIL: Kafka LAG fanout-service = $LAG (ждите дренирования)"
    exit 1
fi
echo "  Kafka LAG: 0"

echo "=== Кластер готов к эксперименту ==="
