# 0.2.14 검증 기록 (Phase 1~4 고도화 통합: Pleos 준수 · 음성 주행 UX · STOMP 실시간 연동 · 성능/창 대응)

1차 심사 기준 커밋 `0.2.13`(`main` = `8453753`, `versionCode = 15`)은 그대로 유지한 채, `dev` 브랜치에서 4단계 고도화를 진행하고 앱 버전을 `versionCode = 16`, `versionName = "0.2.14"`, `assets/schema.json` `"version": "0.2.14"`로 동기화했다.

## 누적 변경 요약 (0.2.13 → 0.2.14)

| 단계 | PR | 핵심 변경 |
| --- | --- | --- |
| Phase 1 (Pleos 심사·가이드 준수) | PR #10 (`b7d125b`) | `VoiceAudioFocus`(`USAGE_ASSISTANT`/`CONTENT_TYPE_SPEECH`, 거절 차단·상실 중단·반납) + 180ms `AudioTrack` 선행 묵음, Android Keystore AES-256-GCM 기기 저장소·토큰 암호화(`LocalCipher`, 외부 패키지 미추가), `VehicleErrorCode` 분류, `CarUxRestrictions` `activeRestrictions` 플래그 및 `car.disconnect()`, Pleos App Market 업데이트 이동·폴백, 즉시 실행 디스패처 복원 레이스(`_state.drop(1)`) 및 `CancellationException` 전파 수정 |
| Phase 2 (음성·주행 UX 고도화) | PR #11 (`fb35517`) | `VoiceNoticeAnnouncer` 신설(`speechMutex` 직렬화, 활성 질문·Gleo 유예·동의 철회 시 생략), `JourneyEngine.onPromptSpeaking`(15초 상한)·`onPromptSpoken`(TTS 완료 후 10초 응답 창) 분리, `Prompt.Kind`별 `retrySpokenText()`, 골든타임 안내 문구 단축 및 명확 보류 발화 보강(모호 표현 거부 유지), 접수 알림 대기 번호 포함 |
| Phase 3 (서버·실시간 연동 고도화) | PR #12 (`22b8927`) | 외부 의존성 없는 RFC 6455 WebSocket + STOMP 1.2 클라이언트(`StompFrame`, `StompWebSocketClient`, `/ws`, `X-Device-Token` 헤더 인증, `heart-beat` 협상, TLS 1.2+/1.3 + `HTTPS` 호스트명 검증, `awaitCancellation()` 즉시 소켓 해제), `/topic/stores/{id}/queue`·`/topic/waitings/{id}` 실시간 구독 + 3초 REST 자동 폴백 + 지수 백오프 재연결, `ensureToken()` `Mutex` 직렬화, `main` `network_security_config.xml` 평문 차단 및 `SJ_RELEASE_SERVER_URL`(`https://` 강제) 선택 주입 |
| Phase 4 (폼 팩터·성능·배포 준비) | PR #13 | 좁은·분할 창 고정 CTA + 본문 스크롤 보완(`ConsentScreen`·`HomeScreen`·`StatusScreen`), `StoreRow` 백엔드 전환 구독 키(`useServer`) 수정, `PromptCard` `scaledDensity` deprecated 경고 제거, `0.2.14` / `versionCode = 16` / `schema.json` 동기화 및 자동 회귀 테스트(`ReleaseReadinessTest`) 추가 |

## 확인한 것 (2026-11-20)

### 1. 통합 빌드·단위 테스트·린트
- 명령: `./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug --no-daemon`
- **단위 테스트:** **28 suites / 145 tests / 0 failures / 0 errors / 0 skipped** (`ReleaseReadinessTest` 2건 포함)
- **Debug Lint:** **0 errors**, 33 warnings (신규 경고 0건)
- **Kotlin 컴파일 경고:** 0건 (`PromptCard.kt` `scaledDensity` 경고 해소)
- **Release APK 정적 검수 (`app/build/outputs/apk/release/app-release-unsigned.apk`):**
  - `aapt2 dump badging` / `xmltree` 확인: `versionCode=16`, `versionName=0.2.14`, `targetSdkVersion=34`, `debuggable=False`, 디버그 전용 컴포넌트(`DemoReceiver`, `ProbeActivity`, `SpeechProbeActivity`) 미포함, `networkSecurityConfig` 적용 확인.
  - 로컬 환경에 서명 키(`SJ_RELEASE_STORE_FILE`)가 없어 **미서명 APK**로 생성됨(배포 가능한 최종본 아님).

### 2. Pleos Connect Emulator v2.1.0 실기 성능·UI 검증 (`pleos_connect_v2_1`, `emulator-5564`, user 10, `0.2.14` debug)
- **창 영역:** 전체 디스플레이 `2560 × 1440` 중 시스템 영역(Driving view·Status bar·GNB)과 분리된 Application window `1682 × 1210`에서 렌더링 확인.
- **성능 실측 (`pleos-connect-rules.md` 7장 기준 대비):**

| 상태 | 측정 방법 | 실측값 | 가이드 기준 | 판정 |
| --- | --- | --- | --- | --- |
| 정차 활성 화면 (`HomeScreen`) | `dumpsys meminfo` | **TOTAL PSS 92,724 KB (약 90.6 MB)** / RSS 176,984 KB | RAM ≤ 400 MB | 충족 (에뮬레이터 기준) |
| 정차 활성 화면 (`HomeScreen`) | `top -b -n 3 -d 2 -p <pid>` | **0.0% / 0.0% / 0.0%** (4코어 400% 스케일) | 정차 CPU ≤ 25% | 충족 (에뮬레이터 기준) |
| 주행 백그라운드 (`JourneyService` 포그라운드 서비스 + 순정 내비 전경) | `dumpsys meminfo` | **TOTAL PSS 106,536 KB (약 104.0 MB)** / RSS 195,116 KB | RAM ≤ 400 MB | 충족 (에뮬레이터 기준) |
| 주행 백그라운드 (`JourneyService` 포그라운드 서비스 + 순정 내비 전경) | `top -b -n 4 -d 2 -p <pid>` | **0.0% / 1.0% / 0.5% / 3.0%** (평균 1.13%, 4코어 정규화 평균 0.28%) | 주행 중 비내비 CPU < 5% | 충족 (에뮬레이터 기준) |

- **UI·주야간·내비 전환·분할 레이아웃 확인:**
  - `ConsentScreen`(주간): 실제 Vehicle SDK가 `좌석 지원 · 도어 지원 · 주행 상태 지원` 표시, 안내 영역 스크롤 + 하단 `"동의하고 시작"` / `"나중에"` 고정 CTA 잘림 없음 확인.
  - `HomeScreen`(주간 / 야간): `adb shell cmd car_service day-night-mode night` 전환 및 `sensor` 복원 시 주간·야간 테마 대비와 하단 `"방문 시작"` 고정 CTA 정상 표시 확인.
  - `방문 시작` 후 순정 내비(`ai.umos.maps.android.navigation.app/.MainActivity`) 자동 전경 전환 및 `JourneyService`(`isForeground=true`) 백그라운드 유지 확인.
  - `DrivingScreen`("주행 중 조작 제한" 배지·편집 차단) 및 `StatusScreen`(기본 폭 및 데모 패널 분할 축소 상태) 잘림·겹침 없음 확인.
- **에뮬레이터 `user` 빌드 제약 확인:**
  - Pleos Connect Emulator v2.1.0(`crp_avd_x86_64`, `user` 빌드)에는 `hmg_car_service`가 존재하지 않고, `cmd car_service inject-vhal-event`는 `non-user build` 요구로 차단됨을 확인했다.

## 확인하지 못한 것 (미실행 항목)

- **공식 폼 팩터 검증 (`FormFactorConsole` / Host Display Bridge):** 현재 워크스테이션에 공식 `FormFactorConsole` 도구가 설치되어 있지 않아 별도 가상 디스플레이 폼 팩터 전환 테스트는 실행하지 못했다. 위 에뮬레이터 `screencap` 검증은 공식 폼 팩터 검증을 대체하지 않는다(`pleos-connect-rules.md` 7장).
- **정식 서명 Release APK 생성 및 업데이트 설치 검증:** `SJ_RELEASE_STORE_FILE` 서명 키가 없는 환경이므로 `0.2.13 → 0.2.14` 서명 Release 업데이트 설치는 배포 담당 환경에서 서명 키 주입 후 수행해야 한다.
- **실서버(`smart-journey-server`) 라이브 STOMP E2E 및 운영 HTTPS 서버 연동:** Phase 3 STOMP 실시간 구독·폴백은 서버 코드 계약과 동일한 로컬 루프백 소켓 목 서버 단위 테스트로 검증했으며, 실제 기동된 서버·웹 POS와의 라이브 재검증은 별도 수행이 필요하다.
- **실차 주행 환경:** 실차 CPU/RAM, 실제 `CarUxRestrictions` 주행 전환, 실제 마이크 발화 음성/Gleo 대화 흐름은 확인하지 못했다.
