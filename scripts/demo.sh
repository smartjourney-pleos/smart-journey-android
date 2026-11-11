#!/bin/sh
# 데모 패널과 같은 조작을 터미널에서 한다. (디버그 빌드 전용, app/src/debug/.../DemoReceiver.kt)
#
#   scripts/demo.sh <명령> [값]
#
#   consent            데이터 전송 동의
#   start <매장 수>     여정 시작 (앱 화면이 떠 있을 때만. 탑승 인원은 현재 좌석 수)
#   prompt             지금 웨이팅 걸기 카드 띄우기
#   approve | defer    카드의 주 버튼 / 보조 버튼
#   drive | stop       주행 시작 / 정차
#   remaining <분>      남은 시간 바꾸기
#   speed <초>          1초에 줄어드는 초 (1=실시간, 60=1분/초)
#   arrive             도착 이벤트
#   seats <명>          탑승 인원
#   door <1|0>         문 열림 / 닫힘
#   alight <명>         하차 장면 (정차 → 문 열림 → n명 하차 → 문 닫힘)
#   board <명>          재탑승 장면 (문 열림 → 탑승 n명 → 문 닫힘)
#   walkin <±팀>        매장 현장 대기 팀 수 바꾸기
#   call | seat        점주 호출 / 착석 처리
#   arrived            수동 도착("도착했어요")
#   mode <1|0>         데모 신호 / 실제 차량 신호
#   end                여정 끝내기
#   state              현재 상태를 로그로 (adb logcat -s SJ SJ-DEMO)
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
USER_ID="$("$ADB" shell am get-current-user | tr -d '\r')"
set -- "$1" ${2:+--ei n "$2"}
"$ADB" shell am broadcast --user "$USER_ID" -n com.smartjourney.vehicle/.debug.DemoReceiver --es cmd "$@" >/dev/null
