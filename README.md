# 꼼짝마차 · StopCar

**출발 전에는 나의 반응을 확인하고, 주행 중에는 달라지는 환경에 맞춰 케어하는 Pleos Connect 앱.**

동물이 도로에 나타나는 짧은 반응 체크로 출발 전 기록을 남깁니다. 주행을 시작하면 야간 미점등, 와이퍼 작동, 터널 접근 정보를 바탕으로 안내하고, 지원되는 차량 장치에 공조·조명·미러 설정 변경을 요청합니다. 사용자는 케어를 중지하고 기록을 확인하거나 공유할 수 있습니다.

현대자동차그룹 차량용 앱 개발 공모전 참가 준비 프로젝트 · 현재 버전 **3.1.0**

[화면 보기](#화면) · [시연 순서](#시연-순서) · [실행 방법](#실행과-조작) · [SDK와 의존성](#sdk와-의존성) · [검증 상태](#검증-상태)

| 자료 | 위치 |
|---|---|
| 앱 소스 | [careway](careway/src/main) |
| 화면 | 아래 실제 에뮬레이터 캡처 2장 |
| 빌드·설치 | [실행과 조작](#실행과-조작) — 소스에서 Debug APK 생성 |
| 테스트 | [단위 테스트](careway/src/test/java/com/example/careway) · [기기 테스트](careway/src/androidTest/java/com/example/careway) |

현재 저장소에는 배포 APK, 시연 영상, 제출용 PDF를 게시하지 않았습니다.

## 어떤 순간에 쓰나요?

| 상황 | 꼼짝마차의 동작 | 운전자가 확인할 내용 |
|---|---|---|
| 출발 전 정차 | 화면에 동물을 표시하고 브레이크 입력까지 시간을 기록 | 이번 반응시간과 최근 개인 기록 |
| 어두운데 하향등이 꺼짐 | 큰 경고와 한국어 음성 안내, 실내 조명 감광 요청 | 전조등 점등 여부 |
| 와이퍼가 계속 작동함 | 제상·외기·미러 열선 등 시야 확보를 위한 설정 요청 | 장치별 실제 적용·보류 상태 |
| 터널에 가까워짐 | 200m 이내 접근 안내와 내기 순환 요청 | 터널 접근과 케어 상태 |
| 케어가 끝남 | 저장한 원래 설정으로 복원 요청 | 복원 완료 또는 미완료 상태 |
| 주행 후 기록 확인 | 최근 30일 반응 기록·전조등 안내 집계 | JSON·CSV 저장, 사용자가 선택한 앱으로 공유 |

반응 기록은 신호 전달 지연을 포함하는 개인 참고 기록입니다. 건강 상태나 운전 적합성을 진단하지 않습니다.

## 화면

### 주행 케어

현재 케어 상태와 상황별 시연 버튼을 한 화면에서 확인합니다. 아래는 **시뮬레이션 모드** 화면입니다.

![꼼짝마차 주행 케어 화면 — 출발 준비와 상황별 시연](docs/images/dashboard.png)

### 세이프티 핏

동물이 나타난 뒤 가상 브레이크를 눌러 반응을 기록한 화면입니다. 표시된 시간은 자동화된 UI 시험 결과이며 사람의 반응능력 평가값이 아닙니다.

![세이프티 핏 — 동물 표시 후 브레이크 반응 기록 완료](docs/images/reaction.png)

## 시연 순서

시뮬레이션 모드는 실제 차량에 명령을 보내지 않습니다. 앱 상단의 **시뮬레이션** 표시를 확인한 뒤 아래 순서로 살펴보세요.

| 순서 | 조작 | 확인할 화면·결과 |
|---|---|---|
| 1 | 주행 화면에서 **반응 체크 시작** | 가상 차량을 정차 상태로 준비하고 측정 시작 |
| 2 | 1.5~3.5초 뒤 동물이 나타나면 **시연: 브레이크 밟기** | 반응시간(ms)과 기록 완료 표시 |
| 3 | **시연: 브레이크 떼기**, 이후 **케어 기록** | 다시 측정할 수 있는 상태와 시뮬레이션 기록 |
| 4 | **주행 케어 → 야간 미점등** | 전조등 경고. **시연: 전조등 켜기**로 해제 |
| 5 | **야간 빗길** 선택 후 전조등 경고 해제, 10초 이상 대기 | 제상·외기·미러 열선 등 시뮬레이션 제어 상태 |
| 6 | **터널 180m 전**, **출구 80m 전**, **직사광선** 선택 | 터널 접근 및 출구·역광 케어 시나리오 |
| 7 | **통과 후 복원** 또는 **케어 중지** | 변경한 설정의 복원 상태 |
| 8 | **케어 기록 → CSV 저장 / 리포트 공유** | 저장 위치 또는 공유 앱 선택 화면 |

실제 차량 모드의 반응 체크에는 **완전 정차·P단·주차 브레이크·화면 조작 허용·브레이크 이벤트 수신**이 모두 필요합니다. 시작할 수 없으면 화면에 필요한 조건을 표시합니다. 출구·역광은 현재 시뮬레이션으로 제공하며, 실제 경로 입력에 연결된 기능으로 표시하지 않습니다.

## 구현 범위

| 기능 | 현재 구현 | 실제 차량 조건·한계 |
|---|---|---|
| 세이프티 핏 | 실제 화면 그리기부터 브레이크 이벤트까지 측정, 조기 입력·시간 초과·취소, 개인 최근 기록 비교, 정차 중 20초 환기 요청 | 정차·P단·주차 브레이크·UX 조작 허용·페달 이벤트 모두 필요. 폴링으로 감지된 페달 변화는 측정 취소. 신호 전달 지연을 포함한 참고 기록 |
| 출발 전 안내 | 화면 표시 중 정차 조건을 확인하면 연결당 한 번 안내 | 실제 시동 이벤트를 구독하는 기능은 아님. 사용자가 시작을 눌러 측정 |
| 야간 미점등 | AAOS NIGHT_MODE·차속·하향등에 따른 큰 경고 화면, 음성, 실내 조명 감광 요청 | 결측·오래된 입력은 미확인 |
| 우천 케어 | WiperSwitch 연속 단계 및 AUTO의 WiperState, 10초 유지, 제상·외기·양쪽 미러 열선·실내 조명 요청 | 와이퍼를 강우량 센서로 표현하지 않음 |
| 터널 접근 | NaviHelper 연결 후 TBT 구독, 200m 이내 안내·내기 순환 요청 | 실연동은 접근 정보. 내부·출구를 임의 추론하지 않음. 우천 동시 감지 시 시야 확보 우선 |
| 출구·역광·스마트글라스 | 출구 100m/역광 시나리오, 검증된 단계·범위 확인, 최대 15초 후 복원 | 실제 출구·역광 입력 미확보, 현재 에뮬레이터 장치 미지원. 시뮬레이션에서 확인 가능 |
| 제어 관리 | 원래 값 영구 기록, 요청 후 최대 12초 실제 값 재조회, 중지·화면 이탈·다음 연결에서 복원 | 요청 응답만으로 성공 표시하지 않음. 수동 변경 시 해당 조건이 끝날 때까지 자동 제어 중지 |
| 음성 | Pleos ON_DEVICE·ko-KR, 재연결·시험 발화·오류 표시 | 서버 등록 불필요. Android TTS 대체 경로 없음 |
| 30일 리포트 | 일별 평균·전조등 안내 횟수, JSON·CSV 저장, 사용자 선택 공유, 모드별 삭제, v2 기록 이전 | 실제/시뮬레이션 분리. 최근 31일, 최대 반응 2,000건·이벤트 20,000건 |

자동 가족 푸시, 건강·인지 저하 진단, ADAS/차선 이탈 제어는 구현하지 않았습니다. 공유는 사용자가 공유 앱과 수신자를 직접 선택합니다.

## 실행과 조작

```powershell
.\gradlew.bat :careway:assembleDebug
adb install -r careway\build\outputs\apk\debug\careway-debug.apk
adb shell am start --user current -n com.example.careway/.MainActivity
```

Android Studio에서 저장소를 열고 `careway` 모듈을 실행합니다. Android SDK 37과 Pleos 에뮬레이터/SDK 서비스 환경이 필요합니다. SDK 경로는 개인 환경의 `local.properties` 또는 `ANDROID_HOME`에 설정합니다. Gradle Wrapper와 JVM 도구 체인 설정을 포함합니다.

1. **시뮬레이션 → 반응 체크 시작**: 다른 시나리오를 보고 있었어도 가상 차량을 정차 상태로 준비한 뒤 바로 측정합니다. 메뉴의 **세이프티 핏**으로 들어갔다면 **반응 체크**를 눌러 시작합니다. 1.5~3.5초 뒤 동물이 나타나면 **시연: 브레이크 밟기**를 누릅니다. 재측정 전에 **시연: 브레이크 떼기**를 누르세요. 실제 차량 모드에서는 정차 조건을 자동 변경하지 않습니다.
2. **야간 미점등**: 경고에서 **시연: 전조등 켜기**로 해제합니다.
3. **야간 빗길 / 터널 180m 전 / 출구 80m 전 / 직사광선**: 제어 상태를 확인합니다. **통과 후 복원** 또는 **케어 중지**로 원래 값 복원을 확인합니다.
4. **차량 연결**: 시스템 권한 요청을 처리하고 정차 조건 확인 후 **케어 켜기**를 누릅니다. 주차 브레이크가 false이거나 입력이 미확인이면 실제 측정과 자동 케어 시작이 제한됩니다.
5. **기록**: **기록 저장**, **CSV 저장**, **리포트 공유**를 사용합니다.
6. **설정 → 음성 다시 연결 / 음성 테스트**로 서비스 상태를 확인합니다.

자동 케어는 앱 화면이 표시되는 동안 동작합니다. 화면 이탈 시 새 제어를 중단하고 변경한 설정의 복원을 요청합니다. 프로세스 종료로 중단되면 다음 차량 연결에서 저장된 복원 기록을 처리합니다.

## SDK와 의존성

- Pleos Vehicle **2.3.2**: Brake·Light·Wiper 상태/리스너와 HVAC·SideMirror·Light·Window 공개 제어, capability API.
- Pleos NaviHelper **3.0.5**: 초기화 후 이벤트 등록, TBT·경로 취소·재탐색.
- Pleos TextToSpeech **3.3.1**: ON_DEVICE·Locale.KOREA·합성 이벤트.
- Android AAOS: CarPropertyManager의 속도·기어·주차 브레이크·NIGHT_MODE, CarUxRestrictionsManager.
- 앱의 AndroidX·Compose·Coroutines 직접 의존성 및 import를 제거했습니다. 기본 Activity·View·Canvas, 일반 상태 객체, Handler·Executor·SDK 콜백으로 구현합니다. **SDK가 내부적으로 가져오는 AndroidX·Coroutines 전이 의존성은 유지합니다. AAOS도 유지합니다.**
- SDK의 INTERNET 권한이 병합됩니다. 자체 서버·비공개 콘솔 HTTP 호출, Vehicle의 숨겨진 getGear, PRIVATE_CAR_SAFETY는 사용하지 않습니다.

공식 근거: [공모전 SDK](https://appdevchallenge.auto-hmg.io/faq), [AAOS 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/pleos-connect-sdk-api), [가용성](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability), [TTS 등록](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/app-registration), [언어/지역](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/set-locale).

## 검증 상태

3.1.0 로컬 검증: 단위 테스트 **36개**, 기본 Android 기기 테스트 **6개** 통과. Lint 오류 **0**, 경고 **20**. [검증 요약·의존성·APK SHA-256](docs/validation-3.1.0.json). 물리 차량 주행 시험은 하지 않았습니다.

```powershell
.\gradlew.bat :careway:testDebugUnitTest :careway:lintDebug :careway:assembleDebug
.\gradlew.bat :careway:assembleDebugAndroidTest
adb install -r careway\build\outputs\apk\androidTest\debug\careway-debug-androidTest.apk
adb shell am instrument --user current -w com.example.careway.test/com.example.careway.NativeTestRunner
```

기기 테스트는 Pleos 서비스가 준비된 에뮬레이터가 필요합니다. `NativeTestRunner`는 기본 Android Instrumentation으로 화면·복원 시뮬레이션·기록 이전·Activity 재생성·실제 SDK 읽기·모드 전환을 검사합니다. 실제 차량 제어 명령은 시험하지 않습니다.

실제 SDK 관찰에서 양쪽 미러 변경·복원을 확인했습니다. 공조·제상·내외기·실내 조명은 요청 성공 응답 후에도 값 변경을 확인하지 못했습니다. 스마트글라스는 에뮬레이터에서 미지원이었습니다. 터널 출구·역광은 시뮬레이션으로 확인했습니다. SDK 전이 의존성 JNA의 16KB 페이지 호환성 경고가 남습니다.

## 프로젝트 구성

```text
careway/src/main/java/com/example/careway/
  CareController.kt    화면 상태 객체·Handler와 케어·측정 흐름
  core/              반응 측정, 환경 판단, 제어·복원 정책
  vehicle/           Pleos·AAOS 연결, 음성, 시뮬레이션 입력
  data/              로컬 기록 저장, 30일 집계, 내보내기
  ui/                주행 화면, 동물 표시, 반응 체크, 기록·설정
careway/src/test/          정책·반응 측정·리포트 단위 테스트
careway/src/androidTest/   화면·기록·SDK 기기 테스트
gradle/                   버전 목록, Wrapper, JVM 도구 체인
```

차량 입력 → 상황 판단 → 안내·제어 요청 → 실제 값 재조회 → 기록·복원 순서로 동작합니다. 실제 입력과 시뮬레이션 입력은 분리하며 기록도 모드별로 구분합니다. 앱 ID는 `com.example.careway`입니다.

APK는 `careway/build/outputs/apk/debug/careway-debug.apk`에 생성됩니다. 개인 SDK 경로, 서명 키, 빌드 결과물은 저장소에 포함하지 않습니다.

## 참고 자료

README의 소개·자료 안내·화면·실행 순서는 [CarPet](https://github.com/xpdlf1004/carpet)의 문서 구성을 참고했습니다. 본문은 꼼짝마차의 구현과 검증 결과에 맞춰 작성했으며, 화면은 꼼짝마차에서 직접 캡처했습니다. 다른 참가작의 코드·이미지·음성은 가져오지 않았습니다.
