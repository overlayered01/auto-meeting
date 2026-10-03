# 제3자 구성요소

기존 Flutter/Android 의존성 외에 음성 감지를 위해 다음 고정 버전을 사용한다. 앱 자체는 음성을 외부로 전송하지 않는다.

| 구성요소 | 사용 버전 | 라이선스 / 원문 |
|---|---|---|
| android-vad Silero wrapper | 2.0.10 | [README의 MIT 표기](https://github.com/gkonovalov/android-vad/tree/2.0.10) · Georgiy Konovalov |
| Silero VAD model | android-vad 2.0.10에 포함된 모델 | [Silero VAD MIT](https://github.com/snakers4/silero-vad/blob/master/LICENSE) |
| ONNX Runtime Android | 1.22.0 | [MIT](https://github.com/microsoft/onnxruntime/blob/v1.22.0/LICENSE) |

위 MIT 저작권·허가 고지는 `assets/licenses/voice_detection.txt`로 APK에 포함되며 `진단 → 오픈소스 라이선스`에서 확인할 수 있다. 일반 배포 전 전이 의존성까지 고지를 재점검해야 한다. 현재 APK는 개발용 검증 빌드다.
