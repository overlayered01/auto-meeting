import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'calendar_models.dart';

typedef JsonMap = Map<Object?, Object?>;

class LabRecord {
  LabRecord.fromMap(JsonMap data)
    : id = data['id'] as String,
      mode = data['mode'] as String? ?? 'foreground',
      status = data['status'] as String? ?? 'interrupted',
      createdAt = _date(data['createdAt'])!,
      scheduledAt = _date(data['scheduledAt'])!,
      startedAt = _date(data['startedAt']),
      endedAt = _date(data['endedAt']),
      durationMs = (data['actualDurationMs'] as num?)?.toInt() ?? 0,
      bytes = (data['bytes'] as num?)?.toInt() ?? 0,
      peakAmplitude = (data['peakAmplitude'] as num?)?.toInt() ?? 0,
      hasFile = data['hasFile'] == true,
      reason = data['reason'] as String?,
      eventTitle = data['eventTitle'] as String?,
      calendarName = data['calendarName'] as String?,
      sourceAccount = data['sourceAccount'] as String?,
      extension = data['extension'] as String? ?? 'm4a',
      speechSeen = data['speechSeen'] == true;

  final String id, mode, status;
  final DateTime createdAt, scheduledAt;
  final DateTime? startedAt, endedAt;
  final int durationMs, bytes, peakAmplitude;
  final bool hasFile;
  final String? reason;
  final String? eventTitle, calendarName, sourceAccount;
  final String extension;
  final bool speechSeen;

  static DateTime? _date(Object? value) =>
      value is num ? DateTime.fromMillisecondsSinceEpoch(value.toInt()) : null;

  bool get isActive => status == 'scheduled' || status == 'recording';
  bool get canPlay => hasFile && !isActive;
  String get statusLabel => switch (status) {
    'scheduled' => '예약 대기',
    'recording' => '녹음 중',
    'saved' => '저장 완료',
    'cancelled' => '예약 취소',
    'missed' => '시작 시각 놓침',
    'failed' => '실패',
    _ => '중단됨',
  };
}

class LabSnapshot {
  const LabSnapshot({
    this.supported = false,
    this.microphone = false,
    this.notifications = false,
    this.busy = false,
    this.launching = false,
    this.current,
    this.remainingMs = 0,
    this.amplitude = 0,
    this.records = const [],
    this.playingId,
    this.playbackState = 'stopped',
    this.device = 'Android 실기기에서 실행해 주세요',
    this.storage = '기기 연결 후 확인할 수 있습니다',
    this.lastError,
    this.unreadableCount = 0,
    this.calendar = const CalendarSnapshot(),
  });

  factory LabSnapshot.fromMap(JsonMap data) => LabSnapshot(
    supported: data['supported'] == true,
    microphone: data['microphone'] == true,
    notifications: data['notifications'] == true,
    busy: data['busy'] == true,
    launching: data['launching'] == true,
    current: data['current'] is Map
        ? LabRecord.fromMap(data['current'] as JsonMap)
        : null,
    remainingMs: (data['remainingMs'] as num?)?.toInt() ?? 0,
    amplitude: (data['amplitude'] as num?)?.toInt() ?? 0,
    records: (data['records'] as List? ?? [])
        .map((item) => LabRecord.fromMap(item as JsonMap))
        .toList(growable: false),
    playingId: data['playingId'] as String?,
    playbackState: data['playbackState'] as String? ?? 'stopped',
    device: data['device'] as String? ?? '알 수 없는 기기',
    storage: data['storage'] as String? ?? '',
    lastError: data['lastError'] as String?,
    unreadableCount: (data['unreadableCount'] as num?)?.toInt() ?? 0,
    calendar: data['calendar'] is Map
        ? CalendarSnapshot.fromMap(data['calendar'] as JsonMap)
        : const CalendarSnapshot(),
  );

  final bool supported, microphone, notifications, busy, launching;
  final LabRecord? current;
  final int remainingMs, amplitude, unreadableCount;
  final List<LabRecord> records;
  final String? playingId, lastError;
  final String playbackState, device, storage;
  final CalendarSnapshot calendar;
}

abstract interface class LabGateway {
  Future<LabSnapshot> snapshot();
  Future<void> command(String name, [Map<String, Object?>? arguments]);
}

class AndroidLabGateway implements LabGateway {
  static const _channel = MethodChannel('auto_meeting/recording_lab');

  @override
  Future<LabSnapshot> snapshot() async {
    if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) {
      return const LabSnapshot();
    }
    final data = await _channel.invokeMapMethod<Object?, Object?>('snapshot');
    return LabSnapshot.fromMap(data!);
  }

  @override
  Future<void> command(String name, [Map<String, Object?>? arguments]) async {
    if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) {
      throw PlatformException(code: 'unsupported');
    }
    await _channel.invokeMethod<void>(name, arguments);
  }
}

class LabController extends ChangeNotifier {
  LabController(this.gateway);
  final LabGateway gateway;
  LabSnapshot snapshot = const LabSnapshot();
  bool initialized = false;
  bool working = false;
  bool _refreshing = false;
  bool _disposed = false;
  String? error;

  bool get canStart =>
      initialized &&
      snapshot.supported &&
      snapshot.microphone &&
      snapshot.notifications &&
      !snapshot.busy &&
      !working;

  Future<void> refresh() async {
    if (_disposed || _refreshing) return;
    _refreshing = true;
    try {
      final updated = await gateway.snapshot();
      if (!_disposed) {
        snapshot = updated;
        initialized = true;
      }
    } catch (_) {
      if (!_disposed) error = '상태를 가져오지 못했습니다. 새로고침해 주세요.';
    } finally {
      _refreshing = false;
      if (!_disposed) notifyListeners();
    }
  }

  Future<void> command(String name, [Map<String, Object?>? arguments]) async {
    if (working || _disposed) return;
    working = true;
    error = null;
    notifyListeners();
    try {
      await gateway.command(name, arguments);
      await refresh();
    } on PlatformException catch (failure) {
      error = explainReason(failure.code);
    } catch (_) {
      error = '작업을 완료하지 못했습니다. 다시 시도해 주세요.';
    } finally {
      working = false;
      if (!_disposed) notifyListeners();
    }
  }

  Future<void> schedule({
    required int delaySeconds,
    required bool standby,
  }) async {
    if (!canStart) return;
    await command('schedule', {
      'delayMs': delaySeconds * 1000,
      'durationMs': delaySeconds == 10 ? 10000 : 60000,
      'mode': standby ? 'standby' : 'foreground',
    });
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}

String explainReason(String? code) => switch (code) {
  'speech_absent_60s' => '사람 말소리가 약 60초 동안 감지되지 않아 자동 종료했습니다.',
  'event_ended' ||
  'event_removed_or_ended' => '일정이 끝났거나 선택한 캘린더에서 변경·삭제되어 종료했습니다.',
  'overlapping_event' => '다른 일정의 녹음과 겹쳐 이 일정은 건너뛰었습니다.',
  'calendar_start_missed' => '일정 시작을 30초 넘게 놓쳐 녹음하지 않았습니다.',
  'recording_limit' => '한 파일의 최대 녹음 시간인 4시간에 도달했습니다.',
  'session_expired' => '자동 대기 세션 12시간이 끝났습니다. 다시 켜 주세요.',
  'monitor_stopped' => '자동 대기를 끄고 진행 중인 녹음을 저장했습니다.',
  'calendar_permission_required' => '캘린더 읽기 권한을 허용해 주세요.',
  'exact_alarm_required' => '일정 시각에 깨우려면 알람 및 리마인더 권한이 필요합니다.',
  'calendar_selection_required' => '녹음에 사용할 캘린더를 하나 이상 선택해 주세요.',
  'calendar_disarm_first' => '자동 대기를 끈 뒤 캘린더 선택을 변경해 주세요.',
  'calendar_query_failed' => '기기의 일정을 읽지 못했습니다. 이전 목록은 유지되며 새 녹음은 보류됩니다.',
  'calendar_selection_failed' => '캘린더 선택을 저장하지 못했습니다. 목록을 새로고침해 주세요.',
  'calendar_arm_failed' ||
  'calendar_schedule_failed' => '자동 대기를 시작하거나 예약하지 못했습니다. 권한과 진단을 확인해 주세요.',
  'calendar_rearm_required' => '대기 서비스가 없습니다. 앱에서 자동 대기를 다시 켜 주세요.',
  'microphone_silenced' => 'Android가 마이크 입력을 차단했습니다. 침묵으로 처리하지 않고 종료했습니다.',
  'audio_read_failed' || 'audio_read_stalled' => '마이크 데이터를 받지 못해 녹음을 중단했습니다.',
  'speech_capture_failed' => '음성 감지 또는 녹음 처리에 실패했습니다. 파일과 진단을 확인해 주세요.',
  'duration_reached' => '설정한 녹음 시간이 되어 자동 종료했습니다.',
  'user_stop' => '사용자가 종료했습니다.',
  'left_foreground' => '녹음 시작 전에 앱을 벗어나 예약을 취소했습니다.',
  'start_deadline_missed' => '시작이 5초 이상 지연되어 녹음하지 않았습니다.',
  'task_removed' => '최근 앱 목록에서 앱을 닫아 테스트를 종료했습니다.',
  'process_ended' => '앱 프로세스가 종료되었습니다. 남은 파일은 복구 여부를 직접 확인해 주세요.',
  'service_ended' => '녹음 서비스가 중단되었습니다.',
  'microphone_restricted' => '마이크 접근이 제한되었습니다. 권한과 실행 상태를 확인해 주세요.',
  'storage_low' => '저장 공간이 부족합니다. 최소 10MB를 확보해 주세요.',
  'recording_start_failed' => '녹음 시작에 실패했습니다. 마이크 점유나 기기 설정을 확인해 주세요.',
  'recorder_error' => '녹음 중 오류가 발생했습니다. 파일을 확인해 주세요.',
  'file_finalize_failed' => '오디오 파일을 정상적으로 마무리하지 못했습니다.',
  'playback_failed' => '재생할 수 없는 파일입니다. 녹음이 중단되었을 수 있습니다.',
  'metadata_write_failed' => '테스트 정보 저장에 실패했습니다. 오디오 파일은 남아 있을 수 있습니다.',
  'permissions_required' => '마이크와 알림 권한이 필요합니다.',
  'already_active' || 'starting' => '진행 중인 작업을 먼저 종료해 주세요.',
  'foreground_required' => '앱을 화면에 연 상태에서 시작해 주세요.',
  'file_missing' || 'not_found' => '녹음 파일을 찾을 수 없습니다.',
  'delete_failed' => '파일을 삭제하지 못했습니다.',
  'unsupported' => '이번 검증용 녹음 기능은 Android에서 실행할 수 있습니다.',
  null => '',
  _ => '작업에 실패했습니다. 권한과 기기 상태를 확인한 뒤 다시 시도해 주세요.',
};

String formatDuration(int milliseconds, {bool roundUp = true}) {
  final rawSeconds = milliseconds.clamp(0, 86400000) / 1000;
  final seconds = roundUp ? rawSeconds.ceil() : rawSeconds.floor();
  return '${(seconds ~/ 60).toString().padLeft(2, '0')}:${(seconds % 60).toString().padLeft(2, '0')}';
}

String formatTime(DateTime? value) => value == null
    ? '—'
    : '${value.hour.toString().padLeft(2, '0')}:${value.minute.toString().padLeft(2, '0')}:${value.second.toString().padLeft(2, '0')}';
