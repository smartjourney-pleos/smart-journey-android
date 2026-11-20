# 차량 앱 ↔ smart-journey-server 연동

2026-11-20 갱신 (`dev` 고도화 Phase 3). 서버 계약 원본은 서버 저장소 `docs/server/02_기능_API_명세.md`와 Swagger(`/swagger-ui.html`)다.

## 현재 상태

- **앱 전환 및 주소 주입:**
  - `debug`: 기본은 앱 안 `FakeBackend`이며 데모 패널 **접수 백엔드** 스위치(또는 `DemoReceiver --es cmd server --ei n 1`)로 전환한다. 주소는 `http://10.0.2.2:8080`이고, 평문 HTTP/WS는 `debug` 전용 `network_security_config.xml`에서 `10.0.2.2`만 예외 허용한다. 방문 중에는 바꿀 수 없다.
  - `release`: 기본 `SERVER_URL`은 비어 있어 앱 안 시연 데이터(`FakeBackend`)만 쓴다. 운영 HTTPS 서버가 준비되면 환경변수 또는 `local.properties`의 `SJ_RELEASE_SERVER_URL=https://...`로 주입하며(`https://` 외 스킴은 빌드·런타임에서 차단), 주입 시 서버 백엔드가 기본 활성화된다.
- **실시간 연동 + 자동 폴백:**
  - 대기 현황(`/topic/stores/{id}/queue`)과 웨이팅 상태(`/topic/waitings/{id}`)는 순수 WebSocket(`/ws`, SockJS 미사용) 위 STOMP 1.2 클라이언트로 실시간 구독한다(외부 HTTP/WebSocket 라이브러리 미추가, 표준 JVM/Android 소켓 + TLS 1.2+ 및 HTTPS 호스트명 검증).
  - 초기 연결·단절 구간에서는 3초 REST 조회로 자동 폴백하며, STOMP 연결·재연결 직후 REST 동기화 1회를 수행해 공백 누락을 막는다.
- **토큰 보호:** `deviceToken`은 Android Keystore AES-256-GCM 암호화 저장소(`EncryptedDeviceTokenStore`)에 보관하며 HTTP/STOMP 모두 `X-Device-Token` 헤더로만 전송한다(URL 쿼리 금지). 동의 철회·HTTP 401·STOMP `UNAUTHORIZED` 시 즉시 폐기한다.
- **매장 POS 조작:** 현장 대기 증감·호출·착석·노쇼는 웹 POS(`smart-journey-web`)에서 한다. 앱 데모 패널의 현장 대기·호출·착석 버튼은 `FakeBackend` 전용이다.
- **데모 매장:** 앱·서버·웹 모두 42dot(1)·소프트웨어드림센터(2)다(서버 시드 `smart-journey-server#2`).

## 앱이 쓰는 API 및 STOMP 토픽

| 순서 | 호출 / 구독 | 앱 처리 |
| --- | --- | --- |
| 1 | `GET /api/v1/consent/notice` → `POST /api/v1/devices {consentVersion}` | 앱에서 동의한 경우에만(`Mutex`로 중복 등록 방지). 서버 고지 `version`이 `CONSENT_VERSION`과 다르면 등록하지 않는다. `deviceToken`은 암호화 저장 후 `X-Device-Token` 헤더로만 전송하고 401·`UNAUTHORIZED` 시 폐기·재등록 |
| 2 | `GET /api/v1/stores` | 매장 이름·주소·좌표·POI로 내비 경로 입력 |
| 3 | STOMP `/topic/stores/{id}/queue` (`QUEUE_UPDATED`) + 폴백 `GET /api/v1/stores/{id}/queue-status` | `estimatedWaitMin`·`arrivalBufferMin`으로 질문 시점 계산. 연결 유지 중에는 중복 3초 폴링 중단, 단절 시 3초 폴백 |
| 4 | `POST /api/v1/journeys {partySize, partySource, storeIds}` | 방문 시작 때. `ACTIVE_JOURNEY_EXISTS`면 `GET /journeys/current` → `POST /journeys/{id}/end` 후 다시 생성 |
| 5 | `POST /api/v1/waitings` + `Idempotency-Key: <질문 ID>` | 승인 1회당 1번. 400·403·404는 접수 거절, 409·5xx·타임아웃은 확인 필요(자동 재요청 없음) |
| 6 | STOMP `/topic/waitings/{id}` (`WAITING_UPDATED`·`CALLED`) + 폴백 `GET /api/v1/waitings/{id}` | 상태·번호·앞 팀 수 실시간 반영, `CALLED` 이벤트의 `qrPayload` 병합·유지. 모르는 상태값은 반영하지 않는다 |
| 7 | `PUT /api/v1/waitings/{id}/eta {etaMin}` | 2분 이상 바뀌거나 1분 주기 |
| 8 | `POST /api/v1/waitings/{id}/arrival {result, alighted?, partySize}` | `CONFIRMED`(alighted 포함)·`MANUAL`·`UNCONFIRMED`. 일부 하차(`PARTIAL`)는 서버 `ArrivalResult` enum에 없고 서버가 유효 요청을 `ARRIVED`로 전이하므로 보내지 않는다 |
| 9 | `DELETE /api/v1/waitings/{id}` | 앱은 자동 호출하지 않는다(사용자 승인 없는 취소 금지) |

## 확인 결과

### 2026-11-13 로컬 실서버 확인 (0.2.11 debug, REST 폴링 기준)

로컬 서버(`main` + 시드 PR) · 웹 POS(리디자인 + 계약 수정 PR) · 에뮬레이터 `SmartJourney_Pleos`의 0.2.11 debug:

| 단계 | 결과 |
| --- | --- |
| 웹 현장 +3팀 | 앱 질문 시점이 서버 대기(15분)로 계산됨 |
| 승인 → 접수 | 서버 번호표 발급(앞에 3팀), 웹 대기열에 차량 인증 카드, ETA 표시 |
| 도착 → 4명 하차 | 웹에 "현장 도착 · 4/4명 하차 완료" |
| 3/4명 하차 | 앱에만 일부 도착 표시, 서버로 보내지 않음(설계대로) |
| 웹 호출 → 착석 | 앱이 3초 안에 호출됨 → 착석 → 다음 매장 |
| 방문 끝내기 | 서버 여정 종료, 도착(ARRIVED)한 웨이팅은 서버 규칙대로 유지 |

> 참고: 2026-11-20 Phase 3의 STOMP WebSocket 실시간 구독·폴백은 `smart-journey-server` 코드 계약(`WebSocketConfig`·`StompAuthChannelInterceptor`·`QueueBroadcaster`·`RealtimeRelay`)과 동일한 프로토콜의 로컬 루프백 목 서버 단위 테스트로 검증했으며, 실제 기동된 `smart-journey-server`와의 라이브 재검증은 별도 수행이 필요하다.

## 서버·운영에 남은 항목

1. **일부 도착 상태 (`ArrivalResult.PARTIAL`):** `smart-journey-server`의 `ArrivalResult`는 현재 `CONFIRMED`·`MANUAL`·`UNCONFIRMED` 3종만 지원하며, `ArrivalService.report()`가 모든 유효 요청을 `ARRIVED`로 전이한다. 서버가 `ARRIVED`로 전이하지 않는 `PARTIAL`을 지원하도록 계약이 확장되면 앱에서 전송을 활성화한다.
2. **운영 HTTPS 서버 주소:** 운영 HTTPS 서버가 배포되면 `SJ_RELEASE_SERVER_URL`로 주입해 실서버·릴리스 빌드를 검증한다.
