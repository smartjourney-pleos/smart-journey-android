# Pleos Connect 디자인 적용

사용자 Figma 파일의 컴포넌트·변수·원본 아이콘을 Jetpack Compose로 옮겼다. 다른 세션의 기능 작업은 `prototype`, 디자인 변경은 `pleos-design` 워크트리에 분리했다.

## 원본과 구현

| 원본 | 적용 |
| --- | --- |
| [Colors](https://www.figma.com/design/Qc0CvEITX6nhlFIGDx8pzf?node-id=67-14834) | `PleosTheme.kt`: background, surface, text, divider, button 주간·야간 토큰 |
| [Typography](https://www.figma.com/design/Qc0CvEITX6nhlFIGDx8pzf?node-id=67-11477) | Asta Sans, headline 60/80·48/64, title 32/40, body 28/36, label 24/28·20/24 |
| [Basic Button](https://www.figma.com/design/Qc0CvEITX6nhlFIGDx8pzf?node-id=67-22525) | large 높이100·모서리32·레이블32/40, small 높이64·모서리16·레이블26/34, 비활성 테두리2 |
| [Stepper](https://www.figma.com/design/Qc0CvEITX6nhlFIGDx8pzf?node-id=67-25154) | 420×100, 모서리24, 64dp 터치 영역, 44dp 원본 plus/minus 아이콘 |
| [Page Navigation](https://www.figma.com/design/Qc0CvEITX6nhlFIGDx8pzf?node-id=67-27600) | 고정 헤더·앱 아이콘 슬롯·40dp 여백·하단 구분선 |
| [System Popup](https://www.figma.com/design/Qc0CvEITX6nhlFIGDx8pzf?node-id=67-29503) | 중앙 제목·설명·같은 너비의 승인 버튼, 좁은 창에서는 세로 버튼 |
| [Display application](https://www.figma.com/design/lXfu5afRmeGHAXC9MbnvWi?node-id=2124-7111) | 앱 영역 배경. OS의 상태바·GNB·창 테두리는 시스템에 맡김 |

Asta Sans 원본은 [Google Fonts의 Asta Sans](https://github.com/google/fonts/tree/main/ofl/astasans)이며 OFL 라이선스를 `app/src/main/assets/licenses/`에 포함했다. 가변 폰트의 `wght`를 명시해 regular·bold·extra bold가 실제 렌더링에서도 구분되게 했다.

Figma SVG 원본은 `app/src/main/assets/pleos/`에 보관했다. Android에서 바로 읽도록 같은 SVG를 3배 해상도 PNG로 렌더링해 `drawable-nodpi/`에 넣었다. 아이콘은 원본 44dp 크기로 표시하며 임시 Figma URL에 의존하지 않는다. 앱 아이콘 슬롯에는 기존 스마트 저니 아이콘을 사용한다.

## 화면

- 동의: 정보 항목을 짧은 레이블로 보여주고 주요·보조 행동을 구분한다.
- 준비: 넓은 앱 창은 인원·방문지 두 열, 좁은 창은 한 열. 스크롤 영역과 하단 여정 시작 버튼을 분리했다.
- 방문지: 다중 선택 체크박스 의미와 방문 순서, 선택 개수를 함께 제공한다.
- 진행: 세 지표가 가용 폭을 나누고 숫자·단위의 위계를 구분한다.
- 승인: 가로·세로 앱 창과 주야간 모드를 지원한다.
- 데모: 기본적으로 접혀 있고 헤더에서 연다. 기존 기능은 유지한다.

## 검증

2026-11-11, 별도 Pleos AVD `SmartJourney_Design_QA` (`emulator-5556`)에서 확인했다.

- `assembleDebug`, `assembleRelease`, `testDebugUnitTest` (19개), `lintDebug` 성공. 린트는 기존 SDK·의존성·매니페스트 경고만 있으며 오류는 없다.
- 가로 1682dp·세로 829dp 앱 창에서 주간/야간, 본문 스크롤·하단 고정 버튼, 원본 아이콘 크기 확인.
- 인원 4→5→4, 방문지 0→3 선택, 선택 전후 시작 버튼 활성화, 여정 시작·진행 화면, 승인 카드의 가로/세로 버튼 배치 확인.
- 승인 카드 검증은 임시 QA APK에서만 디버그 리시버 권한을 풀어 데모 신호를 주입했다. 소스 매니페스트는 원상 복원했고 최종 APK는 보호된 매니페스트로 다시 빌드했다.
- 차량 SDK·엔진·서비스·권한 정책은 이 디자인 변경 범위에 포함하지 않았다.

캡처 및 빌드 로그: 작업 호스트 `/tmp/sj-pleos-design/`. 최종 설치 APK: `app/build/outputs/apk/debug/app-debug.apk`.

## 2026-11-12 프론트엔드 보완

> 이 절은 개정 기획(v4) 이전 화면(준비·진행·잠금)에 대한 기록이다. 0.2.x에서 화면을 다시 구성하면서 시작 버튼 상태("내비 연결 중…"), 인원 제안 표기, 시연용 데이터 고지, 좁은 창 배치는 새 화면 코드로 대체됐다. 현재 동작은 [README](../../README.md)와 [0.2.9 검증 기록](../verification/release-0.2.9.md)을 따른다.

- 화면 분기: 주행 규제가 동의보다 우선한다. 규제+여정 → 주행 화면, 규제+여정 없음 → 잠금 화면, 그다음 동의·준비·진행.
- 준비: 시작 버튼 로딩 스피너(`BigButton(loading)`), 비활성 사유(인원 확인 중·방문지 미선택), 누른 순서 = 방문 순서 안내, 센서 인원은 "제안값"으로 표기. 경로 입력 실패는 예외 메시지 대신 조치 문구를 보이고 원인은 디버그 빌드에서만 로그(`SJ`)에 남긴다.
- 데모 고지: 백엔드는 차량 신호 모드와 무관하게 항상 `FakeBackend`라 준비·진행 화면에 "앱 안의 시연용 데이터, 실제 매장 미접수"를 고정 표시한다.
- 진행: 지표 영역이 600dp 미만이면 세 지표를 세로로 쌓는다.
- 동의: 목적·거부 시 영향을 명시하고, 철회 위치를 실제 준비 화면 하단 링크로 고쳤다.
- 하차: 하차 인원은 센서 추정치("약 N명"), 도착은 앱 안의 시연용 도착 기록으로 표기(서버 통신 아님). `ArrivalResult`·상태 머신은 그대로다.
- 헤더·주행 화면: 앱 창이 800dp 미만이면 주행 규제 배지를 헤더 둘째 줄로 내려 앱명·부제가 잘리지 않게 했다. 주행 화면 상태 문구는 패널 안쪽 폭 600dp 미만에서 Display(60) 대신 Headline(48)을 쓴다. 829dp·1682dp 창의 기본 배치는 그대로다.
- 검증: debug/release 빌드·단위 테스트(30개)·린트 오류 0건. 코디네이터가 별도 AVD `SmartJourney_Design_QA`(`emulator-5580`)에서 동의·준비·진행·미동의 주행 잠금을 확인하고, 최종 APK의 600dp 주행 화면에서 헤더·본문 줄바꿈 수정을 재확인했다. 빠른 창 크기·테마 변경 때 에뮬레이터에 일시적인 미갱신 영역이 관찰되어 모든 전환의 시각 품질이 검증된 것은 아니다. [검증 범위와 캡처](../verification/frontend-2026-11-12.md)에 기록했다. 색은 기존 Palette 토큰만 썼다(Orange 주간 `#9A4000`, 야간 `#FFB580`).

## 화면 캡처

- [주간 준비 화면](screenshots/setup-day.png)
- [야간 세로 창](screenshots/setup-night-portrait.png)
- [야간 승인 카드](screenshots/approval-night-portrait.png)
