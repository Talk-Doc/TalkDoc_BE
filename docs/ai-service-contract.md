# TalkDoc-VisionAI 연동 계약 (수어 인식 서비스)

이 문서는 백엔드(`TalkDoc_BE`, 본 저장소)가 수어 인식을 위임하는 팀의 실제 Vision AI
서비스 `Talk-Doc/TalkDoc-VisionAI`(Flask, `python app.py`, 5001 포트)가 구현하는 HTTP
계약을 정의한다. 로컬에서는 본 저장소와 형제 디렉터리인 `../TalkDoc-VisionAI`에 위치한다.
백엔드 쪽 구현은
`src/main/java/com/talkdoc/backend/ai/signai/HttpSignRecognitionClient.java`와
`talkdoc.sign-ai.*` 설정(`application.yml`)을 참고한다.

> 이전에 사용하던 임시 FastAPI 스텁 `TalkDoc_AI`(8000 포트, `POST /recognize`, 영상 1개당
> 다중 수어 인식)는 더 이상 사용하지 않는다(archived). 이제 백엔드는 영상 1개당 수어
> 단어 1개를 인식하는 `TalkDoc-VisionAI`의 `POST /predict`를 호출한다.

- 연동 모드: `talkdoc.sign-ai.mode`(env `TALKDOC_SIGN_AI_MODE`, `mock` | `http`), 대상 URL:
  `talkdoc.sign-ai.base-url`(env `TALKDOC_SIGN_AI_URL`, 기본값 `http://localhost:5001`)
- 타임아웃 예산: 연결 5초 / 읽기 60초 (`talkdoc.sign-ai.connect-timeout`,
  `talkdoc.sign-ai.read-timeout`). 이 시간 내에 응답하지 못하면 백엔드는 요청을 실패로
  간주하고 `ErrorCode.SIGN_AI_FAILED`(502)를 반환한다.
- 인증(선택): 운영 환경에서만 서비스 토큰 env `TALKDOC_SIGN_AI_TOKEN`을 설정하면 백엔드가
  `Authorization: Bearer {토큰}` 헤더로 함께 보낸다. VisionAI 쪽 `VISION_API_TOKEN`과 같은
  값이어야 한다.
- 재시도: `503 BUSY` 응답을 받으면 `Retry-After` 헤더에 명시된 초(기본 2초)만큼 대기한 뒤
  재시도하며, 최대 `talkdoc.sign-ai.max-retries`(기본 2)회까지 시도한다. 그래도 실패하면
  `SIGN_AI_FAILED`(502)를 반환한다.
- `TalkDoc-VisionAI`에는 `GET /health`가 **없다**. 백엔드는 헬스체크 없이 바로 `/predict`를
  호출한다.

## 1. `POST /predict`

`multipart/form-data` 요청. 파트 구성:

| 파트 이름   | 타입              | 필수 | 설명 |
|----------|-------------------|------|------|
| `video`  | 파일 (`video/webm` 또는 `video/mp4`) | 예 | 환자가 촬영한 수어 영상. WebM(VP8/VP9) 또는 MP4(H.264). 파트 Content-Type을 `video/webm` 또는 `video/mp4`로 지정한다. |
| `duration` | 숫자 (초) | 아니오 | 카운트다운을 제외한 실제 촬영 시간(초). `0 < duration <= 20`. |

헤더:

| 헤더 | 필수 | 설명 |
|------|------|------|
| `X-Request-ID` | 예 | 백엔드가 호출마다 새로 생성하는 UUID. 응답의 `request_id`로 그대로 돌아온다. |
| `Authorization: Bearer {서비스 토큰}` | 아니오 | 운영 환경에서만 전송(`TALKDOC_SIGN_AI_TOKEN` 설정 시) |

### 요청 예시 (curl)

```bash
curl -X POST http://localhost:5001/predict \
  -H "X-Request-ID: 93d267c0-c21b-462c-b09b-fb3abbff534c" \
  -F "video=@sign.webm;type=video/webm" \
  -F "duration=3.2"
```

### 제약 사항

- 전체 요청 크기: 40 MiB 이하
- 영상 한 변: 1280px 이하
- 길이: 20초 이하
- 디코딩된 프레임 수: 2~1200 프레임
- 영상 1개당 수어 단어 1개, 동작의 시작과 끝이 모두 포함되어야 한다
- 서버는 영상을 좌우 반전(mirror)하지 않는다 — 촬영 시 좌우가 맞게 저장된 영상을 그대로
  보낸다

### 응답 200 OK

필드:

| 필드 | 타입 | 설명 |
|------|------|------|
| `request_id` | String | 요청 헤더 `X-Request-ID`를 그대로 반환 |
| `model_version` | String | 예: `"bigru-v2-seed17"` |
| `label` | String \| null | 인식된 수어 단어. 인식 실패 시 `null` |
| `confidence` | Number(0~1) \| null | 신뢰도 |
| `accepted` | Boolean \| null | VisionAI가 자체 임계값으로 판단한 채택 여부. `null`이면 임계값이 설정되지 않아 백엔드가 직접 판단해야 함 |
| `reason` | null \| `LOW_CONFIDENCE` \| `INSUFFICIENT_LANDMARKS` \| `THRESHOLD_NOT_CONFIGURED` | `accepted`가 `true`가 아닐 때의 사유 |
| `processing_ms` | Number | 추론에 걸린 시간(ms) |

**성공 예시**

```json
{
  "request_id": "93d267c0-c21b-462c-b09b-fb3abbff534c",
  "model_version": "bigru-v2-seed17",
  "label": "배",
  "confidence": 0.94,
  "accepted": true,
  "reason": null,
  "processing_ms": 850
}
```

**임계값 미설정** — VisionAI 쪽에 확신 임계값이 설정되지 않아 채택 여부를 판단하지 못한
경우. 이때는 백엔드가 자체 설정값 `talkdoc.sign.confidence-threshold`(기본 0.75)를
`confidence`에 적용해 `accepted`를 직접 결정한다.

```json
{
  "request_id": "93d267c0-c21b-462c-b09b-fb3abbff534c",
  "model_version": "bigru-v2-seed17",
  "label": "배",
  "confidence": 0.94,
  "accepted": null,
  "reason": "THRESHOLD_NOT_CONFIGURED",
  "processing_ms": 850
}
```

**신뢰도 낮음**

```json
{
  "request_id": "93d267c0-c21b-462c-b09b-fb3abbff534c",
  "model_version": "bigru-v2-seed17",
  "label": "배",
  "confidence": 0.52,
  "accepted": false,
  "reason": "LOW_CONFIDENCE",
  "processing_ms": 850
}
```

**랜드마크 부족** — 양손 동시 결측률이 30%를 초과한 경우. 한 손만으로 표현하는 수어는
거부 대상이 아니다.

```json
{
  "request_id": "93d267c0-c21b-462c-b09b-fb3abbff534c",
  "model_version": "bigru-v2-seed17",
  "label": null,
  "confidence": null,
  "accepted": false,
  "reason": "INSUFFICIENT_LANDMARKS",
  "processing_ms": 850
}
```

### `reason`별 화면 처리

| reason | 화면 처리 |
|--------|-----------|
| `LOW_CONFIDENCE` | 재촬영 안내 |
| `INSUFFICIENT_LANDMARKS` | 손과 상체가 화면에 보이도록 재촬영 안내 |
| `THRESHOLD_NOT_CONFIGURED` | 예측된 단어를 환자가 직접 확인(자동 채택하지 않음) |

### 에러 응답

에러 본문 형식(공통):

```json
{
  "request_id": "93d267c0-c21b-462c-b09b-fb3abbff534c",
  "error": {
    "code": "VIDEO_DECODE_FAILED",
    "message": "영상을 읽을 수 없습니다. 다시 촬영해주세요."
  }
}
```

| HTTP 상태 | code | 상황 |
|-----------|------|------|
| 200 | - | 정상 (인식 실패/거부도 200으로 응답, 위 `reason` 참고) |
| 400 | `INVALID_REQUEST` | 필수 파트 누락, `duration` 형식 오류 등 |
| 401 | `UNAUTHORIZED` | 서비스 토큰 누락/불일치 |
| 413 | `PAYLOAD_TOO_LARGE` | 요청 크기 40MiB 초과 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | 지원하지 않는 영상 형식 |
| 422 | `VIDEO_DECODE_FAILED` | 영상 파일을 디코딩/판독할 수 없음 |
| 422 | `VIDEO_LIMIT_EXCEEDED` | 해상도/길이/프레임 수 제약 초과 |
| 422 | `VIDEO_TIMING_UNAVAILABLE` | 수어 동작의 시작/끝 타이밍을 산출할 수 없음 |
| 503 | `BUSY` | 한 번에 영상 1개만 처리 가능, 현재 처리 중 (`Retry-After: 2`) |
| 503 | `NOT_READY` | 서비스가 아직 준비되지 않음(모델 로딩 중 등) |
| 500 | `INFERENCE_FAILED` | 그 외 추론 서버 내부 오류 |

## 2. 백엔드 매핑 규칙 (AI 상태 → 백엔드 ErrorCode/HTTP)

| VisionAI 상태 | 백엔드 처리 |
|----------------|-------------|
| 200 | 위 `accepted`/`reason` 규칙에 따라 처리 (에러 아님) |
| 400 `INVALID_REQUEST` | `INVALID_REQUEST` 400 |
| 401 `UNAUTHORIZED` | `SIGN_AI_FAILED` 502 |
| 413 `PAYLOAD_TOO_LARGE` | `FILE_TOO_LARGE` 413 |
| 415 `UNSUPPORTED_MEDIA_TYPE` | `UNSUPPORTED_MEDIA` 415 |
| 422 (`VIDEO_DECODE_FAILED` / `VIDEO_LIMIT_EXCEEDED` / `VIDEO_TIMING_UNAVAILABLE`) | `SIGN_VIDEO_REJECTED` 422 (신규 에러 코드). 메시지는 "영상을 인식할 수 없습니다. 다시 촬영해주세요." 또는 VisionAI가 준 메시지를 그대로 사용 |
| 503 `BUSY` | `Retry-After`만큼 대기 후 재시도(최대 `talkdoc.sign-ai.max-retries`회). 그래도 실패하면 `SIGN_AI_FAILED` 502 |
| 503 `NOT_READY` / 500 `INFERENCE_FAILED` / 타임아웃 / 연결 실패 | `SIGN_AI_FAILED` 502 |

## 3. 백엔드 처리 규칙

- `session_id`, `question_id`는 백엔드가 관리한다. VisionAI는 세션/질문 개념을 모른다.
- `accepted=true`인 경우에만 자동으로 답변에 채택(pass)된다.
- `accepted=false`인 라벨은 절대 답변에 누적되지 않는다.
- `accepted=null`(`THRESHOLD_NOT_CONFIGURED`)인 경우 환자가 직접 확인 후 채택 여부를
  결정한다.
- `accepted=true`로 자동 채택된 단어도 최종적으로는 환자가 확인(confirm)한 뒤에야
  답변으로 누적된다 — VisionAI의 채택 판정이 확정(confirm)을 대신하지 않는다.
- "전달하기"(답변 확정) 전까지는 어떤 인식 결과도 의사에게 전달되지 않는다.
- 재시도로 인한 중복 인식 결과, 그리고 이미 넘어간 질문에 대한 지연 응답(late result)은
  백엔드가 `question_id` 기준으로 걸러내 중복 반영을 방지한다.

## 4. 개인정보·안전 원칙

- 백엔드는 업로드된 영상을 **디스크나 DB에 저장하지 않는다** —
  `SignRecognitionClient` 호출 직후 바이트 배열은 버려진다 (세션 데이터는 Redis에 TTL
  2시간으로만 임시 저장되며, 영상/음성 원본 자체는 Redis에도 저장하지 않는다).
- `TalkDoc-VisionAI`도 동일한 원칙을 따른다: 요청마다 받은 영상은 추론에 필요한 동안만
  임시 파일로 보관한 뒤, 요청 처리가 끝나면 즉시 삭제한다(영구 저장하지 않음).
- 응답에는 인식된 라벨(`label`)과 신뢰도(`confidence`)만 포함하며, 영상 자체나 프레임
  이미지를 되돌려주지 않는다.

## 5. 수어 어휘 (20개, 의도별)

`Intent.java`에 정의된 전체 지원 어휘는 아래 20개이다. 다만 **현재 배포된 모델은 이 중
15개 클래스만 학습되어 있다** — 가슴, 허리, 기침, 구토, 알레르기는 아직 모델에 포함되지
않았다.

| 의도 (Intent) | 라벨 |
|---------------|------|
| `BODY_LOCATION` (신체 부위, 7개) | 머리, 목, 가슴, 배, 허리, 팔, 다리 |
| `SYMPTOM` (증상, 8개) | 아프다, 어지럽다, 기침, 구토, 설사, 숨차다, 답답하다, 붓다 |
| `HISTORY_STATE` (병력/상태, 5개) | 약, 알레르기, 감기, 임신, 당뇨병 |

현재 모델이 인식 가능한 15개 클래스: 감기, 다리, 답답하다, 당뇨병, 머리, 목, 배, 붓다,
설사, 숨차다, 아프다, 약, 어지럽다, 임신, 팔.

`OTHER` 의도는 어휘가 없으며(지원 대상 외 질문), 이 경우에도 `TalkDoc-VisionAI`는
영상 1개에 대해 라벨 1개만 반환한다(후보 목록을 별도로 전달하지 않는다).
