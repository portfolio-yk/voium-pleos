# VOIUM (보이음)

**차 안의 대화를 말하는 사람별로 구분해 실시간 자막으로 보여 주는 Pleos Connect 앱입니다.**

듣기 어려운 가족도 차 안 대화에 함께할 수 있도록, 말을 큰 자막으로 띄우고 말하는 사람마다 색을 달리 보여 줍니다.
말로 답하기 어려우면 화면의 빠른 답장을 눌러 차량 스피커로 대신 말할 수 있습니다.

## 실행 방법

### APK로 바로 설치

```bash
adb install -r "apk/소소만_VOIUM_release.apk"
```

Pleos 에뮬레이터는 사용자 10에서 앱이 실행됩니다. 설치가 보이지 않으면 사용자를 지정해 설치하세요.

```bash
adb install -r --user 10 "apk/소소만_VOIUM_release.apk"
```

설치 후 홈 화면 독의 **VOIUM** 아이콘을 눌러 실행하고, 처음 실행할 때 마이크 권한을 허용하세요.

### 소스에서 빌드

1. Android Studio에서 이 폴더를 엽니다.
2. 아래 [필요한 설정](#필요한-설정)대로 `local.properties`에 키를 넣습니다.
3. Pleos Connect 에뮬레이터를 켠 뒤 실행(▶)하거나 아래 명령을 씁니다.

```bash
./gradlew :app:installDebug          # 디버그 빌드 설치
./gradlew :app:assembleRelease       # 릴리즈 APK 만들기 (app/build/outputs/apk/release/)
./gradlew :app:testDebugUnitTest     # 단위 테스트
```

### 사용 순서

1. **대화 시작**을 누르면 듣기 시작합니다.
2. 말이 자막으로 크게 나오고, 지난 말은 위쪽에 말한 사람과 함께 쌓입니다.
3. 아무도 말하지 않으면 시간·목적지 같은 차량 상황에 맞는 **대화 제안**이 나옵니다.
4. 누군가 나에게 물으면 아래에 **빠른 답장**이 나오고, 누르면 차량 스피커로 읽어 줍니다.
5. **대화 종료**를 누르면 그때까지의 자막이 대화 기록으로 저장됩니다(설정에서 끌 수 있음).

## 테스트 환경 및 필요한 설정

### 테스트 환경

| 항목 | 내용 |
| --- | --- |
| 개발 도구 | Android Studio, Gradle 9.6.0, Android Gradle Plugin 9.5.0 |
| 앱 SDK | minSdk 28, targetSdk 37 |
| 기기 | Pleos Connect 에뮬레이터 (아래 참고) |
| 언어 | 한국어 |

### 필요한 설정

키는 Git에 올라가지 않는 `local.properties`(프로젝트 최상위)에 넣습니다. 키가 없어도 빌드는 되지만 해당 기능이 동작하지 않습니다.

```properties
# 화자 구분 (Speechmatics 실시간 음성 인식)
speechmatics.apiKey=발급받은_키

# Pleos Connect 앱 키 (Pleos Playground 콘솔에서 발급)
pleos.clientId=발급받은_ID
pleos.clientSecret=발급받은_시크릿

# 대화 제안 · 빠른 답장 · 대화 요약 (Claude API)
anthropic.apiKey=발급받은_키

# 릴리즈 서명 (릴리즈 APK를 직접 만들 때만)
release.storeFile=keystore/voium-release.jks
release.storePassword=비밀번호
release.keyAlias=voium
release.keyPassword=비밀번호
```

- **권한**: 마이크(`RECORD_AUDIO`)는 처음 실행할 때 허용합니다. Pleos 차량 권한(`STT_SERVICE`, `TTS_SERVICE`, `CAR_SEATS`, `CAR_DRIVE`, `CAR_LIGHTS`, `NAVI_ROUTE` 등)은 매니페스트에 선언되어 있어 따로 설정할 필요가 없습니다.
- **인터넷 연결**: 화자 구분과 AI 기능에 필요합니다.
- **차량 상태 바꾸기**: 에뮬레이터의 Virtual Console에서 기어 등을 바꿀 수 있습니다.
  `adb forward tcp:8088 tcp:8088` 후 브라우저에서 <http://127.0.0.1:8088/> 를 엽니다.
  기어를 D로 바꾸면 주행 중 제한(설정 화면 잠금 등)이 적용됩니다.

### 사용한 Pleos Connect SDK

| SDK | 버전 | 용도 |
| --- | --- | --- |
| SpeechToText | 3.3.1 | 음성을 글자로 바꾸기 |
| TextToSpeech | 3.3.1 | 빠른 답장을 차량 스피커로 읽기 |
| Vehicle | 2.3.2 | 좌석 탑승, 기어(주행 중 제한), 실내 조명 색, 와이퍼 |
| NaviHelper | 3.0.5 | 내비 목적지와 남은 시간(대화 제안) |

## 개발에 사용한 에뮬레이터

| 항목 | 내용 |
| --- | --- |
| 에뮬레이터 | Pleos Connect v2.1.0 (AVD `Pleos_Connect_v2.1.0`) |
| Android 버전 | Android 14 (API 34) |
| 화면 해상도 | 2560 × 1440 (가로) |
| 화면 밀도 | 160 dpi (mdpi) |

## APK 파일

| 파일 | 설명 |
| --- | --- |
| [`apk/소소만_VOIUM_release.apk`](apk/%EC%86%8C%EC%86%8C%EB%A7%8C_VOIUM_release.apk) | 릴리즈 빌드 (릴리즈 키로 서명) |

> 이미 같은 패키지(`com.example.pleos`)의 다른 서명 앱이 설치되어 있으면 덮어 설치할 수 없습니다. 먼저 `adb uninstall com.example.pleos`로 지우세요.
