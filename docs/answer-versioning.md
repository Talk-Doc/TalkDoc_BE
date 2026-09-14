# 질문 버전 · 답변 초안 계약

이 문서는 질문 수정(버전 관리), 수어 인식 결과 저장, 답변 초안(preview)·확정(confirm)·
수정 제안(patch) 흐름의 필드 단위 계약을 정의한다. 팀 노션의 API 스펙을 그대로 반영하며,
레거시 요청 바디(`{labels}`, `{labels, answer?}`)는 프론트 호환을 위해 계속 지원한다.
전체 엔드포인트 목록/역할은 [README.md](../README.md#api-요약)의 API 요약 표를 참고한다.

## 1. `PATCH /api/sessions/{sessionId}/questions/{questionId}` (DOCTOR)

질문 텍스트를 수정하고 의도 분석을 다시 수행한다. `question_id`는 유지되며 `version`이
+1 된다. 이전 버전에서 만들어진 **미확정** 답변 초안은 무효화되지만(확정된 답변에는 영향
없음), 수어 인식 결과(recognition)는 그대로 유지된다.

요청:

```json
{ "text": "어디가 불편하세요?", "version": 1 }
```

응답 (200, `POST /question`과 동일한 형태 + `version`/`updated_at`):

```json
{
  "question_id": "q_123",
  "text": "어디가 불편하세요?",
  "intent": "SYMPTOM_LOCATION",
  "intents": ["SYMPTOM_LOCATION"],
  "candidates": ["배", "머리", "다리"],
  "supported": true,
  "asked_at": "2026-09-14T09:00:00Z",
  "version": 2,
  "updated_at": "2026-09-14T09:05:00Z"
}
```

WebSocket으로 `QUESTION_UPDATED`(payload = 위 응답과 동일한 PendingQuestion 형태)가
환자·의사 모두에게 전달된다.

에러:

| 상황 | code | HTTP |
|------|------|------|
| `questionId`가 현재 질문이 아님 | `QUESTION_NOT_FOUND` | 404 |
| 이미 확정된 답변이 있는 질문 | `QUESTION_ALREADY_ANSWERED` | 409 |
| `version`이 현재 질문 버전과 다름 | `VERSION_CONFLICT` | 409 |

## 2. `POST /question` / PendingQuestion 필드 추가

`POST /api/sessions/{sessionId}/question` 응답과 WebSocket `QUESTION_POSTED`의
PendingQuestion에 다음 필드가 추가된다.

| 필드 | 타입 | 설명 |
|------|------|------|
| `version` | number | 질문 버전. 등록 시 `1`부터 시작 |
| `updated_at` | string \| null | 마지막 수정 시각(ISO-8601). 수정 전에는 `null` |

## 3. `POST /sign` 응답 필드 추가

라벨이 인식된 수어는 세션에 `recognition_id`로 저장되어(나중에 preview에서 참조),
응답 최상위에 다음 필드가 추가된다.

| 필드 | 타입 | 설명 |
|------|------|------|
| `recognition_id` | string \| null | 저장된 인식 결과 id. 라벨이 없었거나(reject) 명시적 `intent`가 함께 전달되어 대기 질문 없이 처리된 경우 `null` |
| `question_version` | number | 인식 시점의 현재 질문 버전 |

## 4. `POST /answer/preview` (PATIENT) — 초안 저장

레거시 바디와 신규 바디 중 하나를 사용한다. 둘 다 오면 `recognition_ids`가 우선한다.

레거시:

```json
{ "labels": ["배", "아프다"] }
```

신규 (순서 있는 `recognition_ids` 배열):

```json
{ "question_id": "q_123", "question_version": 2, "recognition_ids": ["r_1", "r_2"] }
```

호출 시마다 답변 문장을 만들고 **초안**으로 저장한다(`answer_id`는 매 호출 새로 발급,
`version`은 항상 `1`).

응답 (200):

```json
{
  "question_id": "q_123",
  "question_version": 2,
  "labels": ["배", "아프다"],
  "answer": "배가 아파요.",
  "answer_id": "a_draft_1",
  "version": 1,
  "recognition_ids": ["r_1", "r_2"]
}
```

에러:

| 상황 | code | HTTP |
|------|------|------|
| `labels`도 `recognition_ids`도 없음 | `INVALID_REQUEST` | 400 |
| `recognition_ids`가 다른 질문의 인식 결과를 참조 | `INVALID_REQUEST` | 400 |
| 참조한 인식 결과가 거부(reject)된 라벨 | `INVALID_REQUEST` | 400 |
| `question_id`가 현재 질문이 아님 | `QUESTION_NOT_FOUND` | 404 |
| `recognition_id`를 찾을 수 없음 | `RECOGNITION_NOT_FOUND` | 404 |
| `question_version`이 현재 질문 버전과 다름 | `VERSION_CONFLICT` | 409 |

## 5. `POST /answer/confirm` (PATIENT) — 확정

레거시 바디는 그대로 동작한다(항상 201, 신규 필드만 추가):

```json
{ "labels": ["배", "아프다"], "answer": "배가 아파요." }
```

신규 바디는 초안을 확정한다:

```json
{ "answer_id": "a_draft_1", "version": 1, "answer": "배가 많이 아파요." }
```

- 최초 확정: 201 + Conversation.
- 같은 `answer_id`+`version`으로 반복 확정: 200 + **동일한** Conversation(중복 저장 안 함).
- `answer`를 함께 보내면 초안 텍스트 대신 그 값으로 확정한다(선택).

Conversation 응답에 추가된 필드:

```json
{
  "answer_id": "a_draft_1",
  "question_id": "q_123",
  "question": "어디가 불편하세요?",
  "question_version": 2,
  "intents": ["SYMPTOM_LOCATION"],
  "signs": ["배", "아프다"],
  "answer": "배가 많이 아파요.",
  "confirmed_at": "2026-09-14T09:06:00Z",
  "version": 1,
  "edited_by": null,
  "edited_at": null,
  "pending_edit": null
}
```

에러(신규 바디 경로에서만): `DRAFT_NOT_FOUND`(404, 초안 없음/이미 다른 방식으로 처리됨),
`DRAFT_INVALIDATED`(409, 질문이 수정/삭제되어 무효화된 초안), `VERSION_CONFLICT`(409).

## 6. `PATCH /answer/{answerId}` (DOCTOR | PATIENT)

```json
{ "answer": "배가 아프고 설사를 해요.", "version": 1 }
```

`version`은 선택이지만 지정을 권장한다(낙관적 락).

- **DOCTOR**가 호출하면 확정된 `answer`는 바뀌지 않고, `pending_edit`에 제안만 저장한다.
  WebSocket `ANSWER_EDIT_PROPOSED`(payload: Conversation)가 발행된다.

  ```json
  { "pending_edit": { "answer": "배가 아프고 설사를 해요.", "proposed_by": "DOCTOR", "proposed_at": "2026-09-14T09:07:00Z" } }
  ```

- **PATIENT**가 호출하면 텍스트가 즉시 반영된다: `answer` 갱신, `version` +1,
  `edited_by: "PATIENT"`, `edited_at` 갱신, `pending_edit`는 `null`로 초기화. WebSocket
  `ANSWER_UPDATED`(payload: Conversation)가 발행된다.

즉, 의사의 수정 제안은 환자가 같은(혹은 다른) 텍스트로 다시 `PATCH`해야 실제로 반영된다.

에러: `ANSWER_NOT_FOUND`(404), `VERSION_CONFLICT`(409, `version` 불일치).

## 7. `GET /api/sessions/{sessionId}` (DOCTOR | PATIENT)

- 공통: `current_question`에 `question_version` 추가.
- **PATIENT**로 조회하면 재연결 후 화면 복구를 위해 다음 필드가 추가된다(DOCTOR는 받지
  않음).

  | 필드 | 설명 |
  |------|------|
  | `drafts` | 현재 질문의 미확정 답변 초안 목록 |
  | `recognitions` | 현재 질문 버전의 수어 인식 결과 목록 |

## 8. Redis 키

| 키 | 타입 | 설명 |
|----|------|------|
| `session:{id}:recognitions` | list | 세션의 수어 인식 결과(`recognition_id` 포함) |
| `session:{id}:drafts` | hash | `answerId → JSON`(미확정 답변 초안) |

두 키 모두 세션과 동일한 TTL(2h)을 가지며, 세션 삭제 시 함께 제거된다. 영상/음성 원본은
여전히 저장하지 않으며, 두 키에는 라벨·텍스트 등 텍스트성 데이터만 담긴다.
