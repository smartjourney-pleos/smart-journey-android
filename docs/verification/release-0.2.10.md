# 0.2.10 — Gleo 승인 경로 차단 (2026-11-13)

## 왜 바꿨나

0.2.9까지는 Gleo가 상태 조회(`GET_SMART_JOURNEY_STATUS`)로 질문 토큰을 받은 뒤, 응답 함수(`RESPOND_SMART_JOURNEY_PROMPT`)에 그 토큰과 `approve`를 보내면 접수가 실행됐다. 여기에 두 가지 문제가 있었다.

- 토큰이 없거나 맞지 않는 응답이 오면 질문을 다시 묻는 응답을 보내면서 **새 토큰을 같이 돌려줬다.** 모델은 첫 응답이 실패해도 그 토큰으로 다시 호출해, 사용자의 새 발화 없이 접수할 수 있었다. 상태 조회를 반복할 때마다 토큰과 기한도 새로 발급됐다.
- 근본적으로 토큰은 "어느 질문에 대한 호출인지"를 묶는 값일 뿐, 사용자가 실제로 답했다는 증거가 아니다.

## 확인한 근거

- Gleo Interface 가이드(아카이브 p105~p108, 2026-11-11 수집): Gleo Action은 LLM이 만든 function call이다. 앱은 Intent action과 `ACTION_PARAM`(모델이 만든 JSON)만 받는다. 사용자 발화 원문, 발화 시점, 서명된 확인값 같은 독립 증거는 정의돼 있지 않다.
- 설치된 Gleo AI 4.2.0.15(`ai.umos.ambientai`)의 `AppActionViewModel.sendActionCall`을 바이트코드에서 확인했다. Intent에 넣는 값은 action(`<패키지>.<함수>`), `setPackage`, `putExtra("ACTION_PARAM", …)`뿐이다.
- 따라서 모델이 보내는 `decision`이나 토큰, 또는 `hasUserApproved` 같은 값은 사용자 승인의 증거가 될 수 없다.

## 바뀐 동작

- `assets/schema.json`(0.2.10)에는 상태 조회 함수만 남겼다.
- 예전 스키마를 기억한 Gleo가 응답 함수를 불러도 `fail`만 돌려준다. 아무것도 실행하지 않고, 승인에 쓸 수 있는 값도 주지 않는다. 앱은 `ACTION_PARAM`을 읽지 않는다.
- 상태 조회는 토큰을 발급하지 않는다. 질문 기한을 늘리지 않고, 앱의 음성 질문을 대신 맡지 않는다. 예전에는 조회하는 순간 앱 STT가 그 질문을 건너뛰었다. 질문이 기다리는 중이면 "앱이 직접 묻고, Gleo로는 승인하지 않는다"고만 알린다.
- 승인 경로는 두 가지만 남았다: 앱 자체 음성 질문(앱 TTS로 질문을 다 읽은 뒤 앱 STT의 최종 인식 결과)과 정차 중 터치(주행 규제 해제 시, 오버레이 권한이 있으면 지도 위 카드). 엔진의 멱등 접수, 응답 유실 시 "확인 필요" 처리, 내비 취소 판정(0.2.9)은 그대로다.
- 디버그 전용 합성 음성 검증(`SpeechProbeActivity`)이 서비스 STT를 잠시 멈추던 값은 `voiceProbePromptId`로 이름을 바꿨다. 릴리스에서는 설정하는 곳이 없다.
- 지금은 Gleo 승인이 없으므로 Gleo 접수의 지연 결과를 음성으로 안내하는 경로(`LateVoiceResultController`)에 들어오는 값이 없다. 이 코드는 남겨 두었다.

## 검증

- `./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug` 성공. 단위 테스트 87개 실패 0, lint 오류 0.
- `GleoActionHandlerTest` 9개:
  - 응답 호출은 실행 0건이고 토큰을 발급하지 않는다.
  - 상태 조회 → 응답을 반복해도 0건이다.
  - 상태 조회만 반복해도 0건이고 질문 기한이 늘지 않는다.
  - 기한이 지났거나, 다른 질문이거나, 동의를 철회했으면 0건이다.
  - Gleo로 조회한 뒤에도 앱 자체 음성 승인은 한 번만 접수된다.
  - 처리 중인 접수는 승인을 다시 묻지 않는다.
  - 결과 중복 안내를 막는다.
  - callback 형식이 맞다.
  - 스키마에는 상태 조회 함수만 있다.
- 서명된 release APK는 0.2.10(versionCode 12)이고, 기존 Smart Journey 인증서로 서명됐다.
- 에뮬레이터(별도 AVD, 미디어 음량 0)에서 확인한 것:
  - 서명 APK를 설치하자 Gleo가 `package=com.smartjourney.vehicle functionCount=1`로 스키마를 등록했다.
  - 방문을 시작하자 앱 자체 STT 요청이 실행됐다(마이크 입력 없이 요청 진입만 확인).
- 별도 검증자(Codex)가 `GleoActionHandlerTest`·`VoicePromptControllerTest` 16개 통과와 서명 APK(SHA-256 `8c18faba548130ec9b93c0ceb17401f246a32f4b42aeb100a2527669668579fb`)의 서명·스키마를 독립 확인했다.
- 이전 빌드 기록과 구분: 합성 PCM으로 실제 Pleos STT를 시험한 승인·보류 결과와 Gleo 상태 조회 자연어 왕복(0.2.5, Mac `say` → 호스트 마이크)은 이전 빌드 결과이며 0.2.10에서 다시 하지 않았다.

## 미확인

- 사람이 마이크로 Gleo를 부르는 전체 흐름(상태 조회 포함)은 이번에도 실행하지 못했다.
- Gleo 쪽 캐시가 바뀐 스키마를 언제 다시 읽는지는 에뮬레이터 설치 로그로만 확인한다.
- 실차 마이크·소음, 실제 서버·매장 접수는 범위 밖이다.
