#!/bin/bash
# W6D2 T2.3 memory smoke. App lifecycle (restart between phases) is driven by the operator:
#   phase1: store turn + redis direct read (hash fields / type-tag JSON / TTL)   [app fresh start]
#           -> stop app -> restart app (same Redis) ->
#   phase2: recall turn (continuity from Redis) + cross-session isolation + 25-turn window + TTL refresh
# Marker contract (see w6d2-llm-stub.jsh): marker_hits=2 on recall == history present; 1 == amnesia baseline.
# Usage: w6d2-memory-smoke.sh <phase1|phase2> [baseUrl]
set -u
BASE=${2:-http://localhost:8081}

chat() {
  curl -s -m 60 -X POST "$BASE/api/chat" -H "Content-Type: application/json" \
       -d "{\"conversationId\":\"$1\",\"message\":\"$2\",\"userId\":\"u1001\"}"
}
redis() { docker exec shopagent-redis redis-cli "$@"; }

case "${1}" in
phase1)
  echo "== T1 store turn (conv=w6d2-mem) =="
  chat w6d2-mem "store marker-ab12cd34 here please"
  echo; echo
  echo "== redis direct read: chat:memory:w6d2-mem =="
  echo "-- HGETALL (field=序号, value=type-tag JSON)"
  redis HGETALL chat:memory:w6d2-mem
  echo "-- HLEN"; redis HLEN chat:memory:w6d2-mem
  echo "-- TTL (seconds, cap 604800 = 7d)"
  redis TTL chat:memory:w6d2-mem
  ;;
phase2)
  echo "== T2 recall turn AFTER app restart (conv=w6d2-mem) =="
  echo "   (marker_hits=2 -> history came back from Redis; 1 -> amnesia baseline)"
  chat w6d2-mem "recall marker-ab12cd34"
  echo; echo
  echo "== T3 cross-session isolation (conv=w6d2-other, same marker) =="
  echo "   (expect marker_hits=1: other conversation must not see it)"
  chat w6d2-other "recall marker-ab12cd34"
  echo; echo
  echo "== TTL write-time refresh: TTL after T2 write (phase1 TTL was lower by elapsed time) =="
  echo "TTL chat:memory:w6d2-mem = $(redis TTL chat:memory:w6d2-mem)"
  ;;
phase3)
  # window semantics on conv=w6d2-win, paced 550ms (>500ms spacing keeps every turn inside the
  # 2/1s user bucket from W6D1 - the unpaced phase2 attempt was itself throttled to 3 turns, HLEN=6,
  # which is incidental proof the rate limiter guards the chat entry end-to-end)
  echo "== window semantics: 25 paced turns on conv=w6d2-win, then HLEN (expect 20) =="
  redis DEL chat:memory:w6d2-win > /dev/null
  for i in $(seq 1 25); do chat w6d2-win "win turn $i" > /dev/null; sleep 0.55; done
  echo "HLEN chat:memory:w6d2-win = $(redis HLEN chat:memory:w6d2-win)"
  echo "TTL chat:memory:w6d2-win  = $(redis TTL chat:memory:w6d2-win)"
  ;;
*)
  echo "usage: $0 <phase1|phase2> [baseUrl]" >&2
  exit 2
  ;;
esac
