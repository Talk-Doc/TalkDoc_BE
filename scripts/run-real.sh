#!/usr/bin/env bash
#
# 실제 AI 연동 모드로 백엔드 실행:
#   - STT/LLM/TTS: Google Gemini (talkdoc.ai.provider=gemini)
#   - 수어 인식:   TalkDoc_AI HTTP 서비스 (talkdoc.sign-ai.mode=http, 기본 http://localhost:8000)
#
# 준비:
#   1. cp .env.example .env  후 GEMINI_API_KEY 를 채운다 (.env 는 git 에 올리지 않는다)
#   2. Redis 실행 (docker compose up -d redis  또는 redis-server)
#   3. TalkDoc_AI 실행 (../TalkDoc_AI/scripts/run.sh)
#
# 사용법: ./scripts/run-real.sh
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ -f .env ]]; then
  set -a; source .env; set +a
fi
if [[ -z "${GEMINI_API_KEY:-}" ]]; then
  echo "GEMINI_API_KEY 가 비어 있습니다. .env 에 채우거나 환경변수로 넘겨주세요." >&2
  exit 1
fi

export TALKDOC_AI_PROVIDER=gemini
export TALKDOC_SIGN_AI_MODE=http
export TALKDOC_SIGN_AI_URL="${TALKDOC_SIGN_AI_URL:-http://localhost:8000}"

if ! curl -sf -m 2 "${TALKDOC_SIGN_AI_URL}/health" >/dev/null; then
  echo "경고: 수어 인식 서비스(${TALKDOC_SIGN_AI_URL})가 응답하지 않습니다. 수어 인식 요청은 502 로 실패합니다." >&2
fi

exec ./gradlew bootRun --args='--spring.profiles.active=gemini'
