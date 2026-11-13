# 0.2.11 검증 기록 (2026-11-13)

0.2.10에서 서버 연동에 필요한 부분만 바꿨다.

| 변경 | 내용 |
| --- | --- |
| 고지 버전 | `CONSENT_VERSION` `2026-11-11` → `2026-11-v1`(서버 `smartjourney.consent.current-version`). 기존 설치는 다시 동의해야 한다 |
| 버전 | versionCode 13, versionName 0.2.11, Gleo `schema.json` version 0.2.11 |
| 문서 | README 서버·웹 함께 실행, `docs/development/server-integration.md` |

## 확인

| 항목 | 결과 |
| --- | --- |
| 빌드·테스트 | `./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug` 성공, 단위 테스트 87개 실패 0. release는 아래 서명 항목 참고 |
| 서버 연동 | 로컬 smart-journey-server(main + #2) · smart-journey-web(#9 + #10) · 에뮬레이터 debug APK. 웹 +3팀 → 질문(대기 15분) → 접수(앞에 3팀) → ETA → 4/4 하차 배지 → 웹 호출 → 앱 호출됨 → 웹 착석 → 다음 매장. 자세한 표는 [서버 연동](../development/server-integration.md) |
| release 서명 | 0.2.10과 같은 인증서(SHA-256 `f888bfaf…d111`)로 서명. 0.2.10 release 위에 업데이트 설치·실행·크래시 없음 확인 |
| 미확인 | 실차, STOMP 실시간 구독(앱은 3초 조회) |
