# 꼼짝마차

팀명: **연근** · 앱명: **꼼짝마차**

출발 전 브레이크 반응시간을 기록하고, 야간·우천·터널 등 주행 환경에 따라 차량 설정을 조절하는 Pleos Connect 앱.

## 다운로드

[꼼짝마차 APK 다운로드](https://github.com/Controllls/stopcar/raw/refs/heads/main/apk/연근_꼼짝마차_release.apk)

저장소 내 APK 위치: [`apk/연근_꼼짝마차_release.apk`](apk/연근_꼼짝마차_release.apk)

```powershell
adb install -r apk/연근_꼼짝마차_release.apk
adb shell am start --user current -n com.example.careway/.MainActivity
```

## 주요 기능

| 기능 | 동작 |
|---|---|
| 세이프티 핏 | 동물이 화면에 표시된 시점부터 브레이크 이벤트 수신까지 반응시간 측정. 조기 입력·시간 초과·측정 취소 처리, 최근 개인 기록 비교 |
| 출발 전 안내 | 차량 연결 후 정차 조건이 확인되면 반응 체크 안내 |
| 야간 케어 | 야간 상태·차속·하향등을 확인해 미점등 경고, 음성 안내, 실내 조명 감광 요청 |
| 우천 케어 | 와이퍼 상태에 따라 제상·외기·양쪽 미러 열선·실내 조명 제어 요청. 마지막 우천 신호 이후 10초 유지 |
| 터널 케어 | TBT 터널 정보 기준 200m 이내 접근 안내와 내기 순환 요청. 우천 시 시야 확보 우선 |
| 출구·역광 시연 | 출구 100m 이내 또는 역광 시나리오에서 스마트글라스 감광, 최대 15초 후 복원 |
| 설정 복원 | 변경 전 값을 저장하고 제어 후 실제 값 재조회. 케어 중지·화면 이탈·재연결 시 복원, 수동 변경 우선 |
| 음성 안내 | Pleos 온디바이스 한국어 음성 합성 |
| 케어 기록 | 최근 30일 반응시간·전조등 안내 집계, JSON·CSV 저장, 공유 앱 연동 |

반응 체크는 완전 정차·P단·주차 브레이크·UX 조작 허용·브레이크 이벤트 수신 조건에서 시작한다. 측정값에는 차량 신호 전달 지연이 포함된다. 실제 차량 기록과 시뮬레이션 기록은 분리해 저장한다.

## 화면

### 주행 케어 · 시뮬레이션

![주행 케어](docs/images/dashboard.png)

### 세이프티 핏 · 시뮬레이션

![브레이크 반응시간 기록](docs/images/reaction.png)

## 기술 구성

| 구분 | 사용 기술 |
|---|---|
| 언어 | Kotlin |
| 화면 | Android Activity·View·Canvas |
| 상태 관리 | CareController·StateCell, Handler로 화면 갱신 |
| 비동기 처리 | Executor·SDK 콜백·CompletableFuture |
| 차량 상태·제어 | Pleos Vehicle SDK |
| 내비게이션 | Pleos NaviHelper SDK |
| 음성 | Pleos TextToSpeech SDK, ON_DEVICE·ko-KR |
| 표준 차량 정보 | AAOS CarPropertyManager·CarUxRestrictionsManager |
| 저장 | SharedPreferences·JSON |

차량 입력 → 상황 판단 → 안내·제어 요청 → 실제 값 확인 → 기록·복원 순서로 처리한다. 차량 통신은 작업 스레드에서 실행하고, 반응 측정과 화면 갱신은 메인 스레드에서 처리한다.

## 테스트 환경 및 필요한 설정

| 항목 | 설정 |
|---|---|
| 개발·실행 에뮬레이터 | Pleos Connect Emulator **v2.1.0** |
| 시스템 이미지 | Android, x86_64 |
| 화면 규격 | **2560×1440px, 160dpi**, 가로 화면 |
| 실행 사용자 | user 10 |
| 개발 호스트 | Windows, Android Studio |
| 차량 연동 | Pleos 차량·내비게이션·음성 서비스 및 기능별 권한 |

- 반응 측정은 완전 정차·P단·주차 브레이크·UX 조작 허용·브레이크 이벤트 수신이 필요하다.
- 시뮬레이션 모드는 가상 차량 입력으로 실행한다. 출구·역광·스마트글라스는 시뮬레이션으로 제공한다.
- 차량 제어 기능은 해당 차량의 API 지원 여부와 권한에 따라 동작한다.

## 빌드 및 실행

- Android SDK, Gradle
- Pleos Connect 에뮬레이터 및 SDK 서비스
- 실행 모듈: `careway`
- 앱 ID: `com.example.careway`

SDK 경로를 `local.properties` 또는 `ANDROID_HOME`에 설정한다. Gradle Wrapper와 JVM 도구 체인 설정은 저장소에 포함되어 있다.

```powershell
.\gradlew.bat :careway:assembleDebug
adb install -r careway\build\outputs\apk\debug\careway-debug.apk
adb shell am start --user current -n com.example.careway/.MainActivity
```

릴리즈 빌드: `.\gradlew.bat :careway:assembleRelease`. 서명 설정은 저장소 외부의 `~/.gradle/stopcar-release.properties` 또는 `-PstopcarSigningFile=경로`로 지정한다. 필드는 `storeFile`, `storePassword`, `keyAlias`, `keyPassword`이며, 설정이 없으면 서명되지 않은 APK가 생성된다.

## 사용 방법

1. **시뮬레이션 → 반응 체크 시작**을 누른다. 1.5~3.5초 뒤 동물이 나타나면 **시연: 브레이크 밟기**를 누른다. 재측정 전에는 **시연: 브레이크 떼기**를 누른다.
2. **야간 미점등**을 선택해 경고를 확인하고 **시연: 전조등 켜기**로 해제한다.
3. **야간 빗길 / 터널 180m 전 / 출구 80m 전 / 직사광선**으로 상황별 케어를 확인한다.
4. **통과 후 복원** 또는 **케어 중지**로 설정 복원을 확인한다.
5. **차량 연결 → 케어 켜기**로 실제 SDK 입력을 사용한다. 시작 시 정차 조건과 권한을 확인한다.
6. **케어 기록**에서 JSON·CSV 저장 및 공유, **설정**에서 기능별 활성화와 음성 테스트를 실행한다.

## 디렉터리 구조

```text
careway/src/main/java/com/example/careway/
  MainActivity.kt       권한·생명주기·문서 저장 연동
  CareController.kt     상태 관리·케어·반응 측정 흐름
  core/                 상태 객체·상황 판단·제어 및 복원 정책
  vehicle/              Pleos·AAOS 연결·음성·시뮬레이션
  data/                 기록 저장·집계·내보내기
  ui/                   주행·반응 체크·기록·설정 화면
```
