#!/usr/bin/env bash
# Gleo AI ↔ 스마트 저니 음성 재시험 (Mac 스피커 → Mac 마이크 → 에뮬레이터).
# 절차와 확인 결과: docs/verification/gleo-voice-test.md
#
# 사용법:
#   scripts/gleo-voice-test.sh boot      # 전용 AVD를 마이크 허용(-allow-host-audio)으로 부팅하고 디버그 APK 설치
#   scripts/gleo-voice-test.sh status    # "Gleo" → "스마트 저니 상태 알려 줘" (GET_SMART_JOURNEY_STATUS)
#   scripts/gleo-voice-test.sh approve   # 질문을 띄운 뒤 "Gleo" → "스마트 저니 웨이팅 등록해 줘" (RESPOND_SMART_JOURNEY_PROMPT)
#   scripts/gleo-voice-test.sh app-mic   # 앱의 음성 질문에 실제 마이크로 "응" (Gleo 없이 앱 TTS→STT)
#   scripts/gleo-voice-test.sh logs      # 마지막 결과 요약
#   scripts/gleo-voice-test.sh stop      # 에뮬레이터 종료, Mac 음량 복원
#
# 다른 세션의 에뮬레이터를 건드리지 않도록 ADB 서버·포트를 따로 쓴다. 바꾸려면 환경변수로 지정한다.
set -u
export ANDROID_ADB_SERVER_PORT="${ANDROID_ADB_SERVER_PORT:-5041}"
AVD="${AVD:-SmartJourney_V4_QA}"
PORT="${PORT:-5680}"
SERIAL="emulator-$PORT"
USER_ID=10
PKG=com.smartjourney.vehicle
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
APK="${APK:-app/build/outputs/apk/debug/app-debug.apk}"
VOICE="${VOICE:-Yuna}"          # macOS 한국어 음성
VOLUME="${VOLUME:-70}"          # 재생하는 동안의 Mac 출력 음량
STATE=/tmp/sj-gleo-volume

a() { adb -s "$SERIAL" "$@"; }
demo() { a shell am broadcast --user $USER_ID -n $PKG/.debug.DemoReceiver --es cmd "$@" >/dev/null; }
speak() { say -v "$VOICE" "$1"; }

volume_up() {
  # 음소거 상태면 say가 마이크에 들리지 않는다. 원래 값을 저장했다가 stop에서 되돌린다.
  [ -f "$STATE" ] || osascript -e 'get volume settings' > "$STATE"
  osascript -e "set volume output volume $VOLUME without output muted"
}

wake() {
  # 직전 대화가 끝나는 중이면 호출어를 놓칠 수 있어 몇 번 다시 부른다.
  for _ in 1 2 3 4; do
    a logcat -c
    speak "글레오"
    sleep 3
    a logcat -d | grep -q '"asrText":"글레' && return 0
    sleep 3
  done
  echo "Gleo가 호출어에 반응하지 않았어요 (마이크·음량·-allow-host-audio 확인)" >&2
  return 1
}

summary() {
  a logcat -d | grep -E "SJ |asrText|sendActionCall|GleoAction|processAppActionResponse|AGENTCLASSIFIER" \
    | grep -v EventListener | cut -c19- | cut -c1-240 | tail -"${1:-15}"
  echo "--- Gleo 음성 응답:"
  a logcat -d | grep -oE "delta=[^,]*" | sed 's/delta=//' | tr -d '\n'; echo
}

wait_for() { # $1 = logcat 패턴, $2 = 초
  for _ in $(seq 1 $(( ${2:-30} * 2 ))); do a logcat -d | grep -qE "$1" && return 0; sleep 0.5; done
  return 1
}

start_visit() {
  a shell am start --user $USER_ID -n $PKG/.MainActivity >/dev/null 2>&1; sleep 3
  demo consent; sleep 1
  demo start --ei n 1
  wait_for "골든타임" 40 || { echo "질문이 나오지 않았어요"; return 1; }
}

case "${1:-}" in
  boot)
    "$SDK/emulator/emulator" -avd "$AVD" -port "$PORT" -no-snapshot-save -no-boot-anim -allow-host-audio >/dev/null 2>&1 &
    until [ "$(a shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 4; done
    sleep 15
    a install -r -g "$APK" | tail -1
    sleep 15   # Gleo가 앱의 assets/schema.json을 다시 읽는다
    a logcat -d | grep "setAppCapability | converted | package=$PKG" | tail -1 | cut -c19-
    ;;
  status)
    volume_up; wake || exit 1
    speak "스마트 저니 상태 알려 줘"
    wait_for "GleoAction: GET" 25; sleep 8; summary
    ;;
  approve)
    volume_up
    start_visit || exit 1
    sleep 11   # 앱 자체 TTS(약 3초)와 STT(8초)가 끝난 뒤 Gleo를 부른다. 질문은 60초 뒤 만료된다.
    wake || exit 1
    speak "스마트 저니 웨이팅 등록해 줘"
    wait_for "GleoAction: RESPOND" 25; sleep 8; summary
    ;;
  app-mic)
    volume_up
    a shell am start --user $USER_ID -n $PKG/.MainActivity >/dev/null 2>&1; sleep 3
    a logcat -c; demo consent; demo start --ei n 1
    wait_for 'SpeechToText: \[main\]request' 40 && { sleep 0.7; speak "응"; }
    sleep 9; summary
    ;;
  logs) summary 30 ;;
  stop)
    a emu kill >/dev/null 2>&1; adb kill-server
    if [ -f "$STATE" ]; then
      vol=$(sed -E 's/output volume:([0-9]+).*/\1/' "$STATE"); muted=$(grep -o 'output muted:[a-z]*' "$STATE" | cut -d: -f2)
      osascript -e "set volume output volume $vol"; [ "$muted" = true ] && osascript -e 'set volume with output muted'
      rm -f "$STATE"
    fi
    ;;
  *) sed -n '2,13p' "$0"; exit 2 ;;
esac
