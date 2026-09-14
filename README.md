# TalkDoc (톡닥) Backend

## 프로젝트 소개

톡닥(TalkDoc)은 병원 외래 초진 상황에서 의료진과 청각장애인 환자 사이의 문진 소통을
돕는 모바일 웹 서비스입니다. 의료진이 음성으로 질문하면 AI가 이를 텍스트/의도로 변환하고,
환자는 수어로 답하면 AI가 이를 인식해 한국어 문장으로 재구성하여 의료진에게 전달합니다.
본 저장소는 이 서비스의 백엔드(API 서버)이며, Google Gemini API 키가 없어도 Mock 어댑터로
전체 흐름을 그대로 확인할 수 있습니다.

## 아키텍처 다이어그램

```
 ┌───────────────┐        ┌────────────────┐
 │  Doctor Web   │        │  Patient Web   │
 │ (음성 질문 입력) │        │  (수어 촬영/답변) │
 └───────┬───────┘        └────────┬───────┘
         │  REST / WebSocket        │  REST / WebSocket
         ▼                          ▼
      ┌───────────────────────────────────┐
      │        TalkDoc Backend (본 저장소)   │
      │   Spring Boot 3.5 / Java 21         │
      └───────┬───────────────┬────────────┘
              │               │
              ▼               ▼
      ┌───────────────┐ ┌───────────────────────┐
      │     Redis      │ │  Google Gemini API      │
      │ (세션 임시 데이터, │ │  STT / LLM / TTS         │
      │  TTL 2h)        │ └───────────────────────┘
      └───────────────┘
              │
              ▼
      ┌───────────────────────────────────┐
      │  TalkDoc-VisionAI (Flask, 5001)    │
      │  수어 인식 POST /predict             │
      └───────────────────────────────────┘
```

- 저장소는 **Redis 하나만** 사용합니다. 세션 단위 임시 데이터를 TTL 2시간으로 보관하며,
  세션 종료 시 즉시 삭제합니다.
- STT/LLM/TTS는 Google Gemini API를 호출합니다 (`talkdoc.ai.provider=gemini`).
- 수어 인식은 팀의 실제 Vision AI 서비스 `TalkDoc-VisionAI`(Flask, 5001 포트)에 HTTP로
  위임합니다 (`talkdoc.sign-ai.mode=http`). 계약은
  [docs/ai-service-contract.md](docs/ai-service-contract.md) 참고.
- 두 외부 연동 모두 Mock 어댑터가 있어(`provider=mock`, `mode=mock`) API 키/외부 서비스
  없이도 전체 흐름을 로컬에서 동작시킬 수 있습니다.

## 요구 사항

- JDK 21 (Gradle 툴체인이 자동으로 찾아 사용합니다. `gradle.properties`에 로컬 설치 경로가
  지정되어 있습니다.)
- Docker (Redis 컨테이너 실행용, 선택적으로 앱 컨테이너도 포함)
- Redis 7.x (Docker로 실행하는 것을 권장)

## 빠른 시작

```bash
# 1. Redis만 기동 (docker-compose.yml의 app 서비스는 profiles: ["app"]이라 기본 실행되지 않음)
docker compose up -d redis

# 2. 애플리케이션 실행 (local 프로필: Mock AI 어댑터, DEBUG 로깅)
./gradlew bootRun --args='--spring.profiles.active=local'

# 3. Swagger UI 확인
open http://localhost:8080/swagger-ui.html

# 4. 헬스체크
curl http://localhost:8080/actuator/health
```

애플리케이션까지 컨테이너로 함께 띄우려면:

```bash
docker compose --profile app up -d --build
```

## 실제 AI 연동으로 실행하기 (Gemini + TalkDoc-VisionAI)

Mock 대신 실제 음성 인식/문장 생성/음성 합성(Gemini)과 수어 인식(TalkDoc-VisionAI)을 붙여서 돌리려면:

```bash
cp .env.example .env            # GEMINI_API_KEY 채우기
docker compose up -d redis      # 또는 redis-server
(cd ../TalkDoc-VisionAI && python3.12 -m venv .venv && .venv/bin/pip install -r requirements.txt)  # 최초 1회
(cd ../TalkDoc-VisionAI && .venv/bin/python app.py)   # 수어 인식 서버, 5001 포트
./scripts/run-real.sh           # gemini 프로필 + sign-ai http 모드로 bootRun
```

`run-real.sh` 는 `.env` 를 읽어 `TALKDOC_AI_PROVIDER=gemini`, `TALKDOC_SIGN_AI_MODE=http` 로 실행합니다.
프론트(TalkDoc_FE)는 `npm run dev` 그대로 두면 됩니다. 수어 인식 서비스 구성은
[../TalkDoc-VisionAI/README.md](../TalkDoc-VisionAI/README.md) 참고.

## 환경 변수

`application.yml`에 정의된 것과 동일한 이름입니다. 전체 목록은 [.env.example](.env.example)
참고.

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `REDIS_HOST` | `localhost` | Redis 호스트 |
| `REDIS_PORT` | `6379` | Redis 포트 |
| `SERVER_PORT` | `8080` | 애플리케이션 HTTP 포트 |
| `TALKDOC_AI_PROVIDER` | `mock` | STT/LLM/TTS 제공자. `mock` \| `gemini` |
| `GEMINI_API_KEY` | (없음) | Gemini API 키. `TALKDOC_AI_PROVIDER=gemini`일 때 필수 |
| `GEMINI_STT_MODEL` | `gemini-3.7-flash` | STT에 사용할 Gemini 모델 (lite 계열은 받아쓰기 정확도가 낮음) |
| `GEMINI_LLM_MODEL` | `gemini-3.5-flash-lite` | 의도 분석/문장 재구성/요약용. thinking 없는 lite 가 1초 내 응답 |
| `GEMINI_THINKING_LEVEL` | `low` | Gemini 3.x thinking 수준. 비우면 모델 기본값(호출당 20~30초, 출력 토큰 소진) |
| `GEMINI_TTS_MODEL` | `gemini-2.5-flash-preview-tts` | TTS에 사용할 Gemini 모델 |
| `TALKDOC_SIGN_AI_MODE` | `mock` | 수어 인식 연동 방식. `mock` \| `http` |
| `TALKDOC_SIGN_AI_URL` | `http://localhost:5001` | `mode=http`일 때 TalkDoc-VisionAI base URL |
| `TALKDOC_SIGN_AI_TOKEN` | (없음) | TalkDoc-VisionAI 서비스 토큰. 기본은 비워두면 미전송이며, 운영에서는 VisionAI의 `VISION_API_TOKEN`과 같은 값으로 설정 |
| `TALKDOC_WS_ORIGINS` | `*` | WebSocket 허용 Origin (콤마 구분, `*`는 전체 허용) |

그 외 `talkdoc.session.ttl`(2h), `talkdoc.sign.confidence-threshold`(0.75),
`talkdoc.ai.timeout`(15s), `talkdoc.sign-ai.connect-timeout`(5s),
`talkdoc.sign-ai.read-timeout`(60s), `talkdoc.sign-ai.max-retries`(2)는 환경 변수가 아닌
`application.yml`의 고정 기본값입니다 (필요 시 YAML 직접 수정).

## 프로필 설명

| 프로필 | 파일 | 용도 |
|--------|------|------|
| `local` | `application-local.yml` | 로컬 개발. Mock AI/수어 인식 어댑터, `com.talkdoc` DEBUG 로깅, Swagger UI 활성화 |
| `gemini` | `application-gemini.yml` | 실제 Gemini STT/LLM/TTS 연동. `GEMINI_API_KEY` 필요 |

프로필을 지정하지 않으면 `application.yml`의 기본값(`talkdoc.ai.provider=mock`,
`talkdoc.sign-ai.mode=mock`)이 그대로 적용됩니다.

## API 요약

베이스 경로는 `/api/sessions`이며, 인증이 필요한 요청은 역할(DOCTOR/PATIENT)이 함께
표기되어 있습니다.

| Method | Path | 역할 | 설명 | 주요 응답 필드 |
|--------|------|------|------|----------------|
| POST | `/api/sessions` | 공개 | 세션 생성 | `session_id`, `doctor_token`, `patient_token`, `created_at`, `patient_join_path` |
| POST | `/api/sessions/{sessionId}/question` | DOCTOR | 질문 등록 (multipart `audio` 또는 `text` 필드) | `question_id`, `text`, `intent`, `intents`, `candidates`, `supported`, `answer_mode`, `card_options`, `asked_at`, `version`(=1), `updated_at`(null) |
| PATCH | `/api/sessions/{sessionId}/questions/{questionId}` | DOCTOR | 질문 텍스트 수정 — 의도 재분석, `version` +1 (`{text, version}`) | POST 질문과 동일 필드 (`answer_mode`, `card_options` 재계산) + `version`, `updated_at` |
| POST | `/api/sessions/{sessionId}/sign` | PATIENT | 수어 영상 인식 — 영상 1개당 단어 1개 (multipart `video`, 선택 `duration` 초) | `question_id`, `intents`, `candidates`, `sign`(`label`,`confidence`,`accepted`,`reason`), `signs[]`(0~1개, 호환용), `all_accepted`, `accepted_labels`, `model_version`, `request_id`, `processing_ms`, `recognition_id`, `question_version` |
| POST | `/api/sessions/{sessionId}/answer/preview` | PATIENT | 답변 미리보기 및 초안 저장 — 라벨 또는 recognition_id 기반 (`{labels}` 또는 `{question_id, question_version, recognition_ids}`) | `question_id`, `question_version`, `labels`, `answer`, `answer_id`, `version`(=1), `recognition_ids` |
| POST | `/api/sessions/{sessionId}/answer/confirm` | PATIENT | 답변 확정 — 레거시 라벨 또는 초안 기반 (`{labels, answer?}` 또는 `{answer_id, version, answer?}`) | Conversation: `answer_id`, `question_id`, `question`, `intents`, `signs`, `answer`, `confirmed_at`, `question_version`, `version`, `edited_by`, `edited_at`, `pending_edit` |
| PATCH | `/api/sessions/{sessionId}/answer/{answerId}` | DOCTOR \| PATIENT | 답변 수정 (`{answer, version?}`) — 의사는 `pending_edit`로 제안만, 환자는 즉시 반영·`version` +1 | Conversation |
| GET | `/api/sessions/{sessionId}` | DOCTOR \| PATIENT | 세션 상세 조회 — PATIENT는 `drafts`, `recognitions` 추가 수신(재연결 복구용) | `session_id`, `status`, `created_at`, `current_question`(+`question_version`), `conversations[]` |
| POST | `/api/sessions/{sessionId}/summary` | DOCTOR | 진료 요약 생성 | `summary`, `conversation_count`, `generated_at` |
| GET | `/api/sessions/{sessionId}/answer/{answerId}/tts` | DOCTOR (선택) | 답변 음성 합성 | `audio/wav` 스트림 |
| DELETE | `/api/sessions/{sessionId}` | DOCTOR | 세션 종료/삭제 | 204 No Content |
| GET | `/ws/sessions/{sessionId}?token=` | (토큰 필요) | WebSocket 연결 | 실시간 이벤트 스트림 |

`answer_mode`가 `SIGN_REQUIRED`면 프론트는 수어 촬영 UI를, `CARD_SELECT`면 `card_options`를
선택 카드로 보여줍니다(옆에 "직접 작성"/"수어로 답변" 전환 버튼 포함). 카드를 고르면
`/sign`·`/answer/preview` 단계 없이 바로 `/answer/confirm`에 `{"labels": [], "answer": "<카드 문구>"}`로
확정하면 됩니다. 어떤 Intent가 카드형인지는 `StaticAnswerModeResolver`의 고정 테이블로 정해집니다.

## 질문 버전과 답변 초안

질문을 등록하면 `version=1`로 시작하고, 의사가 `PATCH .../questions/{questionId}`로 텍스트를
고치면 `version`이 +1 됩니다. 환자가 수어를 인식시키면 각 결과는 `recognition_id`로 세션에
저장되고(`POST /sign` 응답), `POST /answer/preview`는 이 `recognition_id`들(또는 레거시
`labels`)로 답변 문장을 만들어 **초안**(`answer_id`, `version=1`)으로 저장합니다. 환자가
`POST /answer/confirm`을 `answer_id` + `version`으로 호출하면 그 초안이 확정되며, 같은
호출을 반복해도(중복 확정) 200으로 동일한 답변만 돌려주고 새로 저장하지 않습니다. 의사가
질문을 수정하면 이전 버전에서 만들어진 미확정 초안은 모두 무효화됩니다(인식 결과 자체는
유지). 확정된 답변을 의사가 고치고 싶을 때는 `PATCH /answer/{answerId}`로 제안하며, 이는
바로 반영되지 않고 `pending_edit`로 표시된 채 `ANSWER_EDIT_PROPOSED` 이벤트만 발생합니다 —
환자가 같은 텍스트로 다시 `PATCH`해야 `version`이 올라가며 실제로 반영됩니다. 레거시 요청
바디(`labels`, `{labels, answer?}`)는 프론트 호환을 위해 계속 동작합니다. 필드 단위 계약과
JSON 예시는 [docs/answer-versioning.md](docs/answer-versioning.md) 참고.

## 인증 방식

- 세션 생성 응답의 `doctor_token` / `patient_token`을 이후 요청에 사용합니다.
- REST 요청: `Authorization: Bearer <token>` 헤더 또는 `X-Session-Token: <token>` 헤더 중
  하나를 사용합니다 (둘 다 지원, `Authorization` 우선).
- WebSocket 연결은 쿼리 파라미터로 토큰을 전달합니다: `?token=<token>`.
- 토큰은 발급된 세션에만 유효하며, 경로의 `sessionId`와 토큰의 세션이 다르면
  `FORBIDDEN`(403)이 반환됩니다.

## WebSocket 이벤트

연결: `ws://<host>/ws/sessions/{sessionId}?token=<doctor_token 또는 patient_token>`

메시지 형식(`SessionEvent`):

```json
{
  "type": "QUESTION_POSTED",
  "session_id": "3f2a...",
  "payload": { "...": "..." },
  "timestamp": "2026-09-03T12:34:56.789Z"
}
```

| type | 설명 | payload |
|------|------|---------|
| `QUESTION_POSTED` | 의사가 질문을 등록함 | PendingQuestion (환자·의사 모두에게 전달) |
| `QUESTION_UPDATED` | 의사가 질문 텍스트를 수정함(`version` +1) | PendingQuestion |
| `ANSWER_CONFIRMED` | 환자가 답변을 확정함 | Conversation (의사·환자 모두에게 전달) |
| `ANSWER_EDIT_PROPOSED` | 의사가 확정된 답변의 수정을 제안함(`pending_edit`) | Conversation |
| `ANSWER_UPDATED` | 환자가 답변 텍스트 수정을 반영함(`version` +1) | Conversation |
| `SESSION_CLOSED` | 세션이 종료됨 | `{"session_id": "..."}` (직후 연결이 닫힘) |
| `PEER_JOINED` | 상대 역할이 접속함 | `{"role": "DOCTOR"|"PATIENT"}` |

수신 확인용 스크립트: `node scripts/ws-client.mjs <sessionId> <token>` (아래 참고).

## 에러 응답 형식

모든 에러는 다음과 같은 통일된 JSON 형식으로 반환됩니다.

```json
{
  "code": "SESSION_NOT_FOUND",
  "message": "세션을 찾을 수 없습니다.",
  "timestamp": "2026-09-03T12:34:56.789Z"
}
```

| code | HTTP 상태 | 기본 메시지 |
|------|-----------|-------------|
| `INVALID_REQUEST` | 400 | 요청 형식이 올바르지 않습니다. |
| `UNAUTHORIZED` | 401 | 세션 토큰이 없거나 유효하지 않습니다. |
| `FORBIDDEN` | 403 | 이 세션 또는 역할로는 접근할 수 없습니다. |
| `SESSION_NOT_FOUND` | 404 | 세션을 찾을 수 없습니다. |
| `ANSWER_NOT_FOUND` | 404 | 답변을 찾을 수 없습니다. |
| `QUESTION_NOT_FOUND` | 404 | 질문을 찾을 수 없습니다. |
| `DRAFT_NOT_FOUND` | 404 | 답변 초안을 찾을 수 없습니다. |
| `RECOGNITION_NOT_FOUND` | 404 | 인식 결과를 찾을 수 없습니다. |
| `NO_PENDING_QUESTION` | 409 | 현재 답변 대기 중인 질문이 없습니다. |
| `QUESTION_ALREADY_ANSWERED` | 409 | 확정 답변이 있는 질문은 수정할 수 없습니다. |
| `VERSION_CONFLICT` | 409 | 버전이 일치하지 않습니다. |
| `DRAFT_INVALIDATED` | 409 | 무효화된 답변 초안입니다. |
| `SESSION_CLOSED` | 409 | 이미 종료된 세션입니다. |
| `FILE_TOO_LARGE` | 413 | 업로드 파일이 너무 큽니다. |
| `UNSUPPORTED_MEDIA` | 415 | 지원하지 않는 파일 형식입니다. |
| `SIGN_VIDEO_REJECTED` | 422 | 영상을 인식할 수 없습니다. 다시 촬영해주세요. |
| `STT_FAILED` | 502 | 음성 인식에 실패했습니다. |
| `LLM_FAILED` | 502 | 언어 모델 호출에 실패했습니다. |
| `SIGN_AI_FAILED` | 502 | 수어 인식 서비스 호출에 실패했습니다. |
| `TTS_FAILED` | 502 | 음성 합성에 실패했습니다. |
| `INTERNAL_ERROR` | 500 | 서버 내부 오류가 발생했습니다. |

## curl E2E 예시

전체 흐름을 그대로 재현한 스크립트가 [scripts/e2e.sh](scripts/e2e.sh)입니다
(`curl`, `jq` 필요).

```bash
docker compose up -d redis
./gradlew bootRun --args='--spring.profiles.active=local' &
./scripts/e2e.sh
```

스크립트는 다음을 순서대로 수행합니다: (1~8, 레거시 흐름) 세션 생성 → 의사 질문 등록
(`text` 필드) → 환자 수어 업로드(더미 영상, Mock 어댑터로 처리) → 답변 미리보기 → 답변
확정 → 세션 조회 → 진료 요약 생성 → 세션 삭제. 이어서 (9~17, 버전/초안 흐름) 새 세션에서
질문 등록 → 질문 수정(`version` 증가 확인) → 수어 인식 2회(`recognition_id` 수집) →
`recognition_ids` 기반 미리보기(초안 저장) → `answer_id`+`version`으로 확정 → 동일 확정
재호출(200, 중복 저장 없음 확인) → 의사의 답변 수정 제안(`pending_edit`) → 환자 재확정으로
반영(`version` +1) → 환자 세션 조회(`drafts`/`recognitions` 확인) → 세션 삭제까지 진행합니다.
각 단계 응답을 출력하고, HTTP 에러 발생 시 즉시 비정상 종료합니다.

## 개인정보·안전 원칙

- **영상/음성 미보관**: 업로드된 수어 영상과 음성 파일은 인식/변환 직후 즉시 폐기하며
  디스크나 DB에 저장하지 않습니다. Redis에도 원본 미디어는 저장하지 않고, 세션의 텍스트성
  임시 데이터(질문/답변/토큰 등)만 TTL 2시간으로 보관합니다. 답변 초안(`session:{id}:drafts`)과
  인식 결과 목록(`session:{id}:recognitions`)도 라벨/텍스트만 담는 텍스트성 데이터이며 같은
  TTL로 세션 삭제 시 함께 제거됩니다.
- **익명 세션**: 세션은 회원가입/로그인 없이 발급되는 익명 토큰(`doctor_token`,
  `patient_token`) 기반으로 동작하며, 세션 종료 시 관련 데이터를 즉시 삭제합니다.
- **진단·처방 금지**: LLM은 환자가 표현한 수어를 한국어 문장으로 **재구성**하는 역할만
  수행하며, 진단이나 처방 등 의료적 판단을 생성하지 않습니다. 최종 판단은 항상 의료진이
  내립니다.

## 테스트 실행

```bash
./gradlew test
```

일부 테스트는 Testcontainers(Redis 컨테이너)를 사용하므로 로컬에 **Docker가 실행 중**이어야
합니다.

## 디렉터리 구조

```
TalkDoc_BE/
├── build.gradle.kts
├── docker-compose.yml
├── Dockerfile
├── .env.example
├── docs/
│   ├── ai-service-contract.md   # TalkDoc-VisionAI(Flask) 연동 계약
│   └── answer-versioning.md     # 질문 버전/답변 초안 필드 단위 계약
├── scripts/
│   ├── e2e.sh                   # curl 기반 E2E 스모크 테스트
│   └── ws-client.mjs            # WebSocket 이벤트 확인용 CLI
└── src/main/
    ├── resources/
    │   ├── application.yml            # 공통 기본 설정
    │   ├── application-local.yml      # 로컬 개발 프로필
    │   ├── application-gemini.yml     # Gemini 연동 프로필
    │   └── prompts/                   # LLM 프롬프트 템플릿
    └── java/com/talkdoc/backend/
        ├── ai/          # STT/LLM/TTS/수어인식 클라이언트 인터페이스 및 어댑터
        ├── answer/      # 확정된 문진 답변(Conversation) 도메인
        ├── auth/        # 세션 토큰 인증(SessionAuthInterceptor, RequireRole 등)
        ├── common/      # 공통 예외/에러 응답/ID 생성/Redis 키 규칙
        ├── config/      # Spring 설정(TalkDocProperties, Redis, WebConfig, OpenAPI)
        ├── question/    # 질문 의도(Intent) 및 대기 질문(PendingQuestion)
        ├── realtime/    # WebSocket 이벤트(EventType, SessionEvent, Publisher)
        ├── session/     # 세션 도메인 및 Redis 리포지토리
        └── sign/        # 인식된 수어(RecognizedSign) 도메인
```
