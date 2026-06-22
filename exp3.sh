#!/bin/bash
# Эксперимент 3 — celebrity threshold.
# Использование: ./exp3.sh <server-ip>
#
# 2 000 RPS GET /feed в течение 8 минут.
# t=2min: celebrity публикует пост (FanoutService должен пропустить fanout).
# t=5min: обычный пользователь публикует пост (контрольный fanout).
#
# Требует: src/test/resources/celebrity-id.txt и user-ids.csv
# (создаются через setup.sh).

set -euo pipefail

IP=${1:?"Usage: ./exp3.sh <server-ip>"}

USER_URL="http://$IP:8080"
POST_URL="http://$IP:8081"
FEED_URL="http://$IP:8083"

if [ ! -f src/test/resources/celebrity-id.txt ]; then
    echo "ERROR: celebrity-id.txt не найден. Сначала запусти setup.sh."
    exit 1
fi

echo "=== Эксперимент 3: celebrity threshold (8 мин, 2 000 RPS) ==="
echo "  celebrity ID: $(cat src/test/resources/celebrity-id.txt)"
echo ""
mvn -q gatling:test \
    -Dgatling.simulationClass=Experiment3CelebritySimulation \
    -DbaseUrlFeed="$FEED_URL" \
    -DbaseUrlPost="$POST_URL" \
    -DbaseUrlUser="$USER_URL"

echo ""
echo "=== Готово. Отчёт: target/gatling/ ==="
