# Auto Meeting Lab

여러 계정의 캘린더 일정을 기준으로 녹음하는 Flutter/Android 앱입니다. 0.2.0에는 **기기에 동기화된 캘린더 선택 → 일정 시작 → 60초 말소리 미감지 또는 일정 종료 → 기기 저장** 흐름을 추가했습니다.

Google 계정은 Android에 등록된 계정을 이용합니다. **네이버 직접 로그인·일정 조회는 아직 연결되지 않았습니다.** 기기 캘린더에 실제로 노출된 일정만 읽을 수 있습니다. 연동 범위, 새 기능 사용법과 제한은 [캘린더 녹음 안내](docs/CALENDAR_RECORDING.md)를 먼저 확인하세요.

## 실행

Flutter 3.41.4 / Dart 3.11.1에서 개발했습니다. Android 8.0(API 26) 이상을 대상으로 빌드합니다. 물리적 마이크와 화면 잠금 동작은 실제 휴대폰에서 검증해야 합니다.

```powershell
flutter pub get
flutter devices
flutter run -d <Android-device-id>
```

APK 만들기:

```powershell
flutter build apk --debug
```

결과: `build/app/outputs/flutter-apk/app-debug.apk` (개발용 서명, 스토어 출시용 아님).

## 사용 방법

실제 일정 기반 녹음은 하단 **캘린더** 탭에서 권한 허용 → 캘린더 선택 → 자동 대기 켜기를 사용합니다. 아래 절차는 기존의 짧은 녹음 테스트입니다.

1. 앱의 `권한 허용`을 눌러 마이크와 알림을 허용합니다. 이미 거절했거나 알림 채널을 껐다면 `앱 설정 열기`에서 변경합니다.
2. `2분 후 / 1분 녹음`을 선택하고 `예약 테스트 시작`을 누릅니다.
3. 기본 모드에서는 녹음이 시작될 때까지 앱을 화면에 열어 둡니다. 시작 전에 앱을 벗어나거나 잠그면 예약이 취소됩니다.
4. 실제 녹음이 시작되면 타이머와 마이크 입력 레벨이 표시됩니다. 최대 1분 뒤 종료합니다. 앱 또는 지속 알림에서도 종료할 수 있습니다.
5. `녹음함`에서 재생·내보내기·삭제하고, `시간 기록`에서 예약/실제 시작/실제 종료를 비교합니다.
6. `진단`의 `최근 결과 복사`로 기기와 최근 결과를 복사할 수 있습니다. 실제 소리가 정상인지 확인한 결과는 사용자가 추가 기록해야 합니다.

빠른 반복 테스트는 `10초 후 / 10초 녹음`, 이미 시작한 녹음의 잠금 유지 테스트는 `즉시 / 1분 녹음`을 사용합니다.

`화면 잠금 후 시작 실험`은 사용자가 앱에서 미리 서비스를 실행한 상태로 마이크 수집 없이 기다렸다가 녹음을 시작해 보는 실험입니다. 추가 확인 창과 지속 알림을 표시합니다. 실제 기기의 성공률은 미검증입니다. 이 모드가 성공해도 종료된 앱의 무인 실행이나 장시간 캘린더 예약이 검증된 것은 아닙니다.

## 구현 범위

- Flutter: 테스트 시간 선택, 권한 안내, 상태·남은 시간, 녹음함, 진단
- Android/Kotlin: 마이크 Foreground Service, MediaRecorder/AAC/M4A, MediaPlayer 재생
- 예약/종료: 네이티브 단조 시계와 Handler, MediaRecorder 최대 녹음 시간 제한
- 대기 0~120초 / 녹음 10~60초로 제한, 제한 시간 있는 WakeLock 사용 후 해제
- 기본 모드의 백그라운드 전환 취소, 5초 초과 시작 지연 시 실행 생략
- 동시에 한 세션만 실행, 재생과 녹음의 동시 실행 차단
- 최근 앱 목록에서 제거하면 예약 취소 또는 진행 중 녹음 종료·저장
- 프로세스 종료 후 다음 실행에서 미완료 기록을 `중단됨`으로 표시, 자동 재녹음 없음
- iOS는 Flutter 프로젝트 구조만 존재하며 녹음 버튼은 비활성화
- Google 직접 OAuth 로그인은 구현하지 않음. 신규 캘린더 모드는 Android에 등록된 여러 계정의 동기화된 일정을 읽음
- 신규 캘린더 모드: 읽기 전용 Calendar Provider, 사용자 활성화 서비스, 정확한 알람, 기기 내 Silero VAD, WAV 저장
- 네이버 직접 연동은 미구현이며 별도 연동 경로 확인 필요

Flutter Platform Channel로 네이티브 서비스와 연결합니다. Flutter의 화면 타이머는 상태 표시만 담당하고 마이크의 시작·종료를 제어하지 않습니다. 신규 음성 감지는 android-vad/Silero 라이브러리를 사용합니다.

## 저장과 데이터

- 오디오: 앱 전용 `files/recordings/<UUID>.m4a`(기존 테스트), `<UUID>.wav`(신규 일정 녹음)
- 테스트 기록: `noBackupFilesDir/recording_journals/<UUID>.json`, AtomicFile로 교체 저장
- 파일명에 일정명이나 계정 이메일을 넣지 않음
- 앱의 서버 업로드 기능 없음, Android 클라우드 백업·기기 이전 제외 설정 적용
- 내보내기는 사용자 선택 시 FileProvider의 임시 읽기 권한으로 시스템 공유 화면 사용
- 앱 삭제/데이터 초기화 시 기록이 사라지므로 보관할 파일은 사전에 내보내기
- 비정상 종료된 M4A는 재생 불가능할 수 있음. `중단됨`을 `복구 성공`으로 표시하지 않음
- 입력 레벨이 0이거나 컨테이너가 정상이라는 사실만으로 실제 음성이 정상이라고 판단하지 않음

검증 앱은 소량의 테스트 기록을 JSON으로 보관합니다. 제품용 SQLite 데이터 모델과 계정별 녹음 메타데이터는 후속 단계에서 적용합니다.

## 검증

```powershell
flutter analyze
flutter test
```

Android 네이티브 정책 테스트(Java가 설정된 터미널):

```powershell
cd android
.\gradlew.bat :app:testDebugUnitTest
```

UI/정책 자동 테스트와 APK 빌드 성공은 실제 마이크 품질이나 잠금 상태의 성공을 뜻하지 않습니다. 테스트 절차와 결과는 [녹음 검증 기록](docs/RECORDING_LAB.md)을 참고하세요.

## 문서

- [제품 기획서](docs/PRODUCT_SPEC.md)
- [개발 진행 계획](docs/IMPLEMENTATION_PLAN.md)
- [캘린더 녹음·음성 감지](docs/CALENDAR_RECORDING.md)
- [제3자 구성요소](docs/THIRD_PARTY.md)
- [Flutter Platform Channels](https://docs.flutter.dev/platform-integration/platform-channels)
- [Android MediaRecorder](https://developer.android.com/media/platform/mediarecorder)
- [Android 마이크 서비스 시작 제한](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
