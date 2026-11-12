# Pleos Connect 개발 규칙

등록일: 2026-11-12. 사용자가 제공한 `pleos-connect-archive.html`의 앱 설계·개발·SDK·등록 가이드를 개발용으로 정리했다. 이 문서의 등록은 기존 코드가 모든 항목을 준수한다는 뜻이 아니다.

## 출처와 적용 방식

- 기준 스냅샷: 2026-11-11 수집 아카이브, 가이드 121개와 SDK API 레퍼런스 790개.
- 원본 SHA-256: `8bde937fcbfae6f119fbb0288e351f41fd0cade90baf625d8f51a7bfa481394f`.
- 아래 `p번호`·`api번호`는 해당 HTML의 article ID다. 로컬 원본이 없으면 출처 표의 공식 URL을 사용한다.
- **원문 필수**는 가이드의 의무·금지·필수 구현 사항이다. **원문 권장**은 권고이며 무조건적인 출시 차단 조건으로 바꾸지 않는다. **프로젝트 기준**은 Smart Journey의 설계·검증 관례이며 Pleos 전체의 규정으로 설명하지 않는다.
- SDK/API·플랫폼·배포 정책이 달라질 수 있으므로, 해당 변경 시 실제 사용하는 버전의 공식 명세·서비스 버전과 대조한다. 아카이브에 없는 권한 승인·앱 분류·배포 허용 여부를 추측하지 않는다.

## 1. 주행 규제와 화면 조작

**원문 필수 - p1·p2·p47**

- `CarUxRestrictionsManager`에서 현재 상태를 읽고 변경 리스너를 등록한다. `isRequiresDistractionOptimization`과 `activeRestrictions`의 해당 기능 플래그를 반영한다.
- DO 선언만으로 모든 기능이 허용되지 않는다. DO 미선언 앱은 주행 중 사용이 제한되며, 선언한 앱도 기능별 제한을 직접 적용한다.
- 지역별 허용 범위는 플랫폼이 전달하는 규제를 기준으로 처리한다. 차종 이름·속도 하나로 규제 상태를 대신 판단하지 않는다.
- 제한된 입력·설정·영상 등은 축소·비활성화한다. 주행 중 시선을 끄는 자동 스크롤·장식 애니메이션·반복 전환을 유도하지 않는다.

**프로젝트 기준**

- 동의·인원·방문지 편집은 제한 상태보다 먼저 노출하지 않는다. 제한 상태를 아직 모르면 제한을 해제한 것으로 취급하지 않는다.
- 규제 해제는 물리적 정차·안전한 주차의 증명이 아니다. 하차와 정차가 필요한 행동의 조건은 별도로 검증한다.
- 현재 헤드업·승인 카드 구현을 플랫폼의 배포 허용 근거로 사용하지 않는다. 음성 전용 기획과 현재 구현이 다르면 구분해 기록한다.

## 2. 창·생명주기·UI

**원문 기준 - p4·p10·p11·p15·p17·p22·p34·p41·p52**

- Driving view·Status bar·GNB와 Application window를 구분한다. 시스템 영역을 임의로 가리거나 앱 안에 복제하지 않는다.
- portrait·landscape 앱 창과 실행 중 크기·위치 변경에 대응한다. 전체 캡처 해상도와 앱의 dp 크기는 다르다. 실제 `screenOrientation` 설정도 함께 확인한다.
- 백그라운드 전환은 프로세스 종료와 다르다. 화면 작업·불필요한 센서·타이머는 정리하고, 필요한 서비스의 실행·중단·복원 정책을 정의한다.
- 텍스트·아이콘·이미지 대비는 4.5:1 이상을 기준으로 확인한다. 주야간 변화에 맞춰 밝기·대비를 조정하고 색만으로 상태를 전달하지 않는다.
- 원문은 문장·단락 3줄 이내, 중요 텍스트 높이 5mm 이상·보조 3mm 이상을 제시한다. Typography의 12sp 최소 권장 및 약 18~20px 가독성 권장을 물리적 mm 기준과 혼동하지 않는다. 실제 기기·밀도로 확인한다.
- 0.25초를 넘는 처리에는 응답 중임을 표시한다. 버튼의 Pressed·Disabled·Loading, 팝업의 주/보조 행동과 배치 변형을 설계한다.
- 알림은 운전자와 관련된 핵심 내용을 간결하게 전달한다. System Notification은 OS 표현을 사용하며 서비스 광고 용도로 사용하지 않는다.
- 입력 필드가 있는 창과 없는 창의 IME insets 처리를 구분한다. 입력 없는 Dialog에 IME resize를 무조건 적용하지 않고, 입력 Activity 전체에 일괄 제외 설정도 하지 않는다.

**프로젝트 기준**

- `PleosTheme.kt`, `Components.kt`의 토큰·원본 아이콘을 우선 재사용한다. 현재 버튼 100/64dp 등의 구현값을 모든 Pleos 앱의 공식 의무 치수라고 쓰지 않는다.
- 넓은/좁은 창, 긴 매장명·숫자·단위, 고정 CTA와 본문 스크롤, 빈 값·로딩·오류·복원 상태를 확인한다.

## 3. SDK·차량 신호·오류

**원문 기준 - p5·p6·p7·p58·p60·p63·p64·p69·p70·p75**

- 차량 하드웨어를 직접 제어하거나 비공개 API로 우회하지 않는다. 허용된 표준 AAOS/Pleos API를 사용하고 필요한 권한만 선언한다.
- Vehicle 호출 전 `checkXxxCapabilities`로 기능·영역·범위·`AccessType`을 확인한다. 읽기와 쓰기 권한을 구분한다. 표준 property도 실제 지원·접근 가능 여부를 확인한다.
- SDK를 초기화한 뒤 사용하고 연결 실패·미지원·권한 부족·주행 제한·빈 결과를 구분한다. 서비스 연결 오류에는 제한된 재시도와 대체 안내를 둔다.
- Vehicle의 `onFailed(Exception)`은 항상 `VehicleException`이라고 가정하지 않는다. 해당 타입인 경우에만 `errorCode`를 읽는다.
- 리스너 해제에는 등록했던 같은 인스턴스를 사용하고, 필요 없으면 SDK를 해제한다. `release()` 후 재사용에는 명세의 재초기화 절차를 따른다.

**프로젝트 기준**

- SDK 라이브러리 버전과 기기 서비스 버전을 별도로 확인한다. 숫자·권한 이름은 설치 AAR와 해당 API 명세로 확인한다.
- 센서 미지원·조회 실패를 0명·문 닫힘 등 실제 관측값으로 단정하지 않는다. Fake 신호와 실제 신호의 출처를 표시한다.

## 4. 내비 연동과 값의 의미

**원문 기준 - p69~p75·api677·api684**

- `requestRoute()`의 정상 반환이나 `onRouteStarted()`만으로 경로 성공을 확정하지 않는다. 탐색 완료·목적지·오류 이벤트를 함께 확인한다.
- 정보 조회는 해당 이벤트 콜백에서 결과를 처리한다. 경유지 도착 콜백은 내비 구현에 따라 달라질 수 있으므로 이벤트 도달을 무조건 보장하지 않는다.
- 경유지는 최대 2개이며 최종 목적지와 구분한다. `requestRouteWithWaypoints()`의 권한은 `NAVI_ROUTE_SEARCH`다.
- `RouteStateInfo.isRouting`은 활성 경로 유무다. 실제 안내 중인지 단독으로 판정하는 값으로 바꾸어 해석하지 않는다.
- `TripInfo.eta`는 초, `distance`는 미터다. 표시·계산·서버 전송 경계의 단위 변환을 명시한다.
- 초기 서비스 연결 실패와 내비 요청 처리 오류를 분리한다. 좌표·POI·경유지 인덱스·현재 내비 상태를 확인한다.

**프로젝트 기준**

- 실제 장소의 이름·주소·좌표·POI를 일치시킨다. 이름만 바꾸어 다른 장소를 실제 목적지처럼 표시하지 않는다.
- 외부 접수 승인은 요청 한 건과 연결하고 중복 실행을 막는다. 사용자 승인·접수 요청·확정 결과를 구분한다.

## 5. 오디오·TTS·STT·Gleo

**원문 필수·권장 - p45·p46·p51·p80~p84·p90~p95·p105~p109**

- 오디오 재생 전 적절한 `AudioAttributes`와 `AudioFocusRequest`로 포커스를 요청하고 획득·상실·일시 상실·duck 변경에 대응한다. 거절됐는데 강제 재생하지 않는다.
- `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`는 동시 재생을 기대하는 컨텍스트에서 적용하는 원칙이다. 앱의 웨이팅 알림을 임의로 내비·긴급 컨텍스트로 격상하지 않는다.
- Suspend 시 오디오를 중지하고 포커스를 반납하며 리소스·상태를 정리한다. Resume 때 자동 포커스 복원을 가정하지 않고 현재 정책에 따라 재요청한다.
- 선행 묵음은 150~200ms 권장이다. 데이터 앞단 패딩 등 원문 우선순위를 따르고, 출력 경로 초기화·포커스 확보를 묵음으로 대체하지 않는다.
- TTS/STT는 초기화·리스너·모드별 앱 등록 순서를 따른다. 서버 기반 요청은 연결 완료 후 실행한다. ON_DEVICE와 SERVER/HYBRID의 인증 요구를 구분한다.
- STT 최종 결과는 `onUpdated(..., completed=true)`다. `onEndedRecognition()`만으로 승인 발화를 확정하지 않는다. `sendAudio()`는 ON_DEVICE 입력이며 마지막 버퍼 뒤 `completeAudioSend()`를 호출한다.
- Gleo 스키마는 `assets/schema.json`에 두고 function별 Intent filter를 구성한다. 스키마 작성·검토와 실제 단말 동작 검증은 별개다.
- App Schema는 최대 3개 action, 고유한 대문자·밑줄 function 이름, 영어 description과 지원 parameter 타입을 따른다. nullable union·임의 JSON Schema 기능을 가정하지 않는다.
- Callback의 `functionName`, `status(success/asking/fail)`, 영어 문장 `message`, 출력 `mode`를 맞춘다. 아카이브의 7초 action timeout을 고려하고 실제 승인·접수 결과 없이 success를 반환하지 않는다.

**프로젝트 기준**

- 질문 재생 완료 후 인식을 시작하고 유효한 대상·인원·행동·질문 ID에만 승인한다. 침묵·모호함·만료·중복 발화는 실행하지 않는다.
- Gleo와 앱 TTS가 중복 안내하지 않도록 출력 소유권을 둔다. SDK 합성 입력 성공을 실제 마이크나 Gleo 전체 흐름 성공으로 설명하지 않는다.

## 6. 개인정보·보안·네트워크

**원문 필수·권장 - p5·p8·p116**

- 정보가 왜 필요한지 설명하고 최소 수집한다. 공유 차량의 사용자 교체·계정 전환을 고려한다. 민감 발화·계정·차량 데이터·비밀 값을 로그에 남기지 않는다.
- 기기 내 민감정보는 SharedPreferences·DB·캐시·임시 사본을 포함해 암호화한다. 내부 저장소라는 이유로 암호화를 생략하지 않는다. 키는 Android Keystore 등 안전한 관리 경로를 사용한다.
- 외부 통신은 HTTPS/WSS 등 암호화 경로, TLS 1.2 이상을 적용한다(TLS 1.3 권장). 인증서를 무조건 신뢰하거나 호스트 검증을 우회하지 않는다.
- 토큰·비밀번호는 URL 경로·쿼리에 넣지 않고 헤더 등 적절한 경로로 전달한다. 비밀 키를 코드·리소스·공개 저장소에 하드코딩하지 않는다.
- 필요한 컴포넌트만 외부에 노출하고 호출자 인증·권한·입력값·URI를 검증한다. 민감한 Intent·Broadcast의 수신자를 제한한다.
- 네트워크 단절·저대역폭·타임아웃에는 제한된 재시도·상태 안내·필요한 캐시를 설계한다. 재시도로 접수 요청을 무조건 중복 실행하지 않는다.
- WebView를 추가할 경우 콘텐츠·이동·로컬 파일·JavaScript 연결 범위를 제한한다. 웹브라우저 앱 전용 추가 요구를 모든 앱의 규칙으로 확대하지 않는다.

**프로젝트 기준**

- 외부 접수 정보와 내비 설정용 좌표를 구분한다. 좌표·이동 경로·VIN을 기본 외부 접수 데이터에 넣지 않는다.
- 전송 목적·항목·수신처·보유 기간·거부 영향·철회 위치를 실제 동작과 맞춘다. 현재 30일 고지는 프로젝트 설정이며 Pleos 공통 보관 기간이 아니다.
- FakeBackend 시연과 실제 서버 저장·파기를 구분한다. 실제 신호 모드가 실제 매장 접수 모드임을 뜻하지 않는다.

## 7. 성능·테스트·배포

**원문 기준 - p9·p111·p113~p116**

- 초기화는 필요한 최소 작업만 수행하고 불필요한 상주·센서 수집·폴링·타이머를 줄인다.
- 아카이브의 성능 기준은 주행 중 내비 앱 CPU 15% 이하·비내비 앱 5% 미만, 정차 중 모든 앱 25% 이하, RAM 400MB 이하다. 대상 환경과 측정 방법·결과 없이 충족한다고 쓰지 않는다.
- 공식 폼 팩터 검증은 별도 창의 에뮬레이터와 Host Display Bridge/FormFactorConsole로 수행한다. 이 가상 디스플레이 절차에는 Headless나 Android Studio 내장 Running Devices를 사용하지 않는다. 일반 adb 캡처를 공식 폼 팩터 검증과 동일하게 쓰지 않는다.
- 화면 겹침·잘림·크기·방향·터치·전환을 확인하고 기기가 여러 개면 serial을 지정한다. 가상 디스플레이 제거가 해당 앱을 종료한다는 점을 고려한다.
- Pleos App Market 자동 업데이트를 전제하지 않는다. 버전 확인 → 안내 → `market://detail?package={packageName}` 이동 → 거절 시 처리를 구현한다.
- 배포·심사 요청마다 앱 버전을 변경한다. 배포 앱에는 정식 서명을 사용하고 디버그 설정·WebView 원격 디버깅·개발 도구를 남기지 않는다.
- 공식 앱명이 외국어인 앱은 등록 시 공식 원문 앱명을 태그에 포함한다. 정확한 적용 조건은 태그 가이드를 따른다.

**프로젝트 기준**

- 관련 단위 테스트·debug/release 빌드·린트를 변경 범위에 맞게 수행한다. 기존 경고·실패·미실행 항목을 별도로 보고한다.
- 실행 코드·데모·기획·검증 완료를 구분한다. unsigned release APK는 빌드 결과이며 제출·배포 완료가 아니다.

## 출처 찾아보기

아래 URL은 제공된 HTML에서 추출한 원문 주소다. 등록 시 온라인 최신 문서와 다시 대조한 목록은 아니다.

| 아카이브 ID | 원문 |
| --- | --- |
| p1 | [주행 안전](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline) |
| p2·p47 | [지역별 규제](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy), [규제 구현](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide) |
| p4·p15 | [앱 구조](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model), [Window Policy](https://document.pleos.ai/docs/connect/guide/docs-design/getting-started/window) |
| p5·p6·p7 | [하드웨어 접근](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/platform-api-architecture), [SDK 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/pleos-connect-sdk-api), [호환성](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability) |
| p8·p116 | [개인정보 설계](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/security-privacy), [앱 보안](https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-security-guide) |
| p9 | [성능·리소스](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/performance-resource-mgmt) |
| p10·p11·p17 | [UI 원칙](https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro), [Display Layout](https://document.pleos.ai/docs/connect/guide/docs-design/getting-started/layout), [Typography](https://document.pleos.ai/docs/connect/guide/docs-design/foundations/typography) |
| p22·p34·p41 | [버튼](https://document.pleos.ai/docs/connect/guide/docs-design/components/actions/basic-button), [팝업](https://document.pleos.ai/docs/connect/guide/docs-design/components/popups/system-popup), [시스템 알림](https://document.pleos.ai/docs/connect/guide/docs-design/components/notification/system-notification) |
| p45·p46·p51 | [오디오 포커스](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/audio-focus-development), [Suspend](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/suspend-mode-audio-focus), [선행 묵음](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/voice-guidance-preroll-silence-guide) |
| p52 | [IME resize](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/ime-window-resize-handling-guide) |
| p58·p60·p63·p64 | [Vehicle 권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission), [Capability](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/check-capability), [Callback](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/callback), [오류](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-error-code) |
| p69·p70·p71·p73·p74·p75 | [Navi 초기화](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/initialization-and-release), [이벤트](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners), [목적지](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations), [경유지](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/waypoint-settings), [경유 경로](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/route-with-waypoints), [오류](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/error-handling) |
| api677·api684 | [RouteStateInfo](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/route-state-info/entry), [TripInfo](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/trip-info/entry) |
| p80·p83·p84 | [TTS 초기화](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/initialization-and-release), [서버 등록](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/app-registration), [재생](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/start-and-stop) |
| p90·p91·p92·p95 | [STT 초기화](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/initialization-and-release), [인식 결과](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/result-listeners), [서버 등록](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/app-registration), [오디오 입력](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/sending-audio-data) |
| p106·p107·p109 | [Gleo App Schema](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/app-capability), [Callback Schema](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/action_callback), [스키마 전달](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/deliver-schema) |
| p111 | [공식 폼 팩터 테스트](https://document.pleos.ai/docs/pleos-only/app-test-guide/form-factor-test-guide) |
| p113·p114·p115 | [앱 태그](https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-tagging-guide), [앱 업데이트](https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-update-guide), [앱 버전](https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-version-management-policy) |
