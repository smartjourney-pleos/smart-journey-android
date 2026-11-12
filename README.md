# smart-journey-android

스마트 저니(Smart Journey)의 Pleos Connect 차량용 안드로이드 앱입니다.
프로젝트 소개와 전체 구조는 [smart-journey-main](https://github.com/smartjourney-pleos/smart-journey-main)을 참고하세요.

## 기술 스택

Kotlin 2.0, Jetpack Compose, Coroutines/Flow, Pleos Vehicle SDK 2.3.2, Pleos NaviHelper SDK 3.0.5

- `minSdk 28`, `targetSdk 34`, `compileSdk 35`, JDK 17 이상 (JDK 21 사용 중)
- 에뮬레이터/차량의 Vehicle 서비스 **2.3.13 이상**, Navi 서비스 **2.0.1 이상** 필요
- 정차 중 여정 준비 화면의 **연결 환경**에 SDK 버전과 설치된 서비스 버전을 표시합니다. 최소 요구 버전은 SDK AAR의 메타데이터를 따르며, 서비스가 없거나 낮으면 설치·업데이트 안내가 나옵니다.

## 0.2.1 — 개정 기획(v4) 1차 제출 빌드

정차 중에는 홈에서 방문을 계획하고 방문 현황에서 결과를 확인합니다. **주행 중에는 화면을 띄우지 않고 내비를 유지한 채 음성으로만 묻고 답합니다.** 헤드업 알림·승인 카드·오버레이는 제거했습니다.

| 핵심 화면 | 내용 |
| --- | --- |
| 1. 동의 | 목적·전송 항목·수신처·보유 기간·철회, 차량 기능 지원 여부 |
| 2. 홈(방문 계획) | 탑승 인원 제안과 수정, 방문지·지원 방식(웨이팅 연동 · 데모), 대기 허용 시간(기본 30분), 방문 시작 |
| 3. 주행 음성 흐름 | 화면 없음. Pleos TTS로 질문 → 끝난 뒤 STT로 응답 |
| 4. 방문 현황 | 도착 예상·예상 대기·요청 전/요청 중/확정/실패/확인 필요·접수 번호, 다음 방문 인원 카드 |
| 5. 도착 후 확인 | 목적지 도착 감지, 하차 추정, 일부 도착("일행 3명 도착 추정 · 1명 이동 중") |
| 6. 확인 필요(예외·미지원) | 음성 실패, 접수 확인 필요, 센서·서비스 미지원, 미연동 매장 |

| 동작 | 규칙 | 신호 |
| --- | --- | --- |
| 웨이팅 질문 | 남은 ETA ≤ 예상 대기 − 도착 여유(10분)일 때 "42dot까지 20분, 대기는 30분이에요. 4명으로 지금 웨이팅을 걸까요?" | ETA: NaviHelper `onTripInfo` 구독 + 30초 `requestTrip` (실제) 또는 데모 입력 |
| 음성 승인 | "응", "걸어 줘", "등록해 줘" 등 정해진 짧은 답만 승인. 무응답·모호한 답·만료·중복은 실행 안 함 | Pleos Speech SDK 3.3.1 ON_DEVICE (실제) |
| 대기 허용 초과 | 예상 대기가 허용 시간보다 길면 "대기가 90분이에요. 그래도 걸까요?". 보류하면 접수·재질문·대체 추천 없음 | - |
| 접수 안전 | 응답 유실은 "확인 필요"로 두고 다시 접수하지 않음. 방문을 끝내도 접수를 취소하지 않음 | `FakeBackend` (시연 데이터) |
| 일부 하차 | 하차 추정 < 방문 인원이면 일부 도착으로 표시하고 도착 완료로 보지 않음 | 좌석·도어 (데모 입력) |
| Gleo | `assets/schema.json`의 `GET_SMART_JOURNEY_STATUS`, `RESPOND_SMART_JOURNEY_PROMPT` | 스키마 등록만 확인, 자연어 왕복 미검증 |

화면 상단에 **"시연용 데이터 · 실제 매장 미접수"**를 항상 표시하고, 값마다 **실제 SDK / 데모 입력** 배지를 붙입니다. 서버·매장 POS는 앱 안 `FakeBackend`이며 실제 매장에 접수되지 않습니다.

검증 결과와 막힌 점(에뮬레이터에서 실시간 `onTripInfo` 미확인 등)은 [검증 기록](docs/verification/persona-v4-2026-11-12.md)에 있습니다.

## 시연 방법

1. Pleos Connect Emulator(v2.1.0)에 **디버그 APK**를 설치합니다. 데모 패널과 adb 조작은 디버그 빌드에만 있습니다.
2. 동의 → 홈에서 인원·방문지·대기 허용 시간 확인 → **방문 시작**. 내비가 앞으로 나오고 2초 뒤 (데모) 주행이 시작됩니다.
3. 데모 패널 또는 터미널로 신호를 넣습니다. 앱 사용자 ID는 10입니다.

```bash
d(){ adb shell am broadcast --user 10 -n com.smartjourney.vehicle/.debug.DemoReceiver --es cmd "$@"; }
d remaining --ei n 20   # 남은 20분 → 음성 질문
d walkin --ei n 12      # 대기 90분 → 대기 허용 초과 질문
d limit --ei n 60       # 대기 허용 시간 60분
d eta --ei n 1          # 남은 시간을 실제 내비(onTripInfo)로
d arrive; d alight --ei n 3   # 도착 후 4명 중 3명 하차 → 일부 도착
d alight --ei n 1       # 운전자 하차 → 4/4 하차 추정
adb logcat -s SJ SJ-DEMO SJ-NAVI   # 엔진·내비 로그
```

음성 응답은 차량 마이크로 말하거나, 디버그 빌드의 `SpeechProbeActivity`로 합성 PCM(`files/voice-test.pcm`, 16kHz·16bit·mono)을 실제 ASR에 넣어 확인합니다.

## 구성

| 경로 (`app/src/main/java/com/smartjourney/vehicle/`) | 내용 |
| --- | --- |
| `core/` | SDK 래퍼와 Fake. `VehicleGateway`(좌석·벨트·도어), `NaviGateway`(경로·남은 시간·도착), `DrivingStateGateway`(CarUxRestrictions) |
| `domain/` | 순수 Kotlin 규칙. `goldenTime`·`PromptGate`(골든타임), `AlightDetector`(하차 확인 상태 머신) |
| `engine/` | `JourneyEngine`: 매장마다 골든타임 → 승인 → 접수 → 도착·하차 → 호출 → 착석을 진행 |
| `service/` | `JourneyService`(포그라운드 서비스, 엔진 유지·음성 질문)와 LOW 중요도 상태 알림 |
| `voice/` | `VoicePromptController`(읽기 후 듣기, 승인 판정), `PleosVoiceGateway`(TTS·STT), `GleoActionReceiver` |
| `data/` | `WaitingBackend`(서버 계약)와 `FakeBackend` |
| `demo/` | `DemoController`: 데모 신호 조작 |
| `ui/` | 정차 화면(동의·홈·방문 현황·확인 필요), 주행 중 상태, 데모 패널 |
| `AppGraph.kt` | 객체 묶음, 실제 신호와 데모 신호 선택(`AppSignals`) |
| `app/src/debug/` | `ProbeActivity`(SDK 신호 로그), `DemoReceiver`(터미널 조작), `SpeechProbeActivity`(합성 음성 검증). 디버그 빌드에만 들어감 |

선언한 권한: `CAR_SEATS`, `CAR_DOORS`, `NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`, `NAVI_CUSTOM_MAP`, `TTS_SERVICE`, `STT_SERVICE`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`(+`SPECIAL_USE`). 오버레이(`SYSTEM_ALERT_WINDOW`) 권한은 없습니다.

데모 패널, 1초 데모 타이머, adb 조작(`DemoReceiver`, `ProbeActivity`)은 디버그 빌드에서만 동작합니다. `FakeBackend`(앱 안 가짜 서버)는 릴리스에도 들어갑니다. 그래서 릴리스 화면에도 "시연용 데이터 · 실제 매장 미접수"를 고정 표시하며, 실서버 연동 전 빌드를 실제 접수 앱으로 설명하지 않습니다.

주간/야간: 차량 야간 모드를 따릅니다. 에뮬레이터에서는 `adb shell cmd car_service day-night-mode night`(되돌리기 `sensor`)로 확인합니다.
주행 규제: 데모 신호 대신 실제 CarUxRestrictions 경로를 확인하려면 데모 패널에서 실제 신호로 바꾸고 `adb shell cmd hmg_car_service --service drivingstate driving_regulation on` (Pleos FAQ - 디버깅 ADB)을 씁니다. 이 명령은 에뮬레이터에서 아직 실행해 보지 않았습니다.

지도·API 키·환경설정 구분과 11/11 기기 재현 결과는 [내비 화면과 환경설정](docs/navigation-and-env.md)을 참고하세요. `.env.example`은 참고 목록이며 현재 빌드가 `.env`를 읽거나 APK에 주입하지 않습니다. Vehicle/NaviHelper 연동에 프로젝트 키·클라이언트 토큰은 사용하지 않습니다.

## 빌드와 실행

Android Studio에서 이 폴더를 열면 됩니다. 터미널에서는 다음을 사용합니다.

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # 단위 테스트 (골든타임, 하차 확인, 엔진 흐름)
```

**배포 빌드 서명**: 키 정보는 환경변수나 `local.properties`의 `SJ_RELEASE_STORE_FILE`, `SJ_RELEASE_STORE_PASSWORD`, `SJ_RELEASE_KEY_ALIAS`, `SJ_RELEASE_KEY_PASSWORD`에만 둡니다. 없으면 서명되지 않은 APK가 나옵니다.
**버전**: 심사 요청·배포 때마다 `versionCode`를 올립니다(Pleos 앱 버전 관리 정책).

`local.properties`(Android SDK 경로)는 커밋하지 않으므로 처음 한 번 `sdk.dir=<SDK 경로>`를 직접 만들거나 Android Studio가 만들게 하세요.
Pleos Connect Emulator 설정은 Pleos 공식 문서(Getting Started)를 따릅니다. 에뮬레이터에서 로컬 서버 주소는 `http://10.0.2.2:8080`입니다.

## 브랜치 규칙

| 브랜치 | 용도 |
| --- | --- |
| `main` | 제출·심사용 안정 버전 |
| `dev` | 개발 통합 |
| `feature/기능명` | 기능 작업, `dev`로 PR |

공개 저장소이므로 API 키, 서명 키(`*.keystore`, `*.jks`), `local.properties`는 커밋하지 않습니다.
