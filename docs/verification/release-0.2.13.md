# 0.2.13 검증 기록 (순정 내비 자동 전환 · 팝업 깜빡임 수정)

0.2.12(`88652da`) 대비 버전 표기(`versionCode = 15`, `versionName = "0.2.13"`, `assets/schema.json` version `"0.2.13"`)를 올리고, 콜드부팅 직후 순정 내비 자동 전환 실패와 1초 단위 팝업 깜빡임 원인을 수정했다.

## 수정 내용

| 항목 | 원인 | 수정 |
| --- | --- | --- |
| 순정 내비 자동 전환 | 기존에는 경로 설정 성공 뒤에야 내비 화면 전환(`showNavigation`)을 호출했으나, 부팅 직후 지도가 `MapComplete`에 도달하지 않은 순정 내비는 SDK 화면 요청과 `requestRoute`를 무시했다 | `NaviGateway.setRouteAndShowNavigation` 순서를 `showNavigation()` → 읽기 전용 `awaitReady(dest)`(`requestTrip` 새 응답 확인) → `setRoute()` 1회 호출로 변경. `PleosNaviGateway.showNavigation`은 공개 LAUNCHER 진입점(`ai.umos.maps.android.navigation.app`)으로 지도를 깨운 뒤 SDK 화면 전환을 요청(`AndroidManifest.xml` `<queries>` 추가) |
| 목적지 확인 보정 | 순정 내비 엔진이 POI 건물 좌표를 약 22m 떨어진 차량 진입점 좌표로 보정해 돌려줄 때 좌표 허용 오차(`0.0001`) 비교가 실패(`confirmed=false`)했다 | `destinationMatches`를 추가해 `poiId`·정규화 `poiSubId`를 우선 비교하고, `poiId`가 비어 있을 때만 좌표를 비교하도록 수정 |
| 실패 시 데모 폴백 제거 | `AppGraph.startJourneyOnce`가 `demo.enabled`일 때 `signals.setRoute` 실패를 무시하고 데모로 진행했다 | 경로 설정·전환 실패 시 `JourneyService`를 중지하고 오류를 반환하도록 일원화 |
| 팝업 깜빡임 | `JourneyService`가 매초 바뀌는 `JourneyState.remainingMin`까지 그대로 `popup.render()`로 전달해 1초마다 오버레이 창을 닫고 재생성했다 | `PopupState` / `popupStates(...).distinctUntilChanged()`를 도입해 질문·데모·주행규제·창영역·화면표시·동의가 바뀔 때만 `popup.render()` 호출 |

## 확인한 것 (2026-11-14)

- **단위 테스트·빌드·린트**: `./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug` 성공. 단위 테스트 **97개** 통과(실패 0, 건너뜀 0 — `NaviDisplayHandoffTest` 6개, `DestinationConfirmationTest` 4개, `PopupStateTest` 3개 포함). `lintDebug` 오류 0개, 경고 39개(기존 수준).
- **서명 확인**: `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk` 결과 인증서 SHA-256이 기존 0.2.12 release와 동일(`f888bfaf40b5b0eb766d6eba8ad99dbd932c37eaad1fda992e981e756813d111`).
- **콜드부팅 에뮬레이터 실제 흐름 (`emulator-5554`, `SmartJourney_Pleos`, `0.2.13` debug)**:
  - 부팅 후 순정 내비 앱을 수동으로 열지 않은 상태에서 `동의하고 시작 → 42dot 선택(4명) → 방문 시작` 실행.
  - `requestNavigationScreen` → `requestTrip` 응답(`eta=5s distance=49m`) → `requestRoute` → `onRouteStarted` → `route state and destination confirmed=true` 확인, `topResumedActivity`가 `ai.umos.maps.android.navigation.app/.MainActivity`로 자동 전환되고 `42dot` 경로 안내가 시작됨을 확인.
  - `골든타임 · 42dot 대기 30분, 도착 20분 후` 구간에서 `Pleos TTS` 음성 안내(`speaker=seoyoon locale=ko_KR`) 후 `STT`가 열렸고, 내비 화면 위 `SmartJourney waiting popup` 창(`Window{bcaf807 u10 SmartJourney waiting popup}`)이 10초 만료까지 **단 1회만 생성·유지**(매초 재생성 없음)됨을 `dumpsys window windows` 및 logcat으로 확인.
- **서명 release 업데이트 설치 및 실제 SDK 실행 검증 (`emulator-5554`, `SmartJourney_Pleos`, `0.2.13` release)**:
  - 기존 `0.2.12` 서명 release 위에 `0.2.13` 서명 release(`app-release.apk`)를 `adb install -r`로 업데이트 설치하고, `dumpsys package`로 `versionName=0.2.13`, `versionCode=15`, `DEBUGGABLE` 플래그 부재, 디버그 전용 컴포넌트(`DemoReceiver`, `ProbeActivity`, `SpeechProbeActivity`) 부재를 확인.
  - `pm clear --user 10` 후 `POST_NOTIFICATIONS`·`SYSTEM_ALERT_WINDOW` 권한 부여 상태에서 실제 SDK 신호만으로 전체 정차→내비 전환→질문→터치 승인 흐름을 실행:
    1. `동의하고 시작` 후 홈 화면에 `데모` 버튼이 없고 실제 Vehicle SDK가 `4명` 제안(`실제 · 좌석` 배지)을 표시함.
    2. `○ 42dot 선택 → 방문 시작` 후 `requestNavigationScreen` → `requestTrip` → `onTripInfo` → `requestRoute` 순서로 순정 내비(`ai.umos.maps.android.navigation.app/.MainActivity`)가 자동 활성화되고 `1분 · 50m` 42dot 경로 안내 시작.
    3. `도착 1분 후` vs `대기 30분` 비교로 `지금 42dot 웨이팅 걸까요?` 팝업(`Window{cbe055b u10 SmartJourney waiting popup}`)이 만료까지 10초 동안 **단 1개의 창 토큰으로 깜빡임 없이** 표시·유지됨(0.5초 간격 20회 샘플링 확인).
    4. 첫 질문 무응답 만료 후 자동 접수되지 않음을 `방문 현황`에서 확인(`요청 전`, `실제 · 내비 도착 예상`, `실제 · 좌석·도어` 배지), 이어 `접수 여부 확인 → 걸기` 터치 승인으로 `4명 접수 확정 · 번호 11 (데모 접수, 실제 매장 미접수)` 확정.
    5. `방문 끝내기` 시 `방문 안내를 종료할까요?` 확인창(`계속 안내` / `안내 종료`) 표시 및 `계속 안내` 선택 후 확정 유지 확인.

## 확인하지 못한 것

- `0.2.13` release는 debug 도구가 없어 하차·매장 호출·착석(`DemoReceiver` / `FakeBackend` 조작) 및 debug 로컬 서버 연동 전체 시나리오, 그리고 실제 마이크 발화 승인은 재실행하지 않았다. (하차·호출·착석 및 로컬 서버 연동의 마지막 전체 확인은 0.2.11 debug 기준이다.)
- 실차 주행 환경의 내비 준비 시간, 실제 차량 좌석·도어 변화, 공식 FormFactorConsole 검증은 확인하지 않았다.
