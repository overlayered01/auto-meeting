class CalendarSource {
  CalendarSource.fromMap(Map<Object?, Object?> data)
    : id = data['id'] as String,
      name = data['name'] as String? ?? '',
      account = data['account'] as String? ?? '',
      accountType = data['accountType'] as String? ?? '',
      owner = data['owner'] == true,
      selected = data['selected'] == true;
  final String id, name, account, accountType;
  final bool owner, selected;
}

class CalendarEvent {
  CalendarEvent.fromMap(Map<Object?, Object?> data)
    : key = data['key'] as String,
      title = data['title'] as String? ?? '',
      calendarName = data['calendarName'] as String? ?? '',
      account = data['account'] as String? ?? '',
      begin = DateTime.fromMillisecondsSinceEpoch(
        (data['begin'] as num).toInt(),
      ),
      end = DateTime.fromMillisecondsSinceEpoch((data['end'] as num).toInt());
  final String key, title, calendarName, account;
  final DateTime begin, end;
}

class CalendarSnapshot {
  const CalendarSnapshot({
    this.permission = false,
    this.exactAlarms = false,
    this.notifications = false,
    this.armed = false,
    this.recording = false,
    this.launching = false,
    this.reading = false,
    this.speech = false,
    this.silenceMs = 0,
    this.sources = const [],
    this.events = const [],
    this.armedAt,
    this.sessionEnd,
    this.readAt,
    this.windowEnd,
    this.error,
  });
  factory CalendarSnapshot.fromMap(Map<Object?, Object?> data) =>
      CalendarSnapshot(
        permission: data['permission'] == true,
        exactAlarms: data['exactAlarms'] == true,
        notifications: data['notifications'] == true,
        armed: data['armed'] == true,
        recording: data['recording'] == true,
        launching: data['launching'] == true,
        reading: data['reading'] == true,
        speech: data['speech'] == true,
        silenceMs: (data['silenceMs'] as num?)?.toInt() ?? 0,
        sources: (data['calendars'] as List? ?? [])
            .map((e) => CalendarSource.fromMap(e as Map<Object?, Object?>))
            .toList(),
        events: (data['events'] as List? ?? [])
            .map((e) => CalendarEvent.fromMap(e as Map<Object?, Object?>))
            .toList(),
        armedAt: date(data['armedAt']),
        sessionEnd: date(data['sessionEnd']),
        readAt: date(data['readAt']),
        windowEnd: date(data['windowEnd']),
        error: data['error'] as String?,
      );
  final bool permission,
      exactAlarms,
      notifications,
      armed,
      recording,
      launching,
      reading,
      speech;
  final int silenceMs;
  final List<CalendarSource> sources;
  final List<CalendarEvent> events;
  final DateTime? armedAt, sessionEnd, readAt, windowEnd;
  final String? error;
  Set<String> get selectedIds =>
      sources.where((s) => s.selected).map((s) => s.id).toSet();
  static DateTime? date(Object? value) =>
      value is num ? DateTime.fromMillisecondsSinceEpoch(value.toInt()) : null;
}
