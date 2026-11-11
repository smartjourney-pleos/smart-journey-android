# smart-journey-android

스마트 저니(Smart Journey)의 Pleos Connect 차량용 안드로이드 앱입니다.
프로젝트 소개와 전체 구조는 [smart-journey-main](https://github.com/smartjourney-pleos/smart-journey-main)을 참고하세요.

## 기술 스택

Kotlin 2.0, Jetpack Compose, Coroutines/Flow, Pleos Vehicle SDK 2.3.2, Pleos NaviHelper SDK 3.0.5

- `minSdk 28`, `targetSdk 34`, `compileSdk 35`, JDK 17 이상 (JDK 21 사용 중)
- 에뮬레이터/차량의 Vehicle 서비스 **2.3.13 이상**, Navi 서비스 **2.0.1 이상** 필요

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

승인 카드는 기본으로 **내비 위 헤드업 알림**(제목·내용·버튼 2개)으로 뜹니다. 앱 화면이 보이는 중이면 카드 화면이 바로 뜹니다.
백그라운드에서도 카드 화면을 바로 띄우려면 "다른 앱 위에 표시"를 허용해야 합니다(시연용):

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

선언한 권한: `CAR_SEATS`, `CAR_DOORS`, `NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`(+`SPECIAL_USE`), `USE_FULL_SCREEN_INTENT`, `SYSTEM_ALERT_WINDOW`(선택).

**에뮬레이터에서 확인한 것 (11/11)**
- 백그라운드에서 서비스가 화면을 직접 띄우면 시스템이 막습니다(`BAL_BLOCK`). "다른 앱 위에 표시"가 허용돼 있으면 뜹니다.
- 알림 category를 `navigation`으로 주면 내비 위 헤드업으로 뜨고 버튼(action) 2개도 같이 보입니다. category가 없으면 중요도가 높아도 헤드업으로 뜨지 않았습니다.
- 앱이 내비 뒤에 있어도 포그라운드 서비스가 떠 있으면 엔진이 멈추지 않습니다.
- `CarUxRestrictionsManager` 읽기가 됩니다(P단 정차에서 규제 없음). 도어 "닫힘" 값은 네 문 모두 0입니다.

## 빌드와 실행

Android Studio에서 이 폴더를 열면 됩니다. 터미널에서는 다음을 사용합니다.

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # 단위 테스트 (골든타임, 하차 확인, 엔진 흐름)
```

`local.properties`(Android SDK 경로)는 커밋하지 않으므로 처음 한 번 `sdk.dir=<SDK 경로>`를 직접 만들거나 Android Studio가 만들게 하세요.
Pleos Connect Emulator 설정은 Pleos 공식 문서(Getting Started)를 따릅니다. 에뮬레이터에서 로컬 서버 주소는 `http://10.0.2.2:8080`입니다.

## 브랜치 규칙

| 브랜치 | 용도 |
| --- | --- |
| `main` | 제출·심사용 안정 버전 |
| `dev` | 개발 통합 |
| `feature/기능명` | 기능 작업, `dev`로 PR |

공개 저장소이므로 API 키, 서명 키(`*.keystore`, `*.jks`), `local.properties`는 커밋하지 않습니다.
