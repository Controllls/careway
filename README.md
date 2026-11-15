# 꼼짝마차

팀명: **연근** · 앱명: **꼼짝마차**

출발 전 브레이크 반응으로 오늘의 컨디션을 확인하고, 야간·우천·터널 등 주행 환경에 따라 차량 설정을 조절하는 Pleos Connect 앱.

## 다운로드

[꼼짝마차 APK 다운로드](https://github.com/Controllls/careway/raw/refs/heads/main/apk/연근_꼼짝마차_release.apk)

저장소 내 APK 위치: [`apk/연근_꼼짝마차_release.apk`](apk/연근_꼼짝마차_release.apk) · 3.6.0 릴리즈 빌드 · [빌드 정보](apk/release-info.json)

```powershell
adb install -r apk/연근_꼼짝마차_release.apk
adb shell am start --user current -n com.example.careway/.MainActivity
```

릴리즈 APK는 항상 차량에 연결해 동작하며, 시연 도구(시나리오 시연, 시뮬레이션)는 들어 있지 않다. 시연 도구가 필요하면 아래 [시연용 빌드](#시연용-빌드)를 사용한다.

## 주요 기능

| 기능 | 동작 |
|---|---|
| 세이프티 핏 | 동물이 화면에 표시된 시점부터 브레이크 이벤트 수신까지 반응시간을 3회 측정하고 평균으로 오늘의 컨디션 표시(0.5초 이하 정상, 1초 미만 주의, 1초 이상 느림). 느림이면 전방 주의 알림 강화, 음성 안내, 정차 중 환기 요청. 조기 입력·시간 초과·측정 취소 처리 |
| 출발 전 안내 | 앱을 열면 차량에 자동 연결해 케어를 시작하고, 정차 조건이 확인되면 ‘오늘의 반응성 체크’ 안내 |
| 야간 케어 | 야간 상태·차속·하향등을 확인해 미점등 시 화면 전체 주황 경고, 음성 안내, 실내 조명 감광 요청 |
| 우천 케어 | 와이퍼 연속 작동 상태에 따라 제상·외기·양쪽 미러 열선·실내 조명 제어 요청. 마지막 우천 신호 이후 10초 유지 |
| 터널 케어 | 내비 TBT의 터널 입구 거리 기준 100m 이내에서 내기 순환 요청, 입구 통과 후 30초 유지 뒤 복원. 우천 시 시야 확보 우선 |
| 서비스 현황판 | 홈 화면에서 세이프티 핏·야간 시인성·빗길 시야·터널 공조·음성 안내의 동작 상태와 차량 제어 현황을 아이콘으로 표시 |
| 설정 복원 | 변경 전 값을 저장하고 제어 후 실제 값 재조회. 케어 중지·화면 이탈·재연결 시 복원, 수동 변경 우선 |
| 음성 안내 | Pleos 온디바이스 한국어 음성 합성 |
| 케어 기록 | 최근 30일 반응시간·전조등 안내 집계, JSON·CSV 저장, 공유 앱 연동 |

반응 체크는 완전 정차·P단·UX 조작 허용·브레이크 이벤트 수신 조건에서 시작한다. 측정값에는 차량 신호 전달 지연이 포함된다.

## Pleos Connect SDK 활용

모든 핵심 기능이 Pleos SDK로 받은 차량 상태에서 시작해 Pleos SDK의 차량 제어와 음성으로 끝난다. 사용 버전은 Vehicle 2.3.2, NaviHelper 3.0.5, TextToSpeech 3.3.1이다.

### 상황 인식 (입력)

| 기능 | SDK · API | 권한 | 에뮬레이터 확인 |
|---|---|---|---|
| 세이프티 핏 반응 측정 | Vehicle `Brake.registerBrakePedalStatus`, `getCurrentBrakePedalStatus` | `pleos CAR_DRIVE` | 가상 콘솔 페달 입력으로 이벤트 수신 |
| 스텔스 주행 감지 | Vehicle `Light.registerLowBeamLightState`, `getCurrentLowBeamLightState` | `pleos CAR_LIGHTS` | 콘솔에서 하향등을 켜고 끄면 앱에 반영 |
| 빗길 감지 | Vehicle `Wiper.getCurrentWiperSwitch`, `registerWiperState`, `getCurrentWiperState` | `pleos CAR_WINDOWS` | 콘솔 Low/High가 연속 1단/3단으로 읽히고 빗길 케어 실행 |
| 터널 접근 감지 | NaviHelper `addListener`, `onTBTInfo`, `getTBTInfo` (`TurnType.TURN_TUNNEL`, 입구 거리) | `pleos NAVI_ROUTE` | 안내 정보 조회·수신 확인. 터널 경로는 재현하지 못해 유발 입력으로 확인 |

### 동작 (출력)

| 기능 | SDK · API | 권한 | 에뮬레이터 확인 |
|---|---|---|---|
| 앞유리 김서림 제거 | Vehicle `HVAC.setHvacDefroster` | `pleos CONTROL_CAR_CLIMATE` | 적용 후 값 재조회로 확인 |
| 내기 순환·외기 유입 | Vehicle `HVAC.setHvacRecirculation` | `pleos CONTROL_CAR_CLIMATE` | 적용·복원 모두 재조회로 확인 |
| 공조 전원(환기) | Vehicle `HVAC.setHvacPower` | `pleos CONTROL_CAR_CLIMATE` | 이미 켜진 상태로 읽혀 그대로 유지. 값 변화는 미확인 |
| 좌우 미러 열선 | Vehicle `SideMirror.setHvacSideMirrorHeat` | `pleos CONTROL_CAR_MIRRORS` | 양쪽 모두 적용 후 재조회로 확인 |
| 실내 조명 감광 | Vehicle `Light.setAmbientLightBrightness` | `pleos CONTROL_CAR_LIGHTS` | 적용 후 재조회로 확인 |
| 음성 안내 | TextToSpeech `speak` (`Mode.ON_DEVICE`, `Locale.KOREA`) | `pleos TTS_SERVICE` | 합성 완료 이벤트 수신 |

### SDK를 쓰는 방식

- **지원 여부를 먼저 확인한다.** 제어와 조회 전에 `checkXXXCapabilities`로 접근 유형·대상 영역·값 범위를 확인하고, 지원하지 않는 차량에서는 제어하지 않고 안내만 한다.
- **요청 응답만으로 성공이라 하지 않는다.** `set` 호출 뒤 같은 항목의 `get`으로 차량 값을 다시 읽어, 바뀐 것을 확인했을 때만 적용 완료로 표시한다.
- **바꾼 설정은 되돌린다.** 제어 전 값을 저장해 두고 상황이 끝나거나 화면을 벗어나면 복원한다. 앱이 강제 종료돼도 다음 연결에서 복원한다.
- **운전자 조작이 우선이다.** 자동 제어한 값을 운전자가 직접 바꾸면 그 상황이 끝날 때까지 다시 건드리지 않는다.
- **리스너와 조회를 함께 쓴다.** 상태 변화는 리스너로 받고 주기 조회로 누락을 보완한다. 반응 시간은 브레이크 리스너 이벤트의 수신 시각으로만 계산한다.
- **SDK 호출은 작업 스레드에서 시간 제한을 두고 실행한다.** 응답이 없거나 오류가 나면 해당 입력을 ‘미확인’으로 처리하고, 미확인 입력으로는 차량을 제어하지 않는다.
- **표준 우선 정책을 따른다.** 차속·기어·야간 여부처럼 AAOS 표준에 있는 정보는 Pleos 정책대로 AAOS `CarPropertyManager`로 읽고, Pleos 전용 영역만 SDK를 쓴다.
- **연결을 정리한다.** 화면 종료 시 리스너를 해제하고 `Vehicle`·`NaviHelper`·`TextToSpeech`를 `release`한다.

## 화면

### 홈 · 서비스 현황판

![홈 화면](docs/images/dashboard.png)

### 세이프티 핏 · 3회 측정과 컨디션 (시연용 빌드의 시뮬레이션)

![브레이크 반응시간 기록](docs/images/reaction.png)

## 사용 방법

1. 앱을 열면 차량에 자동으로 연결하고 케어를 시작한다. 정차 중이면 ‘오늘의 반응성 체크’ 안내가 뜬다.
2. **지금 시작**을 누르고 동물이 나타날 때마다 브레이크를 밟는다. 밟은 뒤 발을 떼면 다음 측정이 시작되고, 3회 평균으로 컨디션이 표시된다. 홈의 **반응 체크**로도 시작할 수 있다.
3. 홈에서 서비스별 동작 상태(감시 중·동작 중·경고)와 차량 제어 현황을 확인한다. 주행 중에는 조작할 것이 없다.
4. **케어 중지**를 누르면 자동 제어를 멈추고 앱이 바꾼 차량 설정을 복원한다.
5. **설정**에서 기능별 활성화, 음성 테스트, 차량 지원 기능을 확인하고, **케어 기록 보기**에서 30일 기록을 JSON·CSV로 저장하거나 공유한다.

## 기술 구성

| 구분 | 사용 기술 |
|---|---|
| 언어 | Kotlin |
| 화면 | Android Activity·View·Canvas |
| 상태 관리 | CareController·StateCell, Handler로 화면 갱신 |
| 비동기 처리 | Executor·SDK 콜백·CompletableFuture |
| 차량 상태·제어 | Pleos Vehicle SDK (브레이크·하향등·와이퍼 상태, 공조·미러 열선·실내 조명 제어) |
| 내비게이션 | Pleos NaviHelper SDK (TBT 터널 입구 거리) |
| 음성 | Pleos TextToSpeech SDK, ON_DEVICE·ko-KR |
| 표준 차량 정보 | AAOS CarPropertyManager(차속·기어·야간)·CarUxRestrictionsManager |
| 저장 | SharedPreferences·JSON |

차량 입력 → 상황 판단 → 안내·제어 요청 → 실제 값 확인 → 기록·복원 순서로 처리한다. 차량 통신은 작업 스레드에서 실행하고, 반응 측정과 화면 갱신은 메인 스레드에서 처리한다. 제어 요청은 한 번에 보내고, 차량 값이 바뀐 것을 다시 읽었을 때만 적용 완료로 표시한다.

## 테스트 환경 및 필요한 설정

| 항목 | 설정 |
|---|---|
| 개발·실행 에뮬레이터 | Pleos Connect Emulator **v2.1.0** |
| 시스템 이미지 | Android, x86_64 |
| 화면 규격 | **2560×1440px, 160dpi**, 가로 화면 |
| 실행 사용자 | user 10 |
| 개발 호스트 | Windows, Android Studio |
| 차량 연동 | Pleos 차량·내비게이션·음성 서비스 및 기능별 권한 |

- 반응 측정은 완전 정차·P단·UX 조작 허용·브레이크 이벤트 수신이 필요하다.
- 브레이크 페달·하향등·와이퍼·기어·차속은 에뮬레이터 가상 콘솔로 바꿔 확인할 수 있다. 야간 신호와 터널 안내는 에뮬레이터에서 만들 수 없어 시연용 빌드의 시나리오 시연으로 확인한다.
- 차량 제어 기능은 해당 차량의 API 지원 여부와 권한에 따라 동작한다.
- 실제 차량 시험은 하지 않았다.

## 빌드 및 실행

- Android SDK, Gradle
- Pleos Connect 에뮬레이터 및 SDK 서비스
- 실행 모듈: `careway`
- 앱 ID: `com.example.careway`

SDK 경로를 `local.properties` 또는 `ANDROID_HOME`에 설정한다. Gradle Wrapper와 JVM 도구 체인 설정은 저장소에 포함되어 있다.

릴리즈 빌드: `.\gradlew.bat :careway:assembleRelease`. 서명 설정은 저장소 외부의 `~/.gradle/stopcar-release.properties` 또는 `-PstopcarSigningFile=경로`로 지정한다. 필드는 `storeFile`, `storePassword`, `keyAlias`, `keyPassword`이며, 설정이 없으면 서명되지 않은 APK가 생성된다.

단위 테스트: `.\gradlew.bat :careway:testDebugUnitTest`

### 시연용 빌드

에뮬레이터에서 만들 수 없는 주행 상황을 보여 주기 위한 도구는 디버그 빌드에 들어 있다.

```powershell
.\gradlew.bat :careway:assembleDebug
adb install -r careway\build\outputs\apk\debug\careway-debug.apk
```

릴리즈 서명으로 시연 도구를 포함하려면 `.\gradlew.bat :careway:assembleRelease -PcarewayDemoTools=true`로 빌드한다.

- **시나리오 시연**: 홈 바로 가기에 추가된다. **야간 도로 진입 · 전조등 OFF / 소나기 시작 / 터널 접근 주행 시작**으로 상황을 유발한다. 유발하는 것은 입력뿐이며 감지·경고·음성·차량 제어·복원은 같은 기능 경로가 실행된다. 브레이크 페달과 정차 인터락은 유발하지 않는다.
- **시뮬레이션 / 차량 연결**: 상단에 추가된다. 시뮬레이션은 가상 차량으로 실행하며 실제 차량에는 명령을 보내지 않는다. 반응 체크에서는 **시연: 브레이크 밟기/떼기**를 사용한다. 시뮬레이션 기록은 차량 기록과 분리해 저장한다.

## 디렉터리 구조

```text
careway/src/main/java/com/example/careway/
  MainActivity.kt       권한·생명주기·문서 저장 연동
  CareController.kt     상태 관리·케어·반응 측정 흐름
  core/                 상태 객체·상황 판단·시나리오 유발·제어 및 복원 정책
  vehicle/              Pleos·AAOS 연결·음성·시뮬레이션
  data/                 기록 저장·집계·내보내기
  ui/                   홈·반응 체크·시나리오·기록·설정 화면, 아이콘
```
