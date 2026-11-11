# Speechmatics 실시간 STT 분석 노트

- 출처: https://docs.speechmatics.com/speech-to-text/realtime/quickstart (2026-11-08 확인)
- 함께 확인한 문서:
  - 입력: https://docs.speechmatics.com/speech-to-text/realtime/input
  - 출력: https://docs.speechmatics.com/speech-to-text/realtime/output
  - 프로토콜 레퍼런스: https://docs.speechmatics.com/api-ref/realtime-transcription-websocket
  - 인증: https://docs.speechmatics.com/get-started/authentication
- 용도: Pleos 프로젝트(Android/Kotlin 앱 + `claude/sori-node 2` Node 프로토타입)에서 실시간 자막 기능을 만들 때 참고합니다.

---

## 1. 한눈에 보기

```
[마이크] → PCM 16bit mono → (WebSocket 바이너리 프레임) → wss://<region>.rt.speechmatics.com/v2
                                                              │
   StartRecognition(JSON) ─────────────────────────────────▶ │
   ◀──────────────────────────────── RecognitionStarted       │
   AddAudio(바이너리) × N ────────────────────────────────▶   │
   ◀──── AudioAdded / AddPartialTranscript / AddTranscript    │
   EndOfStream(JSON) ──────────────────────────────────────▶  │
   ◀──────────────────────────────────── EndOfTranscript      │
```

- 전송 방식은 WebSocket 하나입니다. 텍스트 프레임은 JSON 제어 메시지이고, 바이너리 프레임은 오디오입니다.
- 공식 SDK는 Python(`speechmatics-rt`)과 JS(`@speechmatics/real-time-client`, `@speechmatics/auth`)만 있습니다. **Android/Kotlin용 SDK는 없으므로** OkHttp WebSocket으로 프로토콜을 직접 구현합니다(8장 참고).

## 2. 엔드포인트

| 용도 | URL |
|---|---|
| 기본(가까운 리전 자동) | `wss://global.rt.speechmatics.com/v2/` |
| EU1 | `wss://eu.rt.speechmatics.com/v2/` |
| US1 | `wss://us.rt.speechmatics.com/v2/` |
| AU1 | `wss://au.rt.speechmatics.com/v2/` |

- 데이터 저장 위치나 규정 준수가 필요하면 리전 엔드포인트를 지정합니다. `global`은 어느 리전으로든 연결될 수 있습니다.
- 앱 사용량 추적: Support에 요청해 활성화한 뒤 URL에 `?sm-app=YourAppID`를 붙입니다.
- Agent STT는 같은 호스트의 `/v2/agent` 경로를 씁니다. 이 노트에서는 다루지 않습니다.

## 3. 인증

### 3-1. API 키 (서버 측)
- 발급: https://portal.speechmatics.com/settings/api-keys
- WebSocket 핸드셰이크에 `Authorization: Bearer <API_KEY>` 헤더를 붙입니다.
- 앱에 API 키를 직접 넣으면 유출됩니다. Android 앱에서는 아래 임시 키 방식을 쓰는 것이 안전합니다.

### 3-2. 임시 키 (JWT, 브라우저·모바일용)
서버가 API 키로 임시 키를 발급하고, 클라이언트는 임시 키만 받아서 연결합니다.

```bash
curl -L -X POST "https://mp.speechmatics.com/v1/jwts?type=rt" \
     -H "Content-Type: application/json" \
     -H "Authorization: Bearer $API_KEY" \
     -d '{"ttl": 60 }'
# 응답: {"key_value": "eyJhbG..."}
```

- `type=rt`는 실시간용입니다. `ttl`은 60~86400초입니다.
- 실시간용 임시 키는 리전에 묶이지 않습니다.
- 연결 방법:
  - 쿼리 파라미터: `wss://eu.rt.speechmatics.com/v2?jwt=<TEMP_KEY>` (브라우저는 헤더를 못 붙이므로 이 방식)
  - Android(OkHttp)는 헤더를 붙일 수 있으므로 `Authorization: Bearer <TEMP_KEY>`도 가능합니다.
- TTL은 **새 연결을 시작할 수 있는 시간**입니다. 이미 연결된 세션이 TTL이 지나 끊기지는 않습니다.
- JS SDK 헬퍼: `createSpeechmaticsJWT({ type: "rt", apiKey, ttl: 60 })`. 이 함수는 API 키가 필요하므로 서버에서만 호출합니다.
- 구 엔드포인트 `POST /v1/api_keys`는 **deprecated**이며 `/v1/jwts`로 대체되었습니다.

> ⚠️ 기존 프로토타입 `claude/sori-node 2/server.js` 8~9행은 `/v1/api-keys?type=rt`와 `/v1/api_keys?type=rt`를 씁니다. 2026-11-08에 실제 키로 확인한 결과, `/v1/jwts?type=rt`가 **201**과 `{"key_value": ...}`를 돌려줍니다. 성공 판정은 200만이 아니라 2xx 전체로 해야 합니다.

## 4. 세션 메시지 흐름

1. 클라이언트가 WebSocket을 엽니다(`GET wss://…/v2/`).
2. 서버가 바로 `Info` 메시지를 보냅니다. 종류는 `recognition_quality`(`quality`: `telephony`/`broadcast`)와 `concurrent_session_usage`(`usage`, `quota`, `region` 등)입니다.
3. 클라이언트가 `StartRecognition`을 보냅니다.
4. 서버가 `RecognitionStarted`로 응답합니다. **이 응답을 받은 뒤에만 오디오를 보냅니다.**
5. 오디오를 보내는 동안 반복합니다.
   - 클라이언트: 바이너리 프레임으로 오디오를 보냅니다. 이것이 `AddAudio`입니다.
   - 서버: `AudioAdded { seq_no }`로 응답합니다. `seq_no`는 1부터 증가합니다.
   - 서버: `AddPartialTranscript`(partials를 켰을 때)와 `AddTranscript`(확정 결과)를 보냅니다.
6. 클라이언트가 `{"message":"EndOfStream","last_seq_no":<보낸 오디오 프레임 수>}`를 보냅니다.
7. 서버가 남은 `AddTranscript`를 모두 보낸 뒤 `EndOfTranscript`를 보냅니다. 그다음 연결을 닫습니다.
8. `Info`, `Warning`, `Error`는 언제든 올 수 있습니다. **`Error`를 받으면 세션이 끝나고 연결이 닫힙니다.**

기타 클라이언트 메시지:
- `ForceEndOfUtterance` (선택: `timestamp`, `channel`): 지금까지의 내용을 즉시 확정(Final)하고 `EndOfUtterance`를 받습니다.
- `SetRecognitionConfig`: 세션 중에 `max_delay`, `max_delay_mode`, `audio_filtering_config`, `enable_partials`, `conversation_config`를 바꿉니다. `language`는 같은 값만 허용됩니다. 그 밖의 필드를 바꾸면 에러입니다.
- `GetSpeakers` (`final` 플래그): `SpeakersResult`를 받습니다.
- `AddChannelAudio`, `EndOfChannel`: 멀티채널 전용입니다.

## 5. StartRecognition 설정

```json
{
  "message": "StartRecognition",
  "audio_format": {
    "type": "raw",
    "encoding": "pcm_s16le",
    "sample_rate": 16000
  },
  "transcription_config": {
    "language": "ko",
    "model": "enhanced",
    "max_delay": 2.0,
    "enable_partials": true,
    "diarization": "speaker"
  }
}
```

### audio_format
- `type: "raw"` (권장): `encoding`(`pcm_s16le` / `pcm_f32le` / `mulaw`)과 `sample_rate`(Hz, 정수)가 필수입니다. 채널은 mono입니다.
- `type: "file"`: wav, mp3, aac, ogg, mpeg, amr, m4a, mp4, flac 파일을 헤더까지 포함해 적당한 크기로 잘라 보냅니다.
- **실제로 보내는 오디오의 샘플레이트·인코딩은 선언한 값과 반드시 같아야 합니다.** 퀵스타트 예제에서 Python은 16000Hz, Node(sox)는 44100Hz를 씁니다.
- 브라우저용 마이크 캡처 패키지로 `@speechmatics/browser-audio-input`이 있습니다.

### transcription_config 주요 필드

| 필드 | 값 / 기본값 | 설명 |
|---|---|---|
| `language` | 필수 (예: `en`, `ko`) | `melia-1`, `oak-1` 모델은 `"multi"`를 쓰고 `language_hints`로 언어를 지정합니다. |
| `model` | `standard`(기본) / `enhanced` / `melia-1` / `oak-1` | `operating_point`는 deprecated이므로 `model`을 씁니다. |
| `max_delay` | 0.7~4초, 기본 4 | 단어를 말한 뒤 확정 결과가 나오기까지의 최대 지연입니다. |
| `max_delay_mode` | `flexible`(기본) / `fixed` | `flexible`은 숫자·날짜 같은 엔티티를 끝까지 기다려 포맷 품질을 높입니다. `fixed`는 지연이 짧은 대신 정확도가 조금 떨어집니다. |
| `enable_partials` | 기본 false | `AddPartialTranscript`를 받습니다. |
| `enable_entities` | 기본 true | |
| `diarization` | `none`(기본) / `speaker` / `channel` / `channel_and_speaker` | 화자 구분 방식입니다. |
| `speaker_diarization_config` | `max_speakers`(≥2), `prefer_current_speaker`, `speaker_sensitivity`(0~1), `get_speakers`, `speakers`(≤50) | |
| `additional_vocab` | 최대 20000개, 문자열 또는 `{content, sounds_like[]}` | 사용자 사전입니다. 목록이 크면 세션 시작이 최대 15초 걸립니다. |
| `domain` | 예: `finance`, `medical` | 특화 모델입니다. |
| `output_locale` | 문자열 | 출력 포맷 로케일입니다. |
| `audio_filtering_config` | `volume_threshold`(0~100) | 작은 소리(배경 소음)를 걸러냅니다. 차량 환경에서 쓸모가 있습니다. |
| `transcript_filtering_config` | `remove_disfluencies`, `replacements[{from,to}]` | 군말 제거와 치환입니다. |
| `punctuation_overrides` | `permitted_marks`, `sensitivity`(0~1, 기본 0.5) | |
| `conversation_config` | `end_of_utterance_silence_trigger`(0~2초, 기본 0=끔) | 침묵 후 `EndOfUtterance`를 보냅니다. enhanced/standard 모델만 지원합니다. |

기타: `translation_config`(`target_languages`, `enable_partials`), `audio_events_config`(`types`).

### max_delay 선택 가이드
- 0.7~1.5초: 음성 에이전트용입니다. 배치 대비 상대 정확도 손실은 5% 미만입니다.
- **2.0초: 대부분의 경우(자막, 콜센터)에 권장합니다.** 손실은 약 1%입니다.
- 4.0초: 정확도가 최우선일 때 씁니다(배치와 동일).

### Final과 Partial 비교

| 종류 | 지연 | 안정성 | 용도 |
|---|---|---|---|
| Final (`AddTranscript`) | 약 0.7~2초 | 확정되며 다시 바뀌지 않습니다 | 정확한 기록, 자막 |
| Partial (`AddPartialTranscript`) | 500ms 미만 | 바뀔 수 있습니다 | 라이브 캡션, 음성 UI |

- Partial은 `max_delay`의 영향을 받지 않습니다. 정확도는 Final보다 10~25% 낮고, `confidence` 값은 의미가 없습니다.
- UI 패턴: Partial은 같은 자리에서 덮어쓰고, Final이 오면 확정 텍스트에 붙인 뒤 Partial 영역을 비웁니다.

## 6. 서버 메시지 구조

### AddTranscript / AddPartialTranscript (구조 동일)
```jsonc
{
  "message": "AddTranscript",          // 또는 "AddPartialTranscript"
  "format": "2.9",                     // 선택
  "metadata": {
    "start_time": 1.23,
    "end_time": 2.34,
    "transcript": "안녕하세요. "         // 화면에 바로 표시할 텍스트
  },
  "results": [
    {
      "type": "word",                  // word | punctuation | entity
      "start_time": 1.23,
      "end_time": 1.80,
      "attaches_to": "previous",       // 선택: next | previous | none | both (주로 문장부호)
      "is_eos": false,                 // 선택: 문장 끝
      "alternatives": [
        {
          "content": "안녕하세요",
          "confidence": 0.98,
          "language": "ko",            // 선택
          "speaker": "S1",             // diarization을 켰을 때. 모르면 "UU"
          "tags": [],                  // 선택: disfluency, profanity
          "display": { "direction": "ltr" }
        }
      ]
    }
  ],
  "channel": "...",                    // 선택
  "forced": false                      // ForceEndOfUtterance로 확정된 경우 true
}
```
- 간단히 쓸 때는 `metadata.transcript`만 표시하면 됩니다.
- 화자별 자막처럼 단어 단위 정보가 필요하면 `results[].alternatives[0]`의 `content`와 `speaker`를 씁니다. 문장부호는 `attaches_to`를 보고 앞 단어에 붙입니다.
- 위 JSON 값은 필드 목록을 바탕으로 만든 예시입니다. 공식 문서에는 전체 예시가 없습니다.

### 기타 서버 메시지
- `RecognitionStarted`: `id`, `orchestrator_version`, `language_pack_info`(`language_description`, `word_delimiter`, `writing_direction`, `itn`, `adapted`), `channel_ids`
- `AudioAdded`: `seq_no`
- `EndOfUtterance`: `metadata{start_time,end_time}`, `channel`, `forced`
- `EndOfTranscript`: 세션 종료 신호입니다.
- `LanguageInfo` (melia-1/oak-1 전용): 새 언어가 처음 나오기 직전에 `language`, `word_delimiter`, `writing_direction`, `partial`을 보냅니다.
- `Info`: `type`, `reason`, `quality`, `usage`, `quota`, `last_updated`, `region` 등
- `Warning`: `type`, `reason`, `duration_limit` 등
  - 종류: `duration_limit_exceeded`, `idle_timeout`, `session_timeout`, `add_audio_after_eos`, `unsupported_translation_pair`, `empty_translation_target_list`, `speaker_id`
  - idle 경고는 타임아웃 15·10·5분 전에, session 경고는 45·30·15분 전에 옵니다.
- `Error`: `type`, `reason`, `code`, `seq_no`
  - 종류: `invalid_message`, `invalid_model`, `invalid_language`, `invalid_config`, `invalid_audio_type`, `invalid_output_format`, `not_authorised`, `not_allowed`, `job_error`, `protocol_error`, `quota_exceeded`, `timelimit_exceeded`, `idle_timeout`, `session_timeout`, `unknown_error`
- `SpeakersResult`: `speakers[]{label, speaker_identifiers}`

## 7. 에러·제한·재시도

| WebSocket 종료 코드 | 의미 | 대응 |
|---|---|---|
| 1003 | protocol_error | 메시지 형식이나 순서를 점검합니다. |
| 1008 | policy_violation (idle/session timeout) | 새 세션을 엽니다. |
| 1011 | internal_error | 5~10초 뒤 재시도합니다. |
| 4001 | not_authorised | 키나 토큰을 확인하고 다시 발급합니다. |
| 4003 | not_allowed | 계정 권한을 확인합니다. |
| 4004 | invalid_model | 모델이나 언어 조합을 확인합니다. |
| 4005 | quota_exceeded | 동시 세션 한도입니다. 5~10초 뒤 재시도합니다. |
| 4006 | timelimit_exceeded | 사용 한도를 확인합니다. |
| 4013 | job_error | 5~10초 뒤 재시도합니다. |

- 핸드셰이크 HTTP 에러: 400(요청 형식 오류), 401(키나 임시 키가 잘못됨), 405(GET이 아님)
- 오디오 없이 1시간이 지나면 idle timeout입니다. 세션은 최대 48시간입니다.
- `EndOfStream` 뒤에 보낸 오디오는 무시되고 `add_audio_after_eos` 경고가 옵니다.
- 백프레셔: 실시간보다 빠르게(예: 파일을) 보내면 버퍼가 찹니다. 보내기 전에 대기 중인 전송량을 확인합니다(브라우저는 `bufferedAmount`, OkHttp는 `queueSize()`).
- 임시 키 TTL이 지난 뒤 재연결하려면 **새 토큰을 발급받아야** 합니다.

## 8. Android(Kotlin) 구현 메모

> ✅ 2026-11-08: 이 설계대로 `app/`에 구현했습니다. 실제 구현은 아래 **12장**을 보세요.

- **WebSocket**: OkHttp `WebSocket`
  - `Request.Builder().url("wss://eu.rt.speechmatics.com/v2/").header("Authorization", "Bearer $tempKey")`
  - `onOpen`에서 StartRecognition JSON을 `send(String)`으로 보냅니다.
  - `RecognitionStarted`를 받은 뒤 오디오 루프를 시작합니다.
  - 오디오는 `send(ByteString)`(바이너리 프레임)으로 보내고, 보낸 프레임 수를 세어 `last_seq_no`로 씁니다.
- **마이크**: `AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, CHANNEL_IN_MONO, ENCODING_PCM_16BIT, bufSize)`
  - Android 기본 바이트 순서가 little-endian이므로 `pcm_s16le`에 그대로 맞습니다.
  - 청크 크기는 100ms(3200바이트) 정도면 적당합니다. 퀵스타트 Python 예제는 4096바이트를 씁니다.
  - 매니페스트에 `RECORD_AUDIO`와 `INTERNET` 권한이 필요하고, `RECORD_AUDIO`는 런타임에도 요청해야 합니다.
- **키 관리**: API 키를 APK에 넣지 않습니다. 백엔드(예: sori-node 서버)가 `/v1/jwts?type=rt`로 임시 키를 발급하고 앱에 전달합니다. 테스트 단계에서만 `local.properties` → `BuildConfig`로 주입합니다.
- **종료**: `EndOfStream`을 보내고 `EndOfTranscript`를 기다린 뒤 `close(1000, null)`을 호출합니다. 마이크는 먼저 멈춥니다.
- **스레딩**: 녹음 루프는 코루틴(`Dispatchers.IO`)에서 돌리고, 메시지는 파싱해 `StateFlow`/`SharedFlow`로 UI에 넘깁니다.
- 한국어(`ko`)를 지원하는지, 그리고 `enhanced` 모델과 diarization을 함께 쓸 수 있는지는 기존 sori-node에서 사용 중이므로 가능한 것으로 보입니다. 지원 언어 목록에서 한 번 더 확인하세요: https://docs.speechmatics.com/speech-to-text/languages

## 9. 퀵스타트 원본 코드

### Python
```bash
pip install speechmatics-rt pyaudio
```
```python
import asyncio
from speechmatics.rt import (
    AudioEncoding, AudioFormat, AuthenticationError,
    Microphone, ServerMessageType, TranscriptResult,
    TranscriptionConfig, AsyncClient,
)

API_KEY = YOUR_API_KEY

audio_format = AudioFormat(
    encoding=AudioEncoding.PCM_S16LE,
    sample_rate=16000,
    chunk_size=4096,
)
config = TranscriptionConfig(
    model="enhanced",
    language="en",
    max_delay=0.7,
    # enable_partials=True,  # partial 받으려면
)

async def main():
    mic = Microphone(sample_rate=audio_format.sample_rate, chunk_size=audio_format.chunk_size)
    if not mic.start():
        print("Mic not started — please install PyAudio")

    try:
        async with AsyncClient(api_key=API_KEY) as client:
            @client.on(ServerMessageType.ADD_TRANSCRIPT)
            def handle_finals(msg):
                if final := TranscriptResult.from_message(msg).metadata.transcript:
                    print(f"[Final]: {final}")

            # @client.on(ServerMessageType.ADD_PARTIAL_TRANSCRIPT) 로 partial 처리

            try:
                await client.start_session(transcription_config=config, audio_format=audio_format)
                while True:
                    await client.send_audio(await mic.read(chunk_size=audio_format.chunk_size))
            except KeyboardInterrupt:
                pass
            finally:
                mic.stop()
    except AuthenticationError as e:
        print(f"Auth error: {e}")

if __name__ == "__main__":
    asyncio.run(main())
```

### Node.js (+ sox)
```bash
npm install @speechmatics/real-time-client @speechmatics/auth
brew install sox
```
```js
import { spawn } from "node:child_process";
import { createSpeechmaticsJWT } from "@speechmatics/auth";
import { RealtimeClient } from "@speechmatics/real-time-client";

const apiKey = YOUR_API_KEY;
const client = new RealtimeClient();

const audio_format = { type: "raw", encoding: "pcm_s16le", sample_rate: 44100 };

async function transcribe() {
  client.addEventListener("receiveMessage", ({ data }) => {
    if (data.message === "AddTranscript") {
      const transcript = data.metadata?.transcript;
      if (transcript) console.log(`[Final]: ${transcript}`);
    } else if (data.message === "AddPartialTranscript") {
      console.log(`[Partial]: ${data.metadata.transcript}`);
    } else if (data.message === "Error") {
      console.error(`Error [${data.type}]: ${data.reason}`);
      process.exit(1);
    }
  });

  const jwt = await createSpeechmaticsJWT({ type: "rt", apiKey, ttl: 60 });

  await client.start(jwt, {
    transcription_config: { language: "en", max_delay: 0.7, model: "enhanced" /*, enable_partials: true */ },
    audio_format,
  });

  const recorder = spawn("sox", [
    "-d", "-q",
    "-r", String(audio_format.sample_rate),
    "-e", "signed-integer", "-b", "16", "-c", "1",
    "-t", "raw", "-",
  ]);

  recorder.stdout.on("data", (chunk) => client.sendAudio(chunk));
  recorder.stderr.on("data", (d) => console.error(`sox: ${d}`));

  process.on("SIGINT", () => {
    recorder.kill();
    client.stopRecognition({ noTimeout: true });
  });
}

transcribe().catch((err) => { console.error(err); process.exit(1); });
```

출력 예:
```
[Partial]: Hello
[Partial]: Hello welcome to
[Final]:   Hello, welcome to Speechmatics.
```

## 10. 더 볼 문서
- 화자 구분: https://docs.speechmatics.com/speech-to-text/realtime/realtime-diarization
- 턴 감지(EndOfUtterance): https://docs.speechmatics.com/speech-to-text/realtime/turn-detection
- 화자 식별: https://docs.speechmatics.com/speech-to-text/realtime/speaker-identification
- 사용자 사전: https://docs.speechmatics.com/speech-to-text/features/custom-dictionary
- 제한사항: https://docs.speechmatics.com/speech-to-text/realtime/limits
- 모델: https://docs.speechmatics.com/speech-to-text/models
- 지원 언어: https://docs.speechmatics.com/speech-to-text/languages
- 포맷: https://docs.speechmatics.com/speech-to-text/formatting
- 포털(코드 없이 테스트): https://portal.speechmatics.com/jobs/create/real-time

## 11. 미확인 항목
- SDK 버전은 문서에 고정되어 있지 않습니다. Python `AsyncClient`와 `Microphone`의 정확한 시그니처는 패키지 소스에서 확인해야 합니다.
- `AddTranscript`, `Error` 등은 공식 문서에 전체 JSON 예시가 없습니다. 6장의 예시는 필드 목록을 바탕으로 구성한 것입니다.

## 12. Android 구현 현황 (2026-11-08)

sori-node 2의 기능을 Android(View + Kotlin)로 옮겼습니다. 패키지는 `com.example.pleos`입니다.

| sori-node 2 | Android |
|---|---|
| `public/transcript.js` (parseTranscript) | `app/src/main/java/com/example/pleos/stt/TranscriptParser.kt` |
| `public/conversation.js` | `stt/ConversationTranscript.kt` |
| `public/speaker-theme.js` | `stt/SpeakerTheme.kt` (`SpeakerThemeController`, `Scheduler`) |
| `public/speechmatics.js` + `audio-processor.js` | `stt/SpeechmaticsSession.kt` (AudioRecord 16kHz PCM16 mono, 100ms 청크, OkHttp WebSocket) |
| `server.js` 토큰 발급 | `stt/SpeechmaticsTokenProvider.kt` (`/v1/jwts` → 404일 때만 `/v1/api-keys`, `/v1/api_keys`) |
| `public/app.js` + `index.html` + `styles.css` | `MainActivity.kt`, `res/layout/activity_main.xml`, `item_turn.xml`, `values/colors.xml` |
| `tests/*.test.js` | `app/src/test/java/com/example/pleos/stt/*Test.kt` (22개 통과) |

- **API 키**: `local.properties`의 `speechmatics.apiKey`에 있습니다(Git 제외). `BuildConfig.SPEECHMATICS_API_KEY`로 주입됩니다. 원본은 `claude/sori-node 2/.env`입니다. **APK 안에 키가 들어가므로 테스트용입니다.** 배포 전에는 서버가 임시 키를 발급하도록 바꿔야 합니다.
- **설정**: language `ko`, model `enhanced`, diarization `speaker` + `prefer_current_speaker`, partials 켜기, max_delay 2.0, `wss://global.rt.speechmatics.com/v2?jwt=...`
- **Pleos 정책 유지**: 버튼으로만 시작, 오디오 포커스(GAIN_TRANSIENT, 잃으면 중지), `onPause`에서 중지, 매니페스트 `pleos.car.permission.STT_SERVICE`
- **추가 의존성**: OkHttp 4.12.0, kotlinx-coroutines-android 1.9.0, (테스트) org.json 20180813
- **미리보기 강제 실행**: `adb shell am start -n com.example.pleos/.MainActivity --es mode preview`. API 키가 없으면 자동으로 미리보기가 됩니다.
- **빌드**: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew --offline :app:assembleDebug :app:testDebugUnitTest`. lint는 오프라인 캐시에 도구가 없어 실행되지 않습니다.
- **에뮬레이터**: `Pleos_Connect_v2.1.0` AVD(2560x1440)를 씁니다.

### 검증 결과
- 맥에서 같은 프로토콜로 TTS 한국어 음성을 보낸 결과: 토큰 발급, RecognitionStarted, partial/final, 화자 S1, EndOfTranscript까지 정상이었습니다(`word_delimiter`는 `" "`).
- 에뮬레이터: 토큰 발급, 연결, "듣는 중" 상태, 중지·재시작, 미리보기 렌더링(화자 문단, 색, 배경 전환, 현재 표시)이 정상이었습니다.
- **미확인**: 에뮬레이터 마이크로 실제 자막이 나오는지. 맥 스피커 TTS가 에뮬레이터 마이크로 들어가지 않았습니다. 에뮬레이터 Extended Controls → Microphone → "Virtual microphone uses host audio input"을 켜고, macOS 마이크 권한을 확인한 뒤 직접 말해서 확인해야 합니다.

## 13. 화자 식별(목소리 등록) 구현 (2026-11-08)

요구사항: 등록한 목소리는 등록한 이름(예: "홍길동"), 등록하지 않은 사람은 "발화자1", "발화자2" 순서로 표시합니다. 결과 형식은 "홍길동: 에어컨 줄여줘"입니다.

**별도 모델 없이 Speechmatics 화자 식별 기능을 씁니다.**
- 문서: https://docs.speechmatics.com/speech-to-text/realtime/speaker-identification
- 등록: `speaker_diarization_config.get_speakers: true`로 혼자 5~30초 말한 뒤 EndOfStream을 보내면 `SpeakersResult {speakers:[{label:"S1", speaker_identifiers:["..."]}]}`를 받습니다. 식별값 하나는 약 1.5KB 문자열입니다. 실제로는 SpeakersResult가 EndOfTranscript보다 먼저 왔습니다.
- 식별: `speaker_diarization_config.speakers: [{label, speaker_identifiers}]`와 `speaker_sensitivity`를 넣으면, 결과의 `alternatives[].speaker`가 등록 label로 옵니다. 미등록 화자는 S1, S2…로 옵니다.
- 제약: 세션당 식별값은 모든 화자 합계 최대 50개입니다. label은 S1/S2/UU 같은 내부 형식이면 안 됩니다. 식별값은 같은 계정·프로젝트·모델에서만 쓸 수 있고, 모델이 바뀌면 다시 등록해야 합니다. 문제가 있으면 `Warning type=speaker_id`가 옵니다.
- 계정의 **동시 세션 한도는 2개**입니다(Info `concurrent_session_usage`).

**실측(한국어, TTS 음성)**
- 한국어 등록과 식별이 됩니다.
- `speaker_sensitivity` 0.5에서는 음높이만 바꾼 비슷한 목소리를 등록자로 잘못 판단했습니다. 0.8과 1.0에서는 등록자는 이름, 다른 목소리는 S1으로 정확히 구분했습니다. → 앱 기본값은 **0.8**입니다(`MainActivity.SPEAKER_SENSITIVITY`).
- 앱과 같은 설정(prefer_current_speaker + speakers + 0.8)과 내부 label(`P` + 10자리)로도 정상이었습니다.
- 사람 목소리로는 아직 확인하지 못했습니다. 실제 사람으로 감도를 조정해야 합니다.

**파일**
| 파일 | 역할 |
|---|---|
| `stt/RecognitionConfig.kt` | StartRecognition 생성(등록/식별 옵션), 식별값 50개 제한, SpeakersResult 파싱 |
| `stt/VoiceProfiles.kt` | `VoiceProfile`, `VoiceProfileRules`(이름 검증·JSON), `VoiceProfileStore`(앱 전용 `files/voice_profiles.json`), `SpeakerDirectory`(라벨 → "홍길동"/"발화자N"/"화자 확인 중", 확정 결과에서만 번호를 매김) |
| `stt/SpeechmaticsSession.kt` | Options에 `enrolledSpeakers`·`speakerSensitivity`·`getSpeakers`를 추가하고, `onSpeakers`·`onWarning`·`onLevel` 콜백을 추가함. 등록 세션은 EndOfTranscript와 SpeakersResult를 모두 기다린 뒤 종료 |
| `VoiceProfilesActivity.kt` | 설정(톱니) 버튼 → 프로필 목록, 등록, 이름 변경, 다시 등록, 삭제(최대 10명, 이름 12자) |
| `VoiceEnrollmentActivity.kt` | 안내 문장을 읽고 녹음(최소 10초, 최대 30초). 여러 목소리가 잡히면 가장 많이 말한 화자를 저장 |
| `MainActivity.kt` | 시작할 때 프로필을 읽어 세션에 전달. 디버그 빌드는 확정 결과를 `adb logcat -s SttResult`에 "이름: 텍스트"로 기록 |

- 프로필 label은 이름과 분리된 내부 ID입니다(`P` + 10자리). 그래서 이름을 바꿔도 다시 등록할 필요가 없습니다.
- `voice_profiles.json`은 백업과 기기 이전에서 제외했습니다(`res/xml/backup_rules.xml`, `data_extraction_rules.xml`).
- 테스트: `SpeakerIdentificationTest` 11개를 추가해 전체 33개가 통과합니다.
- 에뮬레이터 주의: Pleos 에뮬레이터는 **사용자 10번**에서 앱이 실행됩니다. 앱 데이터에 접근할 때는 `adb shell run-as com.example.pleos --user 10 ...`을 써야 합니다.
- 마이크 입력 확인: `adb logcat -s SttAudio`로 1초마다 dBFS를 볼 수 있습니다. 말할 때 -40 이상이면 정상입니다. 2026-11-08에 맥 입력이 AirPods로 잡혀 있어 에뮬레이터에 무음(-95dBFS)만 들어온 적이 있습니다.
