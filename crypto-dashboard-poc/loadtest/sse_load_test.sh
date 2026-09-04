#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Spawn N concurrent SSE clients against /api/prices/stream and watch how many
# stay connected. Run on the same box as the JVM for cleanest numbers.
#
# Usage:
#   ./loadtest/sse_load_test.sh           # default 1000 clients
#   ./loadtest/sse_load_test.sh 5000      # 5000 clients
#   ./loadtest/sse_load_test.sh 5000 60   # 5000 clients, 60s hold
#
# Then in another shell:
#   curl localhost:8080/api/prices/subscribers
#   curl localhost:8080/actuator/metrics/jvm.threads.live
# -----------------------------------------------------------------------------
set -u

N=${1:-1000}
HOLD=${2:-30}
HOST=${HOST:-localhost:8080}
URL="http://${HOST}/api/prices/stream?maxHz=2"

echo "spawning ${N} SSE clients against ${URL} for ${HOLD}s …"
echo "(each client = one curl process holding an SSE connection open)"
echo ""

# raise file-descriptor limit if possible
ulimit -n 65535 2>/dev/null || true

mkdir -p /tmp/sse_load
rm -f /tmp/sse_load/*.pid

for i in $(seq 1 "$N"); do
    # -N disables buffering; > /dev/null discards the actual SSE bytes
    curl -sN --max-time "$HOLD" "$URL" > /dev/null 2>&1 &
    echo $! >> /tmp/sse_load/pids.txt
    # tiny pause every 100 connections so we don't melt the local TCP stack
    if (( i % 100 == 0 )); then
        sleep 0.05
        echo -n "  spawned ${i} …"
    fi
done
echo ""
echo "all clients spawned. checking server-side count in 3s …"
sleep 3

echo "server reports subscribers: $(curl -s http://${HOST}/api/prices/subscribers)"
echo "jvm live threads:            $(curl -s http://${HOST}/actuator/metrics/jvm.threads.live | grep -oE '\"value\":[0-9.]+' | head -1)"
echo "heap used (MB):              $(curl -s http://${HOST}/actuator/metrics/jvm.memory.used | grep -oE '\"value\":[0-9.]+' | head -1 | awk -F: '{printf \"%.1f\", $2/1024/1024}')"
echo ""
echo "waiting ${HOLD}s for clients to finish …"
wait
echo "done."
