#!/bin/bash
# W6D5 混沌 C7：熔断故障注入与恢复回归（最终 W6 构建重验 D3 语义）。
# phase1: 应用以 DEEPSEEK_BASE_URL=http://127.0.0.1:9 起动（真实连接拒绝），断言
#         6 连败 → OPEN（5/5=100%）→ OPEN 短路（<0.5s 不发起连接）→ 20s 后半开探测失败 → 回 OPEN。
# phase2: 应用换桩 LLM（w6d4-llm-stub-toolcall.jsh）重起 → force-open → 规则回复 →
#         POST reset（模拟 API 恢复）→ 正常回答。
# 用法: c7-circuit-break.sh <phase1|phase2> [baseUrl]   应用生命周期由操作者按 phase 切换
set -u
BASE=${2:-http://localhost:8081}
DEV="$BASE/api/dev/resilience/breaker"

chat() { curl -s -o /dev/null -w "%{http_code} %{time_total}s" -m 60 -X POST "$BASE/api/chat" \
         -H "Content-Type: application/json" -d "{\"conversationId\":\"c7-$1\",\"message\":\"msg $1\",\"userId\":\"c7-$1\"}"; }

state() { curl -s "$DEV"; }

case "${1}" in
phase1)
  echo "== C7 phase1: fault injection (LLM dead) =="
  echo "state before: $(state)"
  for i in 1 2 3 4 5 6; do echo "[call-$i] $(chat $i)"; sleep 0.7; done
  echo "state after 6 calls (expect OPEN, failedCalls>=5): $(state)"
  echo "short-circuit timing: [call-7] $(chat 7)  (expect <0.5s, not-permitted)"
  echo "state: $(state)"
  echo "-- wait 21s (waitDurationInOpenState=20s) then probe: half-open fails -> back OPEN --"
  sleep 21
  echo "[probe] $(chat probe)"
  echo "state after probe (expect OPEN): $(state)"
  ;;
phase2)
  echo "== C7 phase2: recovery demo (stub LLM working) =="
  echo "normal answer: $(curl -s -m 60 -X POST "$BASE/api/chat" -H 'Content-Type: application/json' \
       -d '{"conversationId":"c7-rec","message":"hello","userId":"c7-rec"}')"
  echo "force-open: $(curl -s -X POST "$DEV/force-open")"
  echo "degraded reply while FORCED_OPEN: $(printf '{"conversationId":"c7-rec","message":"hello again","userId":"c7-rec2"}' > /tmp/c7.json; curl -s -m 60 -X POST --data-binary @/tmp/c7.json "$BASE/api/chat" -H 'Content-Type: application/json; charset=utf-8')"
  echo "reset: $(curl -s -X POST "$DEV/reset")"
  echo "answer after reset (expect normal LLM reply): $(curl -s -m 60 -X POST --data-binary @/tmp/c7.json "$BASE/api/chat" -H 'Content-Type: application/json; charset=utf-8')"
  ;;
*)
  echo "usage: $0 <phase1|phase2> [baseUrl]" >&2
  exit 2
  ;;
esac
