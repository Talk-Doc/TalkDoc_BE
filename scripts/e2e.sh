#!/usr/bin/env bash
#
# TalkDoc 백엔드 E2E 스모크 테스트
# ---------------------------------------------------------------
# http://localhost:8080 에 떠 있는 서버(기본적으로 talkdoc.ai.provider=mock,
# talkdoc.sign-ai.mode=mock 상태를 가정)를 대상으로 다음 흐름을 curl + jq로 실행한다:
#   1. 세션 생성
#   2. 의사 질문 등록 (text 필드 사용)
#   3. 환자 수어 인식 (임시 생성한 더미 영상 업로드 x2, Mock 어댑터 사용 —
#      영상 1개당 단어 1개이므로 두 번 호출해 라벨 2개를 모은다)
#   4. 답변 미리보기
#   5. 답변 확정
#   6. 세션 조회
#   7. 진료 요약 생성
#   8. 세션 삭제
# 각 단계의 응답을 출력하며, HTTP 상태 코드가 2xx가 아니면 즉시 비정상 종료한다.
#
# 사용법: ./scripts/e2e.sh [BASE_URL]
#   (BASE_URL 기본값: http://localhost:8080)
#
# 필요 도구: curl, jq
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
VIDEO_FILE="/tmp/talkdoc-e2e-sign.webm"

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$1"; }

# curl 호출 후 HTTP 상태코드를 검사하고 응답 본문을 출력하는 헬퍼.
# 사용법: request <설명> <METHOD> <path> [curl 추가 옵션...]
request() {
  local desc="$1" method="$2" path="$3"
  shift 3
  log "$desc"
  local tmp_body
  tmp_body="$(mktemp)"
  local status
  status="$(curl -sS -o "$tmp_body" -w '%{http_code}' -X "$method" "$BASE_URL$path" "$@")"
  local body
  body="$(cat "$tmp_body")"
  rm -f "$tmp_body"
  echo "$body" | jq . 2>/dev/null || echo "$body"
  if [[ "$status" -lt 200 || "$status" -ge 300 ]]; then
    echo "[e2e] 실패: $desc (HTTP $status)" >&2
    exit 1
  fi
  # 다음 단계에서 쓸 수 있도록 본문을 stdout이 아닌 전역 변수로 저장
  LAST_BODY="$body"
}

command -v jq >/dev/null 2>&1 || { echo "[e2e] jq가 필요합니다 (brew install jq)" >&2; exit 1; }

# --- 0. 더미 수어 영상 생성 (Mock 어댑터이므로 내용은 중요하지 않음) ---
log "더미 영상 파일 생성: $VIDEO_FILE"
head -c 1024 /dev/urandom > "$VIDEO_FILE"

# --- 1. 세션 생성 ---
request "1. 세션 생성" POST "/api/sessions" \
  -H "Content-Type: application/json" -d '{}'

SESSION_ID="$(echo "$LAST_BODY" | jq -r .session_id)"
DOCTOR_TOKEN="$(echo "$LAST_BODY" | jq -r .doctor_token)"
PATIENT_TOKEN="$(echo "$LAST_BODY" | jq -r .patient_token)"
echo "session_id=$SESSION_ID"

# --- 2. 의사 질문 등록 (text 필드) ---
request "2. 의사 질문 등록" POST "/api/sessions/$SESSION_ID/question" \
  -H "Authorization: Bearer $DOCTOR_TOKEN" \
  -F "text=어디가 아파서 오셨어요?"

QUESTION_ID="$(echo "$LAST_BODY" | jq -r .question_id)"
echo "question_id=$QUESTION_ID"

# --- 3. 환자 수어 인식 (영상 1개당 단어 1개이므로 두 번 호출) ---
# 3-1. 첫 번째 영상 (Mock 기본값: "배")
request "3-1. 환자 수어 업로드/인식 (1/2)" POST "/api/sessions/$SESSION_ID/sign" \
  -H "Authorization: Bearer $PATIENT_TOKEN" \
  -F "video=@${VIDEO_FILE};type=video/webm"

LABEL_1="$(echo "$LAST_BODY" | jq -r '.sign.label // .accepted_labels[0] // empty')"
echo "sign[1].label=$LABEL_1"

# 3-2. 두 번째 영상 (Mock 디버그 오버라이드: Content-Type 에 ;labels=아프다 를 붙여
#      "아프다" 를 반환하도록 지정)
request "3-2. 환자 수어 업로드/인식 (2/2)" POST "/api/sessions/$SESSION_ID/sign" \
  -H "Authorization: Bearer $PATIENT_TOKEN" \
  -F "video=@${VIDEO_FILE};type=video/webm;labels=\"아프다\""

LABEL_2="$(echo "$LAST_BODY" | jq -r '.sign.label // .accepted_labels[0] // empty')"
echo "sign[2].label=$LABEL_2"

# 두 인식 결과에서 얻은 라벨을 답변 미리보기/확정에 사용할 배열로 모은다.
LABELS_JSON="$(jq -n --arg l1 "$LABEL_1" --arg l2 "$LABEL_2" \
  '[$l1, $l2] | map(select(length > 0))')"
echo "labels=$LABELS_JSON"

# --- 4. 답변 미리보기 ---
request "4. 답변 미리보기" POST "/api/sessions/$SESSION_ID/answer/preview" \
  -H "Authorization: Bearer $PATIENT_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"labels\":${LABELS_JSON}}"

# --- 5. 답변 확정 ---
request "5. 답변 확정" POST "/api/sessions/$SESSION_ID/answer/confirm" \
  -H "Authorization: Bearer $PATIENT_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"labels\":${LABELS_JSON}}"

ANSWER_ID="$(echo "$LAST_BODY" | jq -r .answer_id)"
echo "answer_id=$ANSWER_ID"

# --- 6. 세션 조회 ---
request "6. 세션 조회" GET "/api/sessions/$SESSION_ID" \
  -H "Authorization: Bearer $DOCTOR_TOKEN"

# --- 7. 진료 요약 생성 ---
request "7. 진료 요약 생성" POST "/api/sessions/$SESSION_ID/summary" \
  -H "Authorization: Bearer $DOCTOR_TOKEN"

# --- 8. 세션 삭제 ---
request "8. 세션 삭제" DELETE "/api/sessions/$SESSION_ID" \
  -H "Authorization: Bearer $DOCTOR_TOKEN"

rm -f "$VIDEO_FILE"
log "E2E 완료: 모든 단계가 성공했습니다."
