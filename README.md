# 꼼짝마차 3.0.1 — 출발 전 체크와 주행 케어

Pleos Connect용 출발 전 반응 체크 및 주행 환경 케어 앱입니다. 다른 참가작의 코드·이미지·음성은 제품에 복사하지 않았습니다. 앱 ID는 `com.example.careway`, 표시 이름은 신청서의 **꼼짝마차**입니다.

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
- AndroidX·Compose·Material3·Lifecycle·Coroutines 직접 의존성과 SDK 전이 의존성 유지. **Pleos SDK만으로 구성된 APK는 아닙니다.**
- SDK의 INTERNET 권한이 병합됩니다. 자체 서버·비공개 콘솔 HTTP 호출, Vehicle의 숨겨진 getGear, PRIVATE_CAR_SAFETY는 사용하지 않습니다.

공식 근거: [공모전 SDK](https://appdevchallenge.auto-hmg.io/faq), [AAOS 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/pleos-connect-sdk-api), [가용성](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability), [TTS 등록](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/app-registration), [언어/지역](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/set-locale).

## 검증 상태

3.0.1 로컬 검증: 단위 테스트 35개, 에뮬레이터 UI 테스트 4개 통과. Lint 오류 0, 경고 34. 물리 차량 주행 시험은 하지 않았습니다.

```powershell
.\gradlew.bat :careway:testDebugUnitTest :careway:lintDebug :careway:assembleDebug
.\gradlew.bat :careway:connectedDebugAndroidTest
```

기기 테스트는 Pleos 서비스가 준비된 에뮬레이터가 필요합니다. `SdkIntegrationTest`는 차량 제어·복원을 시험하므로 시험용 환경에서 실행합니다.

실제 SDK 관찰에서 양쪽 미러 변경·복원을 확인했습니다. 공조·제상·내외기·실내 조명은 요청 성공 응답 후에도 값 변경을 확인하지 못했습니다. 스마트글라스는 에뮬레이터에서 미지원이었습니다. 터널 출구·역광은 시뮬레이션으로 확인했습니다. SDK 전이 의존성 JNA의 16KB 페이지 호환성 경고가 남습니다.

## 프로젝트 구성

- `careway/src/main`: 화면, 반응 측정, 상황 판단, 차량 SDK 연결, 기록 저장
- `careway/src/test`: 정책·반응 측정·리포트 단위 테스트
- `careway/src/androidTest`: 화면·기록·SDK 기기 테스트
- `gradle`: 버전 목록, Wrapper, JVM 도구 체인 설정

APK는 `careway/build/outputs/apk/debug/careway-debug.apk`에 생성됩니다. 개인 SDK 경로, 서명 키, 빌드 결과물은 저장소에 포함하지 않습니다.
