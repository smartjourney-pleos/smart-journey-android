# smart-journey-android

스마트 저니(Smart Journey)의 Pleos Connect 차량용 안드로이드 앱입니다.
프로젝트 소개와 전체 구조는 [smart-journey-main](https://github.com/smartjourney-pleos/smart-journey-main)을 참고하세요.

## 기술 스택

Kotlin 2.0, Jetpack Compose, Coroutines/Flow, Pleos Vehicle SDK 2.3.2, Pleos NaviHelper SDK 3.0.5

- `minSdk 28`, `targetSdk 34`, `compileSdk 35`, JDK 17 이상 (JDK 21 사용 중)
- 에뮬레이터/차량의 Vehicle 서비스 **2.3.13 이상**, Navi 서비스 **2.0.1 이상** 필요
- 정차 중 여정 준비 화면의 **연결 환경**에 SDK 버전과 설치된 서비스 버전을 표시합니다. 최소 요구 버전은 SDK AAR의 메타데이터를 따르며, 서비스가 없거나 낮으면 설치·업데이트 안내가 나옵니다.

## 0.2.13 — 1차 기획 검증용 프로토타입 (내비 자동 전환·팝업 안정화 및 서버·웹 POS 연동)

정차 중에는 홈에서 방문을 계획하고, 방문 현황에서 결과를 확인합니다. **주행 중에는 내비 화면을 그대로 두고 음성으로 묻고 답하는 것이 기본입니다.** 사용자가 "다른 앱 위에 표시" 권한을 허용한 환경에서만 내비 위에 웨이팅 질문 카드를 함께 띄웁니다.

| 핵심 화면 | 내용 |
| --- | --- |
| 1. 동의 | 목적·전송 항목·수신처·보유 기간·철회, 차량 기능 지원 여부 |
| 2. 홈(방문 계획) | 탑승 인원 제안과 수정, 방문지·지원 방식(웨이팅 연동 · 데모), 대기 허용 시간(기본 30분), 방문 시작 |
| 3. 주행 중 질문 | Pleos TTS로 질문한 뒤 STT로 응답을 받음. 오버레이 권한이 있으면 내비 위 카드(10초 링, "나중에"/"등록")도 표시 |
| 4. 방문 현황 | 도착 예상·예상 대기·요청 전/요청 중/확정/실패/확인 필요·접수 번호, 다음 방문 인원 카드, 방문 끝내기 |
| 5. 도착 후 확인 | 목적지 도착 감지, 하차 추정, 일부 도착("일행 3명 도착 추정 · 1명 이동 중") |
| 6. 확인 필요(예외·미지원) | 음성 실패, 접수 확인 필요, 센서·서비스 미지원, 미연동 매장 |

| 동작 | 규칙 | 신호 |
| --- | --- | --- |
| 웨이팅 질문 | 남은 ETA ≤ 예상 대기 − 도착 여유(10분)일 때 "42dot까지 20분, 대기는 30분이에요. 4명으로 지금 웨이팅을 걸까요?". 10초 안에 답이 없으면 접수하지 않고 닫음 | ETA: NaviHelper `onTripInfo` 구독 + 30초 `requestTrip` (실제) 또는 데모 입력 |
| 음성 승인 | "응", "걸어 줘", "등록해 줘" 등 정해진 짧은 답만 승인. 무응답·모호한 답·만료·중복은 실행 안 함 | Pleos Speech SDK 3.3.1 ON_DEVICE (실제) |
| 대기 허용 초과 | 예상 대기가 허용 시간보다 길면 "대기가 90분이에요. 그래도 걸까요?". 보류하면 접수·재질문·대체 추천 없음 | - |
| 접수 안전 | 응답 유실은 "확인 필요"로 두고 다시 접수하지 않음. 방문을 끝내도 접수를 취소하지 않음 | `FakeBackend`(기본) 또는 debug 서버 모드의 `ServerBackend` |
| 순정 내비 안내 종료 | 접수 전 방문만 닫고, 접수·요청 중·결과 미상인 방문은 유지. 매장 웨이팅은 취소하지 않음. 우리가 경로를 바꾸며 생긴 이전 경로의 취소는 내비의 현재 경로 상태(`RouteStateInfo`)로 가려 무시 | NaviHelper `onRouteCancelled`, `getRouteStateInfo` |
| 방문 끝내기 | 접수했거나 결과를 모를 때는 확인창("계속 안내"/"안내 종료")을 거침. 방문 추적만 끝내고 접수 기록과 내비 경로는 건드리지 않음 | - |
| 일부 하차 | 하차 추정 < 방문 인원이면 일부 도착으로 표시하고 도착 완료로 보지 않음 | 좌석·도어 (데모 입력) |
| Gleo | `assets/schema.json`(version 0.2.13)에는 상태 조회 `GET_SMART_JOURNEY_STATUS`만 둠. **Gleo를 통한 접수·인원 변경 승인은 받지 않음**: Gleo Action은 모델이 만든 `ACTION_PARAM`만 전달하고 새 사용자 발화를 증명하는 값이 없음(Gleo Interface 가이드, 설치된 Gleo AI 4.2.0.15 확인). 예전 스키마로 오는 승인 호출에도 아무것도 실행하지 않음 | 0.2.10: 에뮬레이터에서 Gleo가 상태 조회 함수 1개를 등록하는 것만 확인. 상태 조회 자연어 왕복은 이전 빌드(0.2.5)에서 Mac `say` 합성 음성을 호스트 마이크로 넣어 시험한 결과이며 0.2.10에서는 재시험하지 않음. 사람이 말하는 Gleo 대화 전체 흐름은 미검증 |

화면 상단에는 **"시연용 데이터 · 실제 매장 미접수"**를 항상 표시하고, 값마다 **실제 SDK / 데모 입력** 배지를 붙입니다. 기본 접수처는 앱 안의 `FakeBackend`입니다. **debug 빌드**는 데모 패널의 "접수 백엔드" 스위치로 [smart-journey-server](https://github.com/smartjourney-pleos/smart-journey-server)에 접수하고 [웹 POS](https://github.com/smartjourney-pleos/smart-journey-web)에서 호출·착석할 수 있습니다([서버·웹과 함께 실행](#서버웹-pos와-함께-실행)). 서버의 매장도 시연용(웨이팅은 실제 영업 정보가 아님)이라 실제 매장에는 접수되지 않습니다.

### 검증 범위와 남은 제약

- 0.2.13 내비 자동 전환·팝업 안정화 검증: 콜드부팅 후 순정 내비 미초기화 상태에서 `42dot 선택 → 방문 시작 → 순정 내비 자동 전환 → 음성 질문 및 깜빡임 없는 웨이팅 팝업`과 `0.2.12 → 0.2.13` 서명 release 업데이트를 에뮬레이터에서 확인, 단위 테스트 97개 통과, lint 오류 0건. [0.2.13 기록](docs/verification/release-0.2.13.md)
- 0.2.11 서버 연동 검증: 로컬 서버 + 웹 POS + 에뮬레이터로 접수 → ETA → 하차 4/4 → 웹 호출 → 착석 → 다음 매장 확인. [0.2.11 기록](docs/verification/release-0.2.11.md)
- 0.2.10 통합 검증: `./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug` 성공, 단위 테스트 87개 실패 0(Gleo 토큰 승인 테스트 17개를 승인 차단 회귀 테스트 9개로 교체). 변경 내용은 [0.2.10 기록](docs/verification/release-0.2.10.md)에 있습니다.
- 아래 독립 QA는 서명된 release 0.2.9 기준입니다. 0.2.10은 Gleo 승인 경로만 닫은 수정이며 독립 QA는 별도로 진행합니다.
  - 강제 권한 없는 일반 설치에서 순정 내비로 안내를 종료하면 접수 전 방문이 정리되는 것을 확인했습니다.
  - 권한을 따로 부여한 QA 환경에서는 지도 카드 등록(시연 1명·11번 확정), 확정 뒤 내비 종료 시 접수 유지, 방문 끝내기 확인창을 확인했습니다.
  - 자세한 내용은 [0.2.9 검증 기록](docs/verification/release-0.2.9.md)에 있습니다.
- **미해결:**
  - 일반 설치에서는 지도 위 카드가 뜨지 않습니다. 이 Pleos 이미지에는 "다른 앱 위에 표시" 권한 설정 화면이 없고, 백그라운드 화면 시작과 알림 배너도 막혀 있어 음성 질문이 기본 경로입니다.
  - Gleo를 실제 마이크로 쓰는 흐름은 검증하지 못했습니다. Gleo로 승인하는 기능은 사용자 발화 증거가 없어 막아 두었습니다.
  - 순정 내비가 안내를 끝내기 전에 확인창을 띄우는 공개 SDK 이벤트는 확인되지 않았습니다. 사전 확인은 앱의 "방문 끝내기"에서만 합니다.
  - 실차 내비 값은 확인하지 못했습니다.
- 이전 기록: [개정 기획 개발 검증](docs/verification/persona-v4-2026-11-12.md), [Gleo 시험](docs/verification/gleo-voice-test.md)

## 시연 방법

1. Pleos Connect Emulator(v2.1.0)에 APK를 설치합니다. 실제 신호로 시연할 때는 서명된 release APK를 일반 설치(`adb install`)합니다. 데모 패널과 adb 신호 조작이 필요하면 **디버그 APK**를 씁니다(디버그 빌드에만 있음). 지도 위 카드를 보려면 화면 상단의 "다른 앱 위 팝업 허용"으로 권한을 허용해야 하며, 이 권한 설정 화면이 없는 이미지에서는 음성 질문만 동작합니다.
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

## 서버·웹 POS와 함께 실행

세 저장소의 `main`을 받아 한 컴퓨터에서 돌립니다. 자세한 순서와 문제 해결은 [smart-journey-main README](https://github.com/smartjourney-pleos/smart-journey-main#서버웹-pos와-함께-실행)에 있습니다.

```bash
# 1) 서버 (JDK 21, H2 + 데모 매장 자동) — http://localhost:8080
git clone https://github.com/smartjourney-pleos/smart-journey-server && cd smart-journey-server && ./gradlew bootRun
# 2) 웹 POS (Node 22+) — http://localhost:5173, 매장 42dot · PIN 1234
git clone https://github.com/smartjourney-pleos/smart-journey-web && cd smart-journey-web && npm install && npm run dev
# 3) 앱 debug APK 설치 → 동의 → 데모 패널 "접수 백엔드" 스위치 켜기(서버 연동) → 방문 시작
./gradlew :app:installDebug   # 에뮬레이터에서 서버 주소는 http://10.0.2.2:8080 (debug 고정)
```

앱이 서버에 기기 등록을 하려면 앱의 고지 버전(`CONSENT_VERSION`)과 서버 `smartjourney.consent.current-version`이 같아야 합니다(현재 `2026-11-v1`). 데모 매장은 세 곳 모두 42dot(1)·소프트웨어드림센터(2)입니다.

## 구성

| 경로 (`app/src/main/java/com/smartjourney/vehicle/`) | 내용 |
| --- | --- |
| `core/` | SDK 래퍼와 Fake. `VehicleGateway`(좌석·벨트·도어), `NaviGateway`(경로·남은 시간·도착), `DrivingStateGateway`(CarUxRestrictions), `RouteCancelFilter`(순정 내비 안내 종료와 경로 교체 구분) |
| `domain/` | 순수 Kotlin 규칙. `goldenTime`·`PromptGate`(골든타임), `AlightDetector`(하차 확인 상태 머신) |
| `engine/` | `JourneyEngine`: 매장마다 골든타임 → 승인 → 접수 → 도착·하차 → 호출 → 착석을 진행 |
| `service/` | `JourneyService`(포그라운드 서비스, 엔진 유지·음성 질문)와 LOW 중요도 상태 알림 |
| `voice/` | `VoicePromptController`(읽기 후 듣기, 승인 판정), `PleosVoiceGateway`(TTS·STT), `GleoActionReceiver`·`GleoActionHandler`(상태 조회만, 승인 차단) |
| `data/` | `WaitingBackend`(서버 계약), `FakeBackend`(앱 안 시연 데이터), `ServerBackend`(smart-journey-server REST, 3초 조회)·`SwitchingBackend` |
| `demo/` | `DemoController`: 데모 신호 조작 |
| `ui/` | 정차 화면(동의·홈·방문 현황·확인 필요), 주행 중 상태, 데모 패널, 내비 위 웨이팅 카드(`MapPromptPopup`·`PromptCard`, 오버레이 권한 허용 시) |
| `AppGraph.kt` | 객체 묶음, 실제 신호와 데모 신호 선택(`AppSignals`) |
| `app/src/debug/` | `ProbeActivity`(SDK 신호 로그), `DemoReceiver`(터미널 조작), `SpeechProbeActivity`(합성 음성 검증). 디버그 빌드에만 들어감 |

선언한 권한: `CAR_SEATS`, `CAR_DOORS`, `NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`, `NAVI_CUSTOM_MAP`, `TTS_SERVICE`, `STT_SERVICE`, `INTERNET`, `SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`(+`SPECIAL_USE`). `SYSTEM_ALERT_WINDOW`은 사용자가 설정에서 허용한 경우에만 내비 위 웨이팅 카드에 씁니다. 허용하지 않아도 음성 질문과 앱 화면으로 모든 흐름을 진행할 수 있습니다.

데모 패널, 1초 데모 타이머, adb 조작(`DemoReceiver`, `ProbeActivity`)은 디버그 빌드에서만 동작합니다. `FakeBackend`(앱 안 가짜 서버)는 릴리스에도 들어갑니다. 그래서 릴리스 화면에도 "시연용 데이터 · 실제 매장 미접수"를 고정 표시하며, 실서버 연동 전 빌드를 실제 접수 앱으로 설명하지 않습니다.

주간/야간: 차량 야간 모드를 따릅니다. 에뮬레이터에서는 `adb shell cmd car_service day-night-mode night`(되돌리기 `sensor`)로 확인합니다.
주행 규제: 데모 신호 대신 실제 CarUxRestrictions 경로를 확인하려면 데모 패널에서 실제 신호로 바꾸고 `adb shell cmd hmg_car_service --service drivingstate driving_regulation on` (Pleos FAQ - 디버깅 ADB)을 씁니다. 이 명령은 에뮬레이터에서 아직 실행해 보지 않았습니다.

11/11 기기 내비 조회 결과는 [실행 환경 검증 기록](docs/verification/pleos-runtime-2026-11-11.json)을 참고하세요. `.env.example`은 참고 목록이며 현재 빌드가 `.env`를 읽거나 APK에 주입하지 않습니다. Vehicle/NaviHelper 연동에 프로젝트 키·클라이언트 토큰은 사용하지 않습니다.

## 빌드와 실행

Android Studio에서 이 폴더를 열면 됩니다. 터미널에서는 다음을 사용합니다.

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/
./gradlew :app:assembleRelease      # app/build/outputs/apk/release/ (서명 키가 있으면 서명됨)
./gradlew :app:testDebugUnitTest    # 단위 테스트 (골든타임, 하차 확인, 엔진 흐름, 내비 취소 판정)
```

**배포 빌드 서명**: 키 정보는 환경변수나 `local.properties`의 `SJ_RELEASE_STORE_FILE`, `SJ_RELEASE_STORE_PASSWORD`, `SJ_RELEASE_KEY_ALIAS`, `SJ_RELEASE_KEY_PASSWORD`에만 둡니다. 없으면 서명되지 않은 APK가 나옵니다.
**버전**: 심사 요청·배포 때마다 `versionCode`를 올립니다(Pleos 앱 버전 관리 정책).

`local.properties`(Android SDK 경로)는 커밋하지 않으므로 처음 한 번 `sdk.dir=<SDK 경로>`를 직접 만들거나 Android Studio가 만들게 하세요.
Pleos Connect Emulator 설정은 Pleos 공식 문서(Getting Started)를 따릅니다. 에뮬레이터에서 로컬 서버 주소는 `http://10.0.2.2:8080`입니다.

## 개발 규칙

Pleos 관련 구현·검토·테스트는 [공통 개발 규칙](docs/development/pleos-connect-rules.md)을 따릅니다. 제공된 Pleos 아카이브의 필수·권장 사항과 프로젝트 자체 기준을 구분하고 출처를 기록했습니다. 자동 작업 도구는 [AGENTS.md](AGENTS.md), Claude는 [CLAUDE.md](CLAUDE.md)를 진입점으로 사용합니다.

## 브랜치 규칙

| 브랜치 | 용도 |
| --- | --- |
| `main` | 제출·심사용 안정 버전 |
| `dev` | 개발 통합 |
| `feature/기능명` | 기능 작업, `dev`로 PR |

공개 저장소이므로 API 키, 서명 키(`*.keystore`, `*.jks`), `local.properties`는 커밋하지 않습니다.
