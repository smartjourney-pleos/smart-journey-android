# 차량 앱 ↔ smart-journey-server 연동

2026-11-13 갱신 (앱 0.2.11). 서버 계약 원본은 서버 저장소 `docs/server/02_기능_API_명세.md`와 Swagger(`/swagger-ui.html`)다.

## 현재 상태

- 앱: debug 빌드의 데모 패널 **접수 백엔드** 스위치(또는 `DemoReceiver --es cmd server --ei n 1`)로 켠다. 기본은 앱 안 `FakeBackend`. 주소는 `BuildConfig.SERVER_URL`(debug `http://10.0.2.2:8080`, release 비어 있음 = 서버 미사용). 방문 중에는 바꿀 수 없다.
- 서버 `main`(Phase 1~6, smart-journey-server#1)에서 아래 API가 모두 동작한다. 0.2.11은 고지 버전만 서버와 맞췄다(`CONSENT_VERSION = 2026-11-v1`).
- 매장 조작(현장 대기 증감·호출·착석·노쇼)은 웹 POS(smart-journey-web)에서 한다. 앱 데모 패널의 현장 대기·호출·착석 버튼은 `FakeBackend` 전용이다.
- 데모 매장은 앱·서버·웹 모두 42dot(1)·소프트웨어드림센터(2)다(서버 시드 smart-journey-server#2).

## 앱이 쓰는 API

| 순서 | 호출 | 앱 처리 |
| --- | --- | --- |
| 1 | `GET /api/v1/consent/notice` → `POST /api/v1/devices {consentVersion}` | 앱에서 동의한 경우에만. 서버 고지 `version`이 `CONSENT_VERSION`과 다르면 등록하지 않는다. `deviceToken`은 `X-Device-Token` 헤더로만 보내고 메모리에만 둔다. 401이면 다시 등록 |
| 2 | `GET /api/v1/stores` | 매장 이름·주소·좌표·POI로 내비 경로 입력 |
| 3 | `GET /api/v1/stores/{id}/queue-status` (3초 조회) | `estimatedWaitMin`·`arrivalBufferMin`으로 질문 시점 계산 |
| 4 | `POST /api/v1/journeys {partySize, partySource, storeIds}` | 방문 시작 때. `ACTIVE_JOURNEY_EXISTS`면 `GET /journeys/current` → `POST /journeys/{id}/end` 후 다시 생성 |
| 5 | `POST /api/v1/waitings` + `Idempotency-Key: <질문 ID>` | 승인 1회당 1번. 400·403·404는 접수 거절, 409·5xx·타임아웃은 확인 필요(자동 재요청 없음) |
| 6 | `GET /api/v1/waitings/{id}` (3초 조회) | 상태·번호·앞 팀 수. 모르는 상태값은 반영하지 않는다 |
| 7 | `PUT /api/v1/waitings/{id}/eta {etaMin}` | 2분 이상 바뀌거나 1분 주기 |
| 8 | `POST /api/v1/waitings/{id}/arrival {result, alighted?, partySize}` | `CONFIRMED`(alighted 포함)·`MANUAL`·`UNCONFIRMED`. 일부 하차(`PARTIAL`)는 서버 enum에 없어 보내지 않는다 |
| 9 | `DELETE /api/v1/waitings/{id}` | 앱은 자동 호출하지 않는다(사용자 승인 없는 취소 금지) |

## 2026-11-13 확인 결과

로컬 서버(main + 시드 PR) · 웹 POS(리디자인 + 계약 수정 PR) · 에뮬레이터 `SmartJourney_Pleos`의 0.2.11 debug:

| 단계 | 결과 |
| --- | --- |
| 웹 현장 +3팀 | 앱 질문 시점이 서버 대기(15분)로 계산됨 |
| 승인 → 접수 | 서버 번호표 발급(앞에 3팀), 웹 대기열에 차량 인증 카드, ETA 표시 |
| 도착 → 4명 하차 | 웹에 "현장 도착 · 4/4명 하차 완료" |
| 3/4명 하차 | 앱에만 일부 도착 표시, 서버로 보내지 않음(설계대로) |
| 웹 호출 → 착석 | 앱이 3초 안에 호출됨 → 착석 → 다음 매장 |
| 방문 끝내기 | 서버 여정 종료, 도착(ARRIVED)한 웨이팅은 서버 규칙대로 유지 |

## 서버에 남은 요청

1. **일부 도착 상태:** `ArrivalResult`에 `PARTIAL`을 추가하면 앱이 보낸다. 일부 도착을 도착 완료로 바꾸지 않아야 한다.
2. **실시간 갱신:** 앱은 3초 REST 조회를 쓴다. STOMP `/topic/waitings/{id}` 구독으로 바꾸면 지연이 줄어든다.
3. **운영 주소:** release는 HTTPS 서버 주소가 정해지면 `SERVER_URL`에 넣는다.
