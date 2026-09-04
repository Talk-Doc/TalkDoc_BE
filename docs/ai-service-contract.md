# TalkDoc_AI 연동 계약 (수어 인식 서비스)

이 문서는 백엔드(`TalkDoc_BE`, 본 저장소)가 수어 인식을 위임하는 별도 파이썬 서비스
`TalkDoc_AI`가 구현해야 하는 HTTP 계약을 정의한다. 백엔드 쪽 구현은
`src/main/java/com/talkdoc/backend/ai/SignRecognitionClient.java`와
`talkdoc.sign-ai.*` 설정(`application.yml`)을 참고한다.

- 연동 모드: `talkdoc.sign-ai.mode = http`, 대상 URL: `talkdoc.sign-ai.base-url`
  (env `TALKDOC_SIGN_AI_MODE`, `TALKDOC_SIGN_AI_URL`)
- 타임아웃 예산: **10초** (`talkdoc.sign-ai.timeout: 10s`). 이 시간 내에 응답하지 못하면
  백엔드는 요청을 실패로 간주하고 `ErrorCode.SIGN_AI_FAILED` (502)를 반환한다.

## 1. `POST /recognize`

`multipart/form-data` 요청. 파트 구성:

| 파트 이름     | 타입                  | 설명 |
|------------|---------------------|------|
| `video`    | 파일 (`video/webm` 또는 `video/mp4`) | 환자가 촬영한 수어 영상 |
| `intent`   | 텍스트 (콤마 구분)    | 현재 질문의 의도. `BODY_LOCATION`, `SYMPTOM`, `HISTORY_STATE` 중 0개 이상 |
| `candidates` | 텍스트 (콤마 구분)  | 모델이 결과로 반환할 수 있는 한국어 수어 라벨 후보 목록 (아래 어휘 참고) |

### 요청 예시 (curl)

```bash
curl -X POST http://localhost:8000/recognize \
  -F "video=@sign.webm;type=video/webm" \
  -F "intent=BODY_LOCATION,SYMPTOM" \
  -F "candidates=머리,목,가슴,배,허리,팔,다리,아프다,어지럽다,기침,구토,설사,숨차다,답답하다,붓다"
```

### 응답 200 OK

```json
{
  "signs": [
    { "label": "배", "confidence": 0.94 },
    { "label": "아프다", "confidence": 0.91 }
  ],
  "segments": 2
}
```

- `signs`: 수어가 표현된 순서(signing order)대로, **감지된 세그먼트마다 하나씩** 반환한다.
- `label`은 반드시 요청의 `candidates`에 포함된 값이어야 한다 (그 외 값은 백엔드가 무시/거부할 수 있음).
- `confidence`는 `0.0` ~ `1.0` 사이의 실수.
- 아무 수어도 감지되지 않으면 `signs: []`, `segments: 0`을 반환한다 (에러 아님).

### 세그먼트 분리 기준

수어 사이에 **약 0.8~1초 정도의 정지(pause)** 가 있으면 별도 세그먼트(별도 수어)로 간주하고
분리해서 반환한다. 즉 한 번의 업로드 영상에 여러 개의 수어가 이어서 촬영될 수 있으며,
`signs` 배열의 각 원소가 그 안의 개별 수어 하나에 대응해야 한다.

### 에러 응답

| 상황 | HTTP 상태 |
|------|-----------|
| 요청 형식 오류 (필수 파트 누락, `candidates` 비어있음 등) | 400 |
| 영상 파일을 디코딩/판독할 수 없음 | 422 |
| 그 외 서버 내부 오류 | 500 |

에러 본문 형식:

```json
{ "code": "INVALID_VIDEO", "message": "영상을 읽을 수 없습니다." }
```

## 2. `GET /health`

헬스체크. 정상이면 본문 없이(또는 임의 본문) **200 OK**만 반환하면 된다.

```bash
curl http://localhost:8000/health
```

## 3. 수어 어휘 (20개, 의도별)

`Intent.java`에 정의된 전체 지원 어휘. `candidates` 파트에는 이 중 현재 질문의 `intent`에
해당하는 라벨들의 합집합이 전달된다.

| 의도 (Intent) | 라벨 |
|---------------|------|
| `BODY_LOCATION` (신체 부위, 7개) | 머리, 목, 가슴, 배, 허리, 팔, 다리 |
| `SYMPTOM` (증상, 8개) | 아프다, 어지럽다, 기침, 구토, 설사, 숨차다, 답답하다, 붓다 |
| `HISTORY_STATE` (병력/상태, 5개) | 약, 알레르기, 감기, 임신, 당뇨병 |

`OTHER` 의도는 어휘가 없으며(지원 대상 외 질문), 이 경우 `candidates`가 비어 전달될 수 있다.

## 4. 개인정보·안전 원칙

- 백엔드는 업로드된 영상을 **디스크나 DB에 저장하지 않는다** — `SignRecognitionClient.recognize()`
  호출 직후 바이트 배열은 버려진다 (세션 데이터는 Redis에 TTL 2시간으로만 임시 저장되며,
  영상/음성 원본 자체는 Redis에도 저장하지 않는다).
- TalkDoc_AI 서비스도 동일한 원칙을 따라야 한다: 요청으로 받은 영상을 **영구 저장하지 않고**,
  추론에 필요한 동안만 메모리/임시 파일로 보관한 뒤 즉시 폐기해야 한다.
- 응답에는 인식된 라벨과 신뢰도만 포함하며, 영상 자체나 프레임 이미지를 되돌려주지 않는다.
