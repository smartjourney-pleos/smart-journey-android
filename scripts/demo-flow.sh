#!/bin/sh
# 두 매장(A 맛집 → B 카페) 여정을 처음부터 끝까지 자동으로 돌리고 엔진 로그를 출력한다.
# 앱은 내비 뒤에 있고, 승인은 내비 위 헤드업 알림의 버튼을 화면 좌표로 누른다.
# 좌표는 Pleos Connect Emulator v2.1.0 (2560x1440) 기준이다. 화면이 다르면 아래 두 값을 고친다.
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
D="$(dirname "$0")/demo.sh"
PRIMARY="1445 259"   # 헤드업 알림의 오른쪽(주) 버튼
SINGLE="1280 259"    # 버튼이 하나일 때
USER_ID="$("$ADB" shell am get-current-user | tr -d '\r')"

"$ADB" shell am start --user "$USER_ID" -n com.smartjourney.vehicle/.MainActivity >/dev/null 2>&1; sleep 3
"$D" consent; "$D" mode 1; "$D" stop; "$D" seats 4; "$D" speed 1
"$ADB" logcat -c
"$D" start 2; sleep 14                                                    # 내비에 경로 입력, 내비가 앞으로 나옴
"$D" remaining 20; sleep 6; "$ADB" shell input tap $PRIMARY; sleep 7      # A 맛집 골든타임 → [등록]
"$D" arrive; sleep 1; "$D" alight 3; sleep 16                             # 도착 → 3명 하차 → 5초 뒤 확정
"$D" call; sleep 6; "$ADB" shell input tap $SINGLE; sleep 2               # 점주 호출 → [확인]
"$D" seat; sleep 6                                                        # 착석 → 다음 매장
"$D" board 3; sleep 16; "$ADB" shell input tap $PRIMARY; sleep 8          # 3명 재탑승 → 인원 변경 제안 → [바꾸기]
"$D" drive; "$D" remaining 5; sleep 6; "$ADB" shell input tap $PRIMARY; sleep 7   # B 카페 골든타임 → [등록]
"$D" arrive; sleep 1; "$D" alight 2; sleep 16
"$D" call; sleep 6; "$ADB" shell input tap $SINGLE; sleep 2
"$D" seat; sleep 3
"$ADB" logcat -d -s SJ:V | cut -c32-
