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
      │  TalkDoc_AI (별도 Python 서비스)      │
      │  수어 인식 (HTTP 위임)                │
      └───────────────────────────────────┘
```

- 저장소는 **Redis 하나만** 사용합니다. 세션 단위 임시 데이터를 TTL 2시간으로 보관하며,
  세션 종료 시 즉시 삭제합니다.
- STT/LLM/TTS는 Google Gemini API를 호출합니다 (`talkdoc.ai.provider=gemini`).
- 수어 인식은 별도 파이썬 서비스인 `TalkDoc_AI`에 HTTP로 위임합니다
  (`talkdoc.sign-ai.mode=http`). 계약은 [docs/ai-service-contract.md](docs/ai-service-contract.md)
  참고.
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
| `GEMINI_STT_MODEL` | `gemini-2.5-flash` | STT에 사용할 Gemini 모델 |
| `GEMINI_LLM_MODEL` | `gemini-2.5-flash` | 의도 분석/문장 재구성 등에 사용할 Gemini 모델 |
| `GEMINI_TTS_MODEL` | `gemini-2.5-flash-preview-tts` | TTS에 사용할 Gemini 모델 |
| `TALKDOC_SIGN_AI_MODE` | `mock` | 수어 인식 연동 방식. `mock` \| `http` |
| `TALKDOC_SIGN_AI_URL` | `http://localhost:8000` | `mode=http`일 때 TalkDoc_AI 서비스 base URL |
| `TALKDOC_WS_ORIGINS` | `*` | WebSocket 허용 Origin (콤마 구분, `*`는 전체 허용) |

그 외 `talkdoc.session.ttl`(2h), `talkdoc.sign.confidence-threshold`(0.75),
`talkdoc.ai.timeout`(15s), `talkdoc.sign-ai.timeout`(10s)은 환경 변수가 아닌
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
| POST | `/api/sessions/{sessionId}/question` | DOCTOR | 질문 등록 (multipart `audio` 또는 `text` 필드) | `question_id`, `text`, `intent`, `intents`, `candidates`, `supported`, `asked_at` |
| POST | `/api/sessions/{sessionId}/sign` | PATIENT | 수어 영상 업로드/인식 (multipart `video`, 선택 `intent`) | `question_id`, `intents`, `candidates`, `signs[]`(`label`,`confidence`,`accepted`), `all_accepted`, `accepted_labels` |
| POST | `/api/sessions/{sessionId}/answer/preview` | PATIENT | 인식된 라벨로 답변 문장 미리보기 (`{labels}`) | `question_id`, `labels`, `answer` |
| POST | `/api/sessions/{sessionId}/answer/confirm` | PATIENT | 답변 확정 (`{labels, answer?}`) | Conversation: `answer_id`, `question_id`, `question`, `intents`, `signs`, `answer`, `confirmed_at` |
| PATCH | `/api/sessions/{sessionId}/answer/{answerId}` | DOCTOR \| PATIENT | 확정된 답변 텍스트 수정 (`{answer}`) | Conversation |
| GET | `/api/sessions/{sessionId}` | DOCTOR | 세션 상세 조회 | `session_id`, `status`, `created_at`, `current_question`, `conversations[]` |
| POST | `/api/sessions/{sessionId}/summary` | DOCTOR | 진료 요약 생성 | `summary`, `conversation_count`, `generated_at` |
| GET | `/api/sessions/{sessionId}/answer/{answerId}/tts` | DOCTOR (선택) | 답변 음성 합성 | `audio/wav` 스트림 |
| DELETE | `/api/sessions/{sessionId}` | DOCTOR | 세션 종료/삭제 | 204 No Content |
| GET | `/ws/sessions/{sessionId}?token=` | (토큰 필요) | WebSocket 연결 | 실시간 이벤트 스트림 |

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
| `ANSWER_CONFIRMED` | 환자가 답변을 확정함 | Conversation (의사·환자 모두에게 전달) |
| `ANSWER_UPDATED` | 확정된 답변 텍스트가 수정됨 | Conversation |
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
| `NO_PENDING_QUESTION` | 409 | 현재 답변 대기 중인 질문이 없습니다. |
| `SESSION_CLOSED` | 409 | 이미 종료된 세션입니다. |
| `FILE_TOO_LARGE` | 413 | 업로드 파일이 너무 큽니다. |
| `UNSUPPORTED_MEDIA` | 415 | 지원하지 않는 파일 형식입니다. |
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

스크립트는 다음을 순서대로 수행합니다: 세션 생성 → 의사 질문 등록(`text` 필드) →
환자 수어 업로드(더미 영상, Mock 어댑터로 처리) → 답변 미리보기 → 답변 확정 →
세션 조회 → 진료 요약 생성 → 세션 삭제. 각 단계 응답을 출력하고, HTTP 에러 발생 시
즉시 비정상 종료합니다.

## 개인정보·안전 원칙

- **영상/음성 미보관**: 업로드된 수어 영상과 음성 파일은 인식/변환 직후 즉시 폐기하며
  디스크나 DB에 저장하지 않습니다. Redis에도 원본 미디어는 저장하지 않고, 세션의 텍스트성
  임시 데이터(질문/답변/토큰 등)만 TTL 2시간으로 보관합니다.
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
│   └── ai-service-contract.md   # TalkDoc_AI(Python) 연동 계약
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
