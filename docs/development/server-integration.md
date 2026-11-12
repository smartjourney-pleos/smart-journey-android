# 차량 앱 ↔ smart-journey-server 연동 (백엔드 전달용)

2026-11-12. 차량 앱(`data/ServerBackend.kt`)이 호출하는 API와 기대 동작이다. 서버 계약 원본은 서버 저장소 `docs/server/02_기능_API_명세.md`와 Swagger다.

## 현재 상태

- 앱: 디버그 빌드의 데모 패널 **접수 백엔드** 스위치(또는 `DemoReceiver --es cmd server --ei n 1`)로 켠다. 기본은 앱 안 `FakeBackend`. 주소는 `BuildConfig.SERVER_URL`(debug `http://10.0.2.2:8080`, release 비어 있음 = 서버 미사용).
- 서버 `main`(468eec5) 확인 결과: 기기 등록·동의 고지·매장 목록·대기 현황은 동작. 아래 표의 나머지는 `NOT_IMPLEMENTED`라 앱의 서버 모드에서 방문 시작이 "접수 서버에 연결하지 못했어요"로 멈춘다.

## 앱이 쓰는 API

| 순서 | 호출 | 앱 처리 | 서버 상태(468eec5) |
| --- | --- | --- | --- |
| 1 | `GET /api/v1/consent/notice` → `POST /api/v1/devices {consentVersion}` | 응답 `deviceToken`을 `X-Device-Token` 헤더로 사용. 401이면 다시 등록 | 동작 |
| 2 | `GET /api/v1/stores` | 매장 목록(이름·주소·좌표·POI)으로 내비 경로 입력 | 동작 |
| 3 | `GET /api/v1/stores/{id}/queue-status` (3초 조회) | `estimatedWaitMin`·`arrivalBufferMin`으로 제안 시점 계산 | 동작 |
| 4 | `POST /api/v1/journeys {partySize, partySource, storeIds}` | 방문 시작 때. `ACTIVE_JOURNEY_EXISTS`면 `GET /journeys/current` → `POST /journeys/{id}/end` 후 다시 생성 | 미구현 |
| 5 | `POST /api/v1/waitings {journeyId, storeId, partySize, approvalMethod}` + `Idempotency-Key: <질문 ID>` | 음성 승인 1회당 1번. 같은 키로 다시 오면 같은 웨이팅을 돌려줘야 한다 | 미구현 |
| 6 | `GET /api/v1/waitings/{id}` (3초 조회) | `status`(REGISTERED·ARRIVED·CALLED·SEATED·NO_SHOW·CANCELLED), `ticketNo`, `aheadTeams`, `estimatedWaitMin` | 미구현 |
| 7 | `PUT /api/v1/waitings/{id}/eta {etaMin}` | 2분 이상 바뀌거나 1분 주기 | 미구현 |
| 8 | `POST /api/v1/waitings/{id}/arrival {result, alighted?, partySize}` | `CONFIRMED`(alighted 포함)·`MANUAL`·`UNCONFIRMED` | 미구현 |
| 9 | `DELETE /api/v1/waitings/{id}` | 현재 앱은 자동 호출하지 않음(사용자 승인 없는 취소 금지) | 미구현 |

웹 POS가 쓰는 `GET /pos/queue`, `POST /pos/waitings/{id}/call|seat|no-show`, `POST|DELETE /pos/demo/walk-ins`도 미구현이다. 이 셋이 되면 시연 흐름(웹에서 대기 추가 → 차량 질문 → 음성 승인 → 웹 대기열에 표시 → 웹에서 호출 → 차량 상태 변경)이 이어진다.

## 오류 처리 약속

- 접수 요청의 400·403·404 응답 → 앱은 **접수 거절**로 표시하고 다시 묻지 않는다.
- 409·5xx·타임아웃·연결 끊김 → **확인 필요**. 이미 접수됐을 수 있으므로 앱이 자동 재요청하지 않는다. 같은 `Idempotency-Key` 재요청에는 기존 결과를 돌려주면 된다.
- 오류 본문은 `ApiError {code, message}`를 그대로 사용한다.

## 서버에 요청하는 추가 사항

1. **일부 도착 상태:** `ArrivalResult`에 `PARTIAL`(alighted < partySize)을 추가해 주면 앱이 보낸다. 지금은 앱에만 "일행 3명 도착 추정 · 1명 이동 중"으로 표시하고 서버로 보내지 않는다. 일부 도착을 `ARRIVED`(도착 완료)로 바꾸지 않아야 한다.
2. **대기 허용 시간:** 앱이 판단하므로 서버 변경은 필요 없다. 웹에서 대기를 늘리면 앱의 판단 질문이 나온다.
3. **실시간 갱신:** 앱은 3초 REST 조회를 쓴다. STOMP `/topic/waitings/{id}`가 안정되면 구독으로 바꾼다.
4. 데모 매장 좌표(시흥 정왕동)는 에뮬레이터 기본 위치(성남 수정구)에서 실제 경로가 잡혀 ETA가 길게 나온다. 시연 시간을 줄이려면 가까운 좌표로 바꿔도 된다.
