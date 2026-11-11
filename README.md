# smart-journey-android

스마트 저니(Smart Journey)의 Pleos Connect 차량용 안드로이드 앱입니다.
프로젝트 소개와 전체 구조는 [smart-journey-main](https://github.com/smartjourney-pleos/smart-journey-main)을 참고하세요.

## 기술 스택

Kotlin 2.0, Jetpack Compose, Coroutines/Flow, Pleos Vehicle SDK 2.3.2, Pleos NaviHelper SDK 3.0.5

- `minSdk 28`, `targetSdk 34`, `compileSdk 35`, JDK 17 이상 (JDK 21 사용 중)
- 에뮬레이터/차량의 Vehicle 서비스 **2.3.13 이상**, Navi 서비스 **2.0.1 이상** 필요
- 정차 중 여정 준비 화면의 **연결 환경**에 SDK 버전과 설치된 서비스 버전을 표시합니다. 최소 요구 버전은 SDK AAR의 메타데이터를 따르며, 서비스가 없거나 낮으면 설치·업데이트 안내가 나옵니다.

## 프로토타입 1차 (지금 되는 것)

정차 중에 인원을 확인하고 방문지를 고른 뒤 **여정 시작**을 누르면, 내비에 경로가 들어가고 앱은 내비 뒤로 물러납니다.
그 뒤로는 내비 위 헤드업 알림(버튼 2개)과 승인 카드로만 운전자를 만납니다.

| 단계 | 동작 | 신호 |
| --- | --- | --- |
| 인원 제안 | 좌석 점유가 5초 유지되면 탑승 인원으로 제안, 직접 수정 가능 | Vehicle SDK (실제) |
| 여정 시작 | 방문지 1~3곳을 내비에 목적지·경유지로 입력 | NaviHelper `requestRouteWithWaypoints` (실제) |
| 골든타임 | 남은 시간 ≤ 예상 대기 − 도착 여유가 되면 "지금 웨이팅 걸까요?" | 첫 값은 NaviHelper `requestTrip`(실제), 이후는 데모 조작 |
| 승인 접수 | 알림의 [등록] 또는 승인 카드. 승인 없이는 접수하지 않음 | - |
| 하차 확인 | 도착 → 정차 → 문 열림 → 문 닫힘 + 좌석 5초 유지 → "3/4명 하차" 전송. 3분 안에 못 하면 [도착했어요] | 데모 조작 |
| 호출·착석 | 점주 호출 → 번호표 알림 → 착석 → 다음 매장 | 데모 조작 (POS 역할) |
| 인원 변동 | 다음 매장 출발 전 재탑승 인원이 다르면 "3명으로 바꿀까요?" | 데모 조작 |

**아직 가짜인 것**
- 서버와 매장 POS: 앱 안의 `FakeBackend`가 대신합니다. 서버 저장소의 여정·접수 API가 나오면 HTTP·STOMP 구현으로 바꿉니다.
- 좌석·도어·주행 상태의 *변화*와 남은 시간 감소, 도착 이벤트: 에뮬레이터에서 값을 바꿀 수 없어 데모 패널로 넣습니다. 데모 패널의 스위치를 끄면 실제 차량 신호로 돕니다.
- Gleo 음성 승인, 짐 의심 좌석 표시, 순번 미루기는 없습니다.

데모 방문지는 [Pleos 공식 내비 샘플의 POI](https://github.com/Pleos-Playground/pleos-sample-navi-app/blob/main/app/src/main/java/ai/pleos/playground/navidevapp/data/sample/NaviTestData.kt)를 사용하는 **42dot → 소프트웨어드림센터** 두 곳입니다. 샘플의 예전 주소·좌표 대신 [기기 내비의 현재 POI 조회 결과](docs/verification/pleos-runtime-2026-11-11.json)로 앱과 내비의 장소명·주소·좌표·POI를 일치시켰습니다. 이 장소들의 웨이팅과 POS는 실제 영업 정보가 아닌 시연 데이터입니다. 엔진은 기존처럼 최대 3곳을 지원합니다.

## 시연 방법

1. Pleos Connect Emulator(v2.1.0)를 켜고 디버그 APK를 설치합니다.
2. 앱에서 동의 → 인원 확인 → 방문지 선택 → **여정 시작**. 내비가 앞으로 나오고 2초 뒤 (데모) 주행이 시작됩니다.
3. 오른쪽 **데모 조작 패널** 또는 터미널로 신호를 넣습니다. 앱이 내비 뒤에 있을 때는 터미널이 편합니다.

```bash
scripts/demo.sh remaining 20   # 남은 시간을 20분으로 → 골든타임 알림이 내비 위에 뜸
scripts/demo.sh arrive         # 도착 이벤트
scripts/demo.sh alight 3       # 정차 → 문 열림 → 3명 하차 → 문 닫힘 (5초 뒤 "3/4명 하차 확인")
scripts/demo.sh walkin 2       # 매장 현장 대기 +2팀 → 알림 시점이 바로 다시 계산됨
scripts/demo.sh call           # 점주 호출
scripts/demo-flow.sh           # 두 매장 여정을 처음부터 끝까지 자동으로 (약 3분)
adb logcat -s SJ SJ-DEMO       # 엔진 로그
```

**주행 중**에는 승인 요청이 내비 위 헤드업 알림(제목·내용·버튼 2개)으로만 뜹니다. 카드 화면은 앱 영역 전체를 차지해 지도를 가릴 수 있어(Pleos Window Policy) 운전자가 배너를 눌렀을 때만 열립니다.
**정차 중**에는 카드 화면을 바로 띄워 보고, 시스템이 막으면 헤드업 알림으로 대신합니다.
디버그 빌드에서는 "다른 앱 위에 표시"를 허용하면 정차 중 백그라운드에서도 카드가 바로 뜹니다(시연용, 심사 빌드에는 이 권한이 없습니다):

```bash
adb shell appops set --user 10 com.smartjourney.vehicle SYSTEM_ALERT_WINDOW allow
```

## 구성

| 경로 (`app/src/main/java/com/smartjourney/vehicle/`) | 내용 |
| --- | --- |
| `core/` | SDK 래퍼와 Fake. `VehicleGateway`(좌석·벨트·도어), `NaviGateway`(경로·남은 시간·도착), `DrivingStateGateway`(CarUxRestrictions) |
| `domain/` | 순수 Kotlin 규칙. `goldenTime`·`PromptGate`(골든타임), `AlightDetector`(하차 확인 상태 머신) |
| `engine/` | `JourneyEngine`: 매장마다 골든타임 → 승인 → 접수 → 도착·하차 → 호출 → 착석을 진행 |
| `service/` | `JourneyService`(포그라운드 서비스, 엔진을 살려 둠)와 알림 |
| `data/` | `WaitingBackend`(서버 계약)와 `FakeBackend` |
| `demo/` | `DemoController`: 데모 신호 조작 |
| `ui/` | 정차 화면(`MainActivity`), 승인 카드(`ApprovalActivity`), 데모 패널 |
| `AppGraph.kt` | 객체 묶음, 실제 신호와 데모 신호 선택(`AppSignals`) |
| `app/src/debug/` | `ProbeActivity`(SDK 신호 로그), `DemoReceiver`(터미널 조작). 디버그 빌드에만 들어감 |

선언한 권한: `CAR_SEATS`, `CAR_DOORS`, `NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`, `NAVI_CUSTOM_MAP`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`(+`SPECIAL_USE`). 디버그 빌드에만 `SYSTEM_ALERT_WINDOW`(시연용).

데모 패널, 1초 데모 타이머, adb 조작(`DemoReceiver`, `ProbeActivity`)은 디버그 빌드에서만 동작합니다. `FakeBackend`(앱 안 가짜 서버)는 아직 릴리스에도 들어가므로, **실서버 연동 전에는 심사용 빌드를 만들지 않습니다**.

주간/야간: 차량 야간 모드를 따릅니다. 에뮬레이터에서는 `adb shell cmd car_service day-night-mode night`(되돌리기 `sensor`)로 확인합니다.
주행 규제: 데모 신호 대신 실제 CarUxRestrictions 경로를 확인하려면 데모 패널에서 실제 신호로 바꾸고 `adb shell cmd hmg_car_service --service drivingstate driving_regulation on` (Pleos FAQ - 디버깅 ADB)을 씁니다. 이 명령은 에뮬레이터에서 아직 실행해 보지 않았습니다.

**에뮬레이터에서 확인한 것 (11/11)**
- 백그라운드에서 서비스가 화면을 직접 띄우면 시스템이 막습니다(`BAL_BLOCK`). "다른 앱 위에 표시"가 허용돼 있으면 뜹니다.
- 알림 category를 `navigation`으로 주면 내비 위 헤드업으로 뜨고 버튼(action) 2개도 같이 보입니다. category가 없으면 중요도가 높아도 헤드업으로 뜨지 않았습니다.
- 앱이 내비 뒤에 있어도 포그라운드 서비스가 떠 있으면 엔진이 멈추지 않습니다.
- `CarUxRestrictionsManager` 읽기가 됩니다(P단 정차에서 규제 없음). 도어 "닫힘" 값은 네 문 모두 0입니다.

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
