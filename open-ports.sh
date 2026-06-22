#!/bin/bash
# Открывает порты к сервисам в k3d-кластере для нагрузочных тестов Gatling.
# Использование:
#   ./open-ports.sh start   — открыть порты (фон)
#   ./open-ports.sh stop    — закрыть порты
#
# После запуска используй ./setup.sh localhost, ./exp1.sh localhost и т.д.

set -euo pipefail

NAMESPACE="newsfeed"
PIDFILE="/tmp/gatling-port-forward.pids"

start() {
    rm -f "$PIDFILE"
    echo "=== Открываю порты ==="

    kubectl port-forward -n "$NAMESPACE" svc/user-service 8080:8080 >/tmp/pf-user.log 2>&1 &
    echo $! >> "$PIDFILE"

    kubectl port-forward -n "$NAMESPACE" svc/post-service 8081:8080 >/tmp/pf-post.log 2>&1 &
    echo $! >> "$PIDFILE"

    kubectl port-forward -n "$NAMESPACE" svc/feed-service 8083:8080 >/tmp/pf-feed.log 2>&1 &
    echo $! >> "$PIDFILE"

    sleep 2
    echo "user-service  -> localhost:8080"
    echo "post-service  -> localhost:8081"
    echo "feed-service  -> localhost:8083"
    echo ""
    echo "Запускай тесты так: ./setup.sh localhost && ./exp1.sh localhost"
}

stop() {
    if [[ -f "$PIDFILE" ]]; then
        while read -r pid; do
            kill "$pid" 2>/dev/null || true
        done < "$PIDFILE"
        rm -f "$PIDFILE"
        echo "Порты закрыты."
    else
        echo "Нет активных port-forward (файл $PIDFILE не найден)."
    fi
}

case "${1:-start}" in
    start) start ;;
    stop) stop ;;
    *) echo "Использование: $0 [start|stop]"; exit 1 ;;
esac
