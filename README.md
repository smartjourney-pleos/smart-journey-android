# smart-journey-android

스마트 저니(Smart Journey)의 Pleos Connect 차량용 안드로이드 앱입니다.
프로젝트 소개와 전체 구조는 [smart-journey-main](https://github.com/smartjourney-pleos/smart-journey-main)을 참고하세요.

## 기술 스택

Kotlin 2.0, Jetpack Compose, Coroutines/Flow, Pleos Vehicle SDK 2.3.2 (NaviHelper SDK는 연동 예정)

- `minSdk 28`, `targetSdk 34`, `compileSdk 35`, JDK 17 이상 (JDK 21 사용 중)
- 에뮬레이터/차량의 Vehicle 서비스 **2.3.13 이상** 필요

## 현재 구성

| 경로 | 내용 |
| --- | --- |
| `app/.../core/PleosVehicleGateway.kt` | Vehicle SDK 래퍼. 좌석 점유·안전벨트·도어 신호를 Flow로 제공 |
| `app/.../core/FakeVehicleGateway.kt` | 시연·테스트용 가짜 구현 (좌석 수, 도어 상태를 직접 변경) |
| `app/.../core/VehicleGatewayFactory.kt` | Pleos 서비스에 붙으면 실제 구현, 실패하면 Fake로 대체 |
| `app/.../MainActivity.kt` | 신호 연동 확인용 임시 화면 (이후 정차 화면/승인 카드로 대체) |

선언한 차량 권한: `CAR_SEATS`, `CAR_DOORS`. 내비 연동 등 기능이 늘면 권한을 추가합니다.

## 빌드와 실행

Android Studio에서 이 폴더를 열면 됩니다. 터미널에서는 다음을 사용합니다.

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # 단위 테스트
```

`local.properties`(Android SDK 경로)는 커밋하지 않으므로 처음 한 번 `sdk.dir=<SDK 경로>`를 직접 만들거나 Android Studio가 만들게 하세요.
Pleos Connect Emulator 설정은 Pleos 공식 문서(Getting Started)를 따릅니다. 에뮬레이터에서 로컬 서버 주소는 `http://10.0.2.2:8080`입니다.

## 브랜치 규칙

| 브랜치 | 용도 |
| --- | --- |
| `main` | 제출·심사용 안정 버전 |
| `dev` | 개발 통합 |
| `feature/기능명` | 기능 작업, `dev`로 PR |

공개 저장소이므로 API 키, 서명 키(`*.keystore`, `*.jks`), `local.properties`는 커밋하지 않습니다.
