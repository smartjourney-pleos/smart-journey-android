# Gleo AI 음성 연동 재시험 기록 (2026-11-12)

다음에 같은 시험을 반복할 때는 `scripts/gleo-voice-test.sh`를 쓴다. 사람이 말하는 대신 Mac의 `say`가 Mac 스피커로 말하고, Mac 마이크로 에뮬레이터에 들어간다.

## 준비

1. **에뮬레이터는 `-allow-host-audio`로 띄운다.** 이 옵션이 없으면 에뮬레이터가 마이크 입력을 0(무음)으로 채운다. `adb emu avd hostmicon`만으로는 부족했다.
2. **Mac 소리를 켠다.** 음소거면 `say`가 마이크에 들리지 않는다. 스크립트가 재생하는 동안만 음량을 70으로 올리고 `stop`에서 원래대로 돌린다.
3. macOS 설정 → 개인정보 보호 및 보안 → 마이크에서 에뮬레이터를 띄운 앱(Orca·터미널)이 허용돼 있어야 한다(2026-11-12 Orca 허용 확인).
4. 전용 AVD `SmartJourney_V4_QA`, ADB 서버 5041, `emulator-5680`을 쓴다. 다른 세션의 기기는 건드리지 않는다.

```bash
./gradlew :app:assembleDebug
scripts/gleo-voice-test.sh boot       # 부팅 + 설치 + Gleo 스키마 재등록 확인
scripts/gleo-voice-test.sh app-mic    # 앱 질문 → 실제 마이크 "응" → 데모 접수
scripts/gleo-voice-test.sh status     # Gleo → GET_SMART_JOURNEY_STATUS
scripts/gleo-voice-test.sh approve    # Gleo → RESPOND_SMART_JOURNEY_PROMPT (아래 '미확인' 참고)
scripts/gleo-voice-test.sh stop
```

## 확인한 것 (Gleo AI 4.2.0.15, Pleos Connect Emulator 2.1.0)

| 항목 | 결과 |
| --- | --- |
| 앱 음성 질문 + 실제 마이크 | 앱 TTS 질문 뒤 Mac 스피커 "응" → 온디바이스 STT 인식 → 데모 접수(번호 11). 합성 PCM이 아닌 마이크 입력 |
| Gleo 호출어 | "글레오"로 깨어남. 직전 대화가 끝나는 중에는 놓치는 경우가 있어 스크립트가 최대 4번 다시 부른다 |
| Gleo → 앱 호출 | "스마트 저니 상태 알려 줘"(인식: "스마트 전의 상태 알려줘.") → 액션 플래너가 `com.smartjourney.vehicle.GET_SMART_JOURNEY_STATUS` 선택 → 앱 수신기 실행 → `success`/`asking` 응답 → Gleo가 응답을 받아 음성으로 안내 |
| Gleo가 질문 읽기 | `asking`에 담은 질문을 그대로 읽음: "42dot까지 20분, 대기는 30분이에요. 4명으로 지금 웨이팅을 걸까요?" 읽은 뒤 약 5초 듣기 상태 |

## 찾아서 고친 문제

1. **Gleo 호출이 앱에 닿지 않음.** Gleo는 인텐트 액션을 `<패키지명>.<function 이름>`(`com.smartjourney.vehicle.GET_SMART_JOURNEY_STATUS`)으로 만들어 `setPackage` + `sendBroadcast`한다(Gleo 앱 `AppActionViewModel.sendActionCall` 디컴파일 확인). Pleos 문서 예시는 이름만 쓰므로 수신기가 이름만 받고 있었다 → 두 형태를 모두 받도록 수정.
2. **Gleo가 질문 ID를 지어냄.** 승인 요청에 실제 ID 대신 `42dot_waiting_prompt`를 넣어 앱이 거절했고, Gleo는 "할 수 없다"고 답했다 → 스키마에서 질문 ID를 빼고, 앱이 Gleo로 읽어 준 질문을 기억해 그 질문이 아직 유효할 때만 적용한다. 읽어 준 뒤 질문이 바뀌었으면 새 질문을 다시 읽게 한다.
3. **짧은 후속 답이 일상 대화로 빠짐.** 질문을 읽은 뒤 "네"라고 하면 Gleo가 `cloud_call_chitchat_agent`로 분류해 "알겠습니다"라고만 답하고 앱 함수를 부르지 않았다(실제로는 아무것도 실행되지 않음). 앱에서 바꿀 수 없는 Gleo 동작이다 → 앱 이름을 넣은 한 문장("스마트 저니 웨이팅 등록해 줘")으로 바로 승인하도록 하고, Gleo가 질문을 읽을 때 그렇게 답하라고 안내하게 했다.

## 미확인 (다음 시험에서 볼 것)

- **"스마트 저니 웨이팅 등록해 줘"로 승인되는지.** 3번 수정 뒤 시험에서 Gleo가 호출어에 연속으로 반응하지 않아 확인하지 못했다. `scripts/gleo-voice-test.sh approve` → 로그에 `GleoAction: RESPOND_SMART_JOURNEY_PROMPT → success`와 `SJ : 접수 완료`가 나오면 통과.
- 거절 경로: "스마트 저니 나중에" → `defer`.
- 합성 음성의 "걸어 줘"는 온디바이스·서버 인식 모두 "그렇죠"로 자주 들렸다. 사람 목소리로 다시 확인한다.
- 사람이 직접 말하는 시험, 차내 소음, 실차 Gleo 버전에서의 액션 이름 형식.
- Gleo 흐름(호출어 → 명령 → 질문 읽기 → 답)에 30~40초가 걸려 질문 만료(60초)에 가깝다. 실사용에서 부족하면 Gleo가 질문을 맡은 동안 만료를 늘리는 것을 검토한다.

## 개인정보

디버그 빌드에서만 `GleoAction: <함수> → <상태>` 한 줄을 남긴다. 발화 원문과 질문 내용은 앱 로그에 남기지 않는다(Gleo 자체 로그는 Gleo 앱의 동작이다).

## 0.2.6 보안 보완 (재시험 필요)

- Gleo 승인은 사용자가 실제로 들은 질문에만 적용한다: Gleo가 `asking`으로 읽어 준 질문이거나 앱 음성으로 끝까지 읽은 질문. 아니면 실행하지 않고 질문을 읽게 한다. 승인을 처리하면 이 기록을 지운다.
- Gleo에 보내는 지시문은 고정 문장으로 바꾸고, 질문(매장 이름 포함)은 `data.question`으로만 넘긴다(제어 문자 제거, 200자 제한).
- 이 변경 뒤 Gleo 흐름은 에뮬레이터에서 다시 확인하지 못했다. `scripts/gleo-voice-test.sh status`와 `approve`로 확인한다.
- 보안 검토의 남은 의견(다음 작업): ① "질문을 읽어 줬다"는 근거가 앱이 Gleo에 질문을 넘긴 사실에 기대므로, Gleo 모델이 사용자 발화 없이 승인을 연달아 보내면 막지 못한다. Gleo가 질문 ID를 정확히 돌려주지 못해 1회용 확인값은 아직 쓸 수 없다. ② 앱이 읽은 질문 기록은 같은 질문이 유효할 때만 쓰이지만, 듣기 시간이 끝나면 지우는 짧은 유효 시간을 두는 것을 검토한다.
- 0.2.6 재시험은 스크립트의 `boot`가 끝나면서 에뮬레이터가 같이 종료돼 진행하지 못했다. `nohup`·`disown`으로 고쳤다.
