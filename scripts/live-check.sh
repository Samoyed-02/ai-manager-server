#!/usr/bin/env bash
# 실제 Claude API로 주요 기능을 한 번씩 돌려 보는 점검.
# 임시 Postgres 17과 실제 Docker 이미지를 띄우고, 새로 만든 테스트 비밀번호·TOTP로 로그인한다.
# 결과는 통과/실패가 아니라 사람이 읽을 요약으로 출력하고, 끝나면 컨테이너를 모두 지운다.
#
# 사용: scripts/live-check.sh <ANTHROPIC_API_KEY가 든 env 파일> [예산 달러, 기본 2]
#   키는 파일 경로로만 받아 docker --env-file로 넘긴다. 이 스크립트는 키를 읽거나 출력하지 않는다.
#   KEEP=1이면 끝나도 컨테이너를 남긴다 (정리: docker rm -f aim-live-app aim-live-db; docker network rm aim-live)
set -uo pipefail

ENV_FILE="${1:?usage: $0 <env-file> [budget-usd]}"
BUDGET="${2:-2}"
# 진행 중 호출 몫을 남겨 두고 이 금액을 넘으면 다음 시나리오를 시작하지 않는다
STOP_AT=$(python3 -c "print($BUDGET * 0.9)")

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HELP="python3 $ROOT/scripts/live_check_helpers.py"
NET=aim-live DB=aim-live-db APP=aim-live-app IMAGE=aim-live:local PORT=18080
BASE="http://localhost:$PORT"
WORK="$(mktemp -d)"
TOKEN=""

cleanup() {
  if [ "${KEEP:-0}" != 1 ]; then
    docker rm -f "$APP" "$DB" >/dev/null 2>&1
    docker network rm "$NET" >/dev/null 2>&1
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT

section() { printf '\n=== %s ===\n' "$*"; }
api() { # api METHOD PATH [JSON]
  curl -s -X "$1" "$BASE$2" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' ${3:+-d "$3"}
}
sql() { docker exec "$DB" psql -U live -d live -tA -c "$1"; }
spent() { docker logs "$APP" 2>&1 | $HELP cost-total; }
budget_ok() {
  local s; s=$(spent)
  echo "  (누적 추정 비용 \$$s)"
  if python3 -c "import sys; sys.exit(0 if $s < $STOP_AT else 1)"; then return 0; fi
  echo "!! 예산 한도 근접(\$$s >= \$$STOP_AT): 남은 시나리오를 건너뛴다"
  return 1
}
chat() { # chat 메시지 [대화 id] — 대화 id는 $WORK/conv에 남는다
  local body
  body=$(python3 -c 'import json,sys; d={"message":sys.argv[1]}; sys.argv[2] and d.update(conversationId=sys.argv[2]); print(json.dumps(d))' "$1" "${2:-}")
  echo "  나: $1"
  curl -sN -X POST "$BASE/chat" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -H 'Accept: text/event-stream' --max-time 200 -d "$body" | $HELP sse "$WORK/conv"
}
last_call() { docker logs "$APP" 2>&1 | grep 'claude call' | tail -1 | sed 's/.*claude call /  호출: /'; }
wait_for() { # wait_for 초 설명 명령... — 명령이 성공할 때까지 5초 간격
  local limit=$1 what=$2; shift 2
  for ((i = 0; i < limit; i += 5)); do
    if "$@" >/dev/null 2>&1; then echo "  $what: ${i}초"; return 0; fi
    sleep 5
  done
  echo "  $what: ${limit}초 안에 안 됨"; return 1
}

[ -r "$ENV_FILE" ] || { echo "env 파일을 읽을 수 없음: $ENV_FILE"; exit 1; }

section "준비: 이미지 빌드, 임시 DB, 테스트 자격 증명"
docker build -q -t "$IMAGE" "$ROOT" >/dev/null || { echo "이미지 빌드 실패"; exit 1; }
PASSWORD="live-$(python3 -c 'import secrets; print(secrets.token_urlsafe(12))')"
HASH=$(printf '%s\n' "$PASSWORD" | (cd "$ROOT" && ./gradlew -q hashPassword --console=plain 2>/dev/null) | grep -E '^\$2' | tail -1)
SECRET=$($HELP new-secret)
[ -n "$HASH" ] || { echo "비밀번호 해시를 만들지 못함"; exit 1; }

docker network create "$NET" >/dev/null
docker run -d --name "$DB" --network "$NET" -e POSTGRES_USER=live -e POSTGRES_PASSWORD=live -e POSTGRES_DB=live postgres:17 >/dev/null
wait_for 60 "DB 준비" docker exec "$DB" pg_isready -U live -d live || exit 1
docker run -d --name "$APP" --network "$NET" -p "$PORT:8080" --env-file "$ENV_FILE" \
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://$DB:5432/live" -e SPRING_DATASOURCE_USERNAME=live -e SPRING_DATASOURCE_PASSWORD=live \
  -e APP_PASSWORD_HASH="$HASH" -e APP_TOTP_SECRET="$SECRET" "$IMAGE" >/dev/null
wait_for 120 "서버 기동" sh -c "docker logs $APP 2>&1 | grep -q 'Started AiManagerServerApplication'" \
  || { docker logs "$APP" 2>&1 | tail -30; exit 1; }

section "1. 로그인"
LOGIN=$(curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d "{\"password\":\"$PASSWORD\",\"totp\":\"$($HELP totp "$SECRET")\",\"deviceName\":\"live-check\"}")
TOKEN=$(python3 -c 'import json,sys; print(json.loads(sys.argv[1]).get("token",""))' "$LOGIN")
[ -n "$TOKEN" ] && echo "  로그인 성공" || { echo "  로그인 실패: $LOGIN"; exit 1; }

section "2. 기본 대화"
chat "안녕! 오늘부터 너를 내 개인 매니저로 쓸게. 짧게 인사해줘."
last_call
A=$(cat "$WORK/conv")

section "3. 웹 검색 (출처 표시)"
budget_ok && { chat "오늘 서울 날씨 어때? 출처도 알려줘." "$A"; last_call; }

section "4. 검색이 필요 없는 질문"
budget_ok && { chat "파이썬에서 리스트를 뒤집는 방법 두 가지만 짧게 알려줘." "$A"; last_call; }

section "5. 활동 기록 (log_activity)"
budget_ok && { chat "오늘 아침에 30분 달리기 했어." "$A"; last_call; }

section "6. 목표 만들기 (create_goal)"
budget_ok && { chat "이번 달부터 영어 공부를 일주일에 3시간씩 하는 걸 목표로 할래." "$A"; last_call; }

section "7. 목표에 연결된 활동"
budget_ok && { chat "방금 영어 단어 40분 외웠어." "$A"; last_call; }
echo "  활동 목록:"; api GET /activities | python3 -m json.tool
echo "  목표 목록:"; api GET /goals | python3 -m json.tool

section "8. 루틴 만들기 (create_routine)"
budget_ok && { chat "매일 아침 8시에 오늘 할 일이랑 목표 진행 상황을 브리핑해줘." "$A"; last_call; }

section "9. 기억 저장 (save_memory)"
budget_ok && { chat "참고로 나는 커피는 디카페인만 마셔. 기억해둬." "$A"; last_call; }

section "10. 삭제 확인 (바로 지우지 않고 물어보는지)"
budget_ok && { chat "아침 루틴 지워줘." "$A"; last_call; }
echo "  루틴 목록 (아직 남아 있어야 함):"; api GET /routines | python3 -m json.tool

section "11. 문서 작업 (create_task)"
if budget_ok; then
  chat "다음 주 영어 공부 계획표를 문서로 만들어줘. 내 목표에 맞춰서." "$A"; last_call
  TASK=$(api GET /tasks | python3 -c 'import json,sys; t=json.load(sys.stdin); print(t[0]["id"] if t else "")')
  if [ -n "$TASK" ]; then
    wait_for 300 "문서 작업 완료" sh -c "curl -s -H 'Authorization: Bearer $TOKEN' $BASE/tasks/$TASK | grep -qE '\"status\":\"(DONE|FAILED)\"'"
    api GET "/tasks/$TASK" | python3 -m json.tool | head -40
    DOC=$(api GET /documents | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d[0]["id"] if d else "")')
    [ -n "$DOC" ] && { echo "  문서 앞부분:"; api GET "/documents/$DOC" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("title")); print(d.get("content","")[:1500])'; }
  else
    echo "  작업이 만들어지지 않음"
  fi
fi

section "12. 통화 기록과 기억 추출 (민감 정보 제외)"
if budget_ok; then
  NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  api POST /voice/calls "{\"startedAt\":\"$NOW\",\"turns\":[
    {\"role\":\"user\",\"text\":\"오늘 헬스장 가서 하체 운동 한 시간 했어\"},
    {\"role\":\"assistant\",\"text\":\"잘했어요! 기록해 둘까요?\"},
    {\"role\":\"user\",\"text\":\"응. 아 그리고 집 와이파이 비번은 qwer1234야, 혹시 몰라서 말해둬\"},
    {\"role\":\"assistant\",\"text\":\"비밀번호 같은 정보는 따로 저장하지 않을게요.\"},
    {\"role\":\"user\",\"text\":\"요즘 저녁에는 기타 연습을 시작했어. 손가락이 아프네\"}]}"; echo
  wait_for 180 "통화 기억 추출" sh -c "docker logs $APP 2>&1 | grep 'claude call' | grep -q 'kind=memory'"
  sleep 5
  echo "  기억 목록:"; api GET /memories | python3 -m json.tool
  if api GET /memories | grep -q qwer1234; then echo "  !! 와이파이 비밀번호가 기억에 들어감"; else echo "  와이파이 비밀번호는 기억에 없음"; fi
fi

section "13. 통화용 도구 중계 (/tools/log_activity)"
api POST /tools/log_activity '{"args":{"description":"기타 코드 연습","category":"OTHER","minutes":20}}' | python3 -m json.tool

section "14. 브리핑 루틴 실행"
if budget_ok; then
  sql "update routines set next_run_at = now() where kind = 'BRIEFING'"
  wait_for 180 "브리핑 알림" sh -c "curl -s -H 'Authorization: Bearer $TOKEN' $BASE/notifications | grep -q '\"id\"'"
  sleep 3
  api GET /notifications | python3 -c 'import json,sys
for n in json.load(sys.stdin): print("  [" + str(n.get("title")) + "]"); print("    " + str(n.get("body", n.get("message", ""))).replace("\n", "\n    "))'
fi

section "15. 주간 회고 루틴 실행"
if budget_ok; then
  sql "update routines set next_run_at = now() where kind = 'REVIEW'"
  wait_for 180 "회고 저장" sh -c "curl -s -H 'Authorization: Bearer $TOKEN' $BASE/reviews | grep -q '\"id\"'"
  R=$(api GET /reviews | python3 -c 'import json,sys; r=json.load(sys.stdin); print(r[0]["id"] if r else "")')
  [ -n "$R" ] && api GET "/reviews/$R" | python3 -m json.tool
fi

section "16. 새 대화에서 맥락 이어가기"
if budget_ok; then
  # 채팅 기억 추출은 30분 조용해야 시작한다. 기다리지 않도록 시각을 당긴다
  sql "update conversations set updated_at = now() - interval '31 minutes' where channel = 'CHAT'"
  wait_for 180 "채팅 기억 추출" sh -c "[ \$(docker logs $APP 2>&1 | grep 'claude call' | grep -c 'kind=memory') -ge 2 ]"
  sleep 5
  chat "나에 대해 기억하는 거랑 요즘 내 목표, 이번 주에 한 활동을 정리해줘. 그리고 집 와이파이 비번 기억나?"
  last_call
fi

section "17. 비용 집계 (claude.usage)"
docker logs "$APP" 2>&1 | $HELP cost
echo
echo "  오류·경고 로그:"
docker logs "$APP" 2>&1 | grep -E ' (ERROR|WARN) ' | grep -v 'APP_PASSCODE' | tail -20 || true
