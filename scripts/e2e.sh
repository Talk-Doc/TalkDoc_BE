#!/usr/bin/env bash
#
# TalkDoc 백엔드 E2E 스모크 테스트
# ---------------------------------------------------------------
# http://localhost:8080 에 떠 있는 서버(기본적으로 talkdoc.ai.provider=mock,
# talkdoc.sign-ai.mode=mock 상태를 가정)를 대상으로 다음 흐름을 curl + jq로 실행한다:
#
# [레거시 흐름]
#   1. 세션 생성
#   2. 의사 질문 등록 (text 필드 사용)
#   3. 환자 수어 인식 (임시 생성한 더미 영상 업로드 x2, Mock 어댑터 사용 —
#      영상 1개당 단어 1개이므로 두 번 호출해 라벨 2개를 모은다)
#   4. 답변 미리보기 (레거시 labels 바디)
#   5. 답변 확정 (레거시 labels 바디)
#   6. 세션 조회
#   7. 진료 요약 생성
#
# [질문 버전 / 답변 초안 흐름 — 같은 세션에서 이어서 진행]
#   9.  의사 질문 등록 (version=1)
#   10. 의사 질문 수정 (PATCH questions, version +1)
#   11. 수어 인식 2회 (recognition_id 수집)
#   12. 답변 미리보기 — recognition_ids 기반 초안 저장
#   13. 답변 확정 — answer_id + version (201 기대)
#   14. 동일 확정 재호출 — 중복 확정 (200 기대, 동일 answer_id)
#   15. 의사 답변 수정 제안 — PATCH answer (pending_edit 저장, 확정 답변은 그대로)
#   16. 환자 재확정 — PATCH answer (실제 반영, version +1)
#   17. 환자 세션 조회 — drafts/recognitions 확인
#
#   18. 세션 삭제
#
# 각 단계의 응답을 출력하며, 기대하지 않은 HTTP 상태 코드를 받으면 즉시 비정상 종료한다.
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
# 기본적으로 2xx를 성공으로 간주하지만, 특정 상태 코드만 허용하려면 호출 전에
# EXPECTED_CODES="200" 처럼 공백으로 구분된 코드 목록을 지정한다 (해당 호출에만 적용됨):
#   EXPECTED_CODES="200" request "설명" GET "/path"
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

  local ok=0
  if [[ -n "${EXPECTED_CODES:-}" ]]; then
    local code
    for code in $EXPECTED_CODES; do
      [[ "$status" == "$code" ]] && ok=1
    done
  elif [[ "$status" -ge 200 && "$status" -lt 300 ]]; then
    ok=1
  fi
  if [[ "$ok" -ne 1 ]]; then
    echo "[e2e] 실패: $desc (HTTP $status)" >&2
    exit 1
  fi
  # 다음 단계에서 쓸 수 있도록 본문을 stdout이 아닌 전역 변수로 저장
  LAST_BODY="$body"
  LAST_STATUS="$status"
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

# --- 4. 답변 미리보기 (레거시 labels 바디) ---
request "4. 답변 미리보기" POST "/api/sessions/$SESSION_ID/answer/preview" \
  -H "Authorization: Bearer $PATIENT_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"labels\":${LABELS_JSON}}"

# --- 5. 답변 확정 (레거시 labels 바디) ---
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

# =================================================================
# 9~17. 질문 버전 / 답변 초안 흐름 (같은 세션에서 이어서 진행)
# =================================================================

# --- 9. 의사 질문 등록 (버전 흐름 전용 새 질문) ---
request "9. 의사 질문 등록 (버전/초안 흐름)" POST "/api/sessions/$SESSION_ID/question" \
  -H "Authorization: Bearer $DOCTOR_TOKEN" \
  -F "text=어디가 아프세요?"

QUESTION_ID_2="$(echo "$LAST_BODY" | jq -r .question_id)"
QUESTION_VERSION="$(echo "$LAST_BODY" | jq -r .version)"
echo "question_id_2=$QUESTION_ID_2 question_version=$QUESTION_VERSION"

# --- 10. 의사 질문 수정 (version +1 기대) ---
request "10. 의사 질문 수정" PATCH "/api/sessions/$SESSION_ID/questions/$QUESTION_ID_2" \
  -H "Authorization: Bearer $DOCTOR_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"text\":\"어디가 불편하세요?\",\"version\":${QUESTION_VERSION}}"

QUESTION_VERSION="$(echo "$LAST_BODY" | jq -r .version)"
echo "question_version(수정 후)=$QUESTION_VERSION"
if [[ "$QUESTION_VERSION" != "2" ]]; then
  echo "[e2e] 실패: 질문 수정 후 version이 2가 아님 ($QUESTION_VERSION)" >&2
  exit 1
fi

# --- 11. 수어 인식 2회 (recognition_id 수집) ---
request "11-1. 환자 수어 업로드/인식 (1/2)" POST "/api/sessions/$SESSION_ID/sign" \
  -H "Authorization: Bearer $PATIENT_TOKEN" \
  -F "video=@${VIDEO_FILE};type=video/webm"

REC_1="$(echo "$LAST_BODY" | jq -r .recognition_id)"
echo "recognition_id_1=$REC_1"

request "11-2. 환자 수어 업로드/인식 (2/2)" POST "/api/sessions/$SESSION_ID/sign" \
  -H "Authorization: Bearer $PATIENT_TOKEN" \
  -F "video=@${VIDEO_FILE};type=video/webm;labels=\"아프다\""

REC_2="$(echo "$LAST_BODY" | jq -r .recognition_id)"
echo "recognition_id_2=$REC_2"

# --- 12. 답변 미리보기 (recognition_ids 기반 초안 저장) ---
request "12. 답변 미리보기 (초안 저장)" POST "/api/sessions/$SESSION_ID/answer/preview" \
  -H "Authorization: Bearer $PATIENT_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"question_id\":\"${QUESTION_ID_2}\",\"question_version\":${QUESTION_VERSION},\"recognition_ids\":[\"${REC_1}\",\"${REC_2}\"]}"

DRAFT_ANSWER_ID="$(echo "$LAST_BODY" | jq -r .answer_id)"
DRAFT_VERSION="$(echo "$LAST_BODY" | jq -r .version)"
DRAFT_ANSWER_TEXT="$(echo "$LAST_BODY" | jq -r .answer)"
echo "draft.answer_id=$DRAFT_ANSWER_ID draft.version=$DRAFT_VERSION draft.answer=$DRAFT_ANSWER_TEXT"

# --- 13. 답변 확정 (초안 기반, 201 기대) ---
EXPECTED_CODES="201" request "13. 답변 확정 (초안)" POST "/api/sessions/$SESSION_ID/answer/confirm" \
  -H "Authorization: Bearer $PATIENT_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"answer_id\":\"${DRAFT_ANSWER_ID}\",\"version\":${DRAFT_VERSION}}"

VERSIONED_ANSWER_ID="$(echo "$LAST_BODY" | jq -r .answer_id)"
echo "confirmed.answer_id=$VERSIONED_ANSWER_ID (HTTP $LAST_STATUS)"

# --- 14. 동일 확정 재호출 (중복 확정, 200 기대 · 동일 answer_id · 중복 저장 없음) ---
EXPECTED_CODES="200" request "14. 답변 중복 확정" POST "/api/sessions/$SESSION_ID/answer/confirm" \
  -H "Authorization: Bearer $PATIENT_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"answer_id\":\"${DRAFT_ANSWER_ID}\",\"version\":${DRAFT_VERSION}}"

DUP_ANSWER_ID="$(echo "$LAST_BODY" | jq -r .answer_id)"
echo "duplicate-confirm.answer_id=$DUP_ANSWER_ID (HTTP $LAST_STATUS)"
if [[ "$DUP_ANSWER_ID" != "$VERSIONED_ANSWER_ID" ]]; then
  echo "[e2e] 실패: 중복 확정 시 answer_id가 달라짐 ($DUP_ANSWER_ID != $VERSIONED_ANSWER_ID)" >&2
  exit 1
fi

# --- 15. 의사 답변 수정 제안 (pending_edit, 확정 답변은 그대로) ---
request "15. 의사 답변 수정 제안" PATCH "/api/sessions/$SESSION_ID/answer/${VERSIONED_ANSWER_ID}" \
  -H "Authorization: Bearer $DOCTOR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"answer":"배가 아프고 설사를 해요.","version":1}'

PENDING_EDIT_ANSWER="$(echo "$LAST_BODY" | jq -r .pending_edit.answer)"
UNCHANGED_ANSWER="$(echo "$LAST_BODY" | jq -r .answer)"
echo "pending_edit.answer=$PENDING_EDIT_ANSWER"
echo "answer(변경 없음 확인)=$UNCHANGED_ANSWER"

# --- 16. 환자 재확정 (같은 텍스트로 PATCH → 실제 반영, version +1) ---
request "16. 환자 재확정 (pending_edit 반영)" PATCH "/api/sessions/$SESSION_ID/answer/${VERSIONED_ANSWER_ID}" \
  -H "Authorization: Bearer $PATIENT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"answer":"배가 아프고 설사를 해요.","version":1}'

UPDATED_ANSWER_VERSION="$(echo "$LAST_BODY" | jq -r .version)"
UPDATED_ANSWER_TEXT="$(echo "$LAST_BODY" | jq -r .answer)"
echo "answer.version=$UPDATED_ANSWER_VERSION answer=$UPDATED_ANSWER_TEXT"
if [[ "$UPDATED_ANSWER_VERSION" != "2" ]]; then
  echo "[e2e] 실패: 답변 반영 후 version이 2가 아님 ($UPDATED_ANSWER_VERSION)" >&2
  exit 1
fi

# --- 17. 환자 세션 조회 (drafts/recognitions 확인용) ---
request "17. 환자 세션 조회" GET "/api/sessions/$SESSION_ID" \
  -H "Authorization: Bearer $PATIENT_TOKEN"

# --- 18. 세션 삭제 ---
request "18. 세션 삭제" DELETE "/api/sessions/$SESSION_ID" \
  -H "Authorization: Bearer $DOCTOR_TOKEN"

rm -f "$VIDEO_FILE"
log "E2E 완료: 모든 단계가 성공했습니다."
