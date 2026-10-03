import 'package:auto_meeting/calendar_models.dart';
import 'package:auto_meeting/main.dart';
import 'package:auto_meeting/recording_lab.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

class CalendarFake implements LabGateway {
  CalendarFake(this.value);
  final LabSnapshot value;
  final calls = <(String, Map<String, Object?>?)>[];
  @override
  Future<LabSnapshot> snapshot() async => value;
  @override
  Future<void> command(String name, [Map<String, Object?>? arguments]) async {
    calls.add((name, arguments));
  }
}

CalendarSnapshot calendar({bool armed = false}) => CalendarSnapshot.fromMap({
  'permission': true,
  'exactAlarms': true,
  'notifications': true,
  'armed': armed,
  'calendars': [
    {
      'id': 'a',
      'name': 'Personal A',
      'account': 'a@example.test',
      'accountType': 'com.google',
      'owner': true,
      'selected': true,
    },
    {
      'id': 'b',
      'name': 'Personal B',
      'account': 'b@example.test',
      'accountType': 'com.google',
      'owner': true,
      'selected': false,
    },
  ],
});

Future<void> openCalendar(WidgetTester tester, CalendarFake fake) async {
  await tester.pumpWidget(AutoMeetingApp(gateway: fake));
  await tester.pump();
  await tester.tap(find.text('캘린더'));
  await tester.pump();
}

void main() {
  testWidgets('calendar arm needs calendar and exact alarm permissions', (
    tester,
  ) async {
    final fake = CalendarFake(
      const LabSnapshot(supported: true, microphone: true, notifications: true),
    );
    await openCalendar(tester, fake);
    await tester.scrollUntilVisible(find.byKey(const Key('calendar-arm')), 150);
    expect(
      tester
          .widget<FilledButton>(find.byKey(const Key('calendar-arm')))
          .onPressed,
      isNull,
    );
    expect(fake.calls, isEmpty);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('selecting a calendar preserves selection from another account', (
    tester,
  ) async {
    final fake = CalendarFake(
      LabSnapshot(
        supported: true,
        microphone: true,
        notifications: true,
        calendar: calendar(),
      ),
    );
    await openCalendar(tester, fake);
    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('calendar-b')),
      250,
    );
    await Scrollable.ensureVisible(
      tester.element(find.byKey(const ValueKey('calendar-b'))),
      alignment: 0.5,
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('calendar-b')));
    await tester.pump();
    expect(fake.calls.single.$1, 'calendarSelect');
    expect(fake.calls.single.$2!['ids'], ['a', 'b']);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets(
    'cancelling recording consent never arms the microphone service',
    (tester) async {
      final fake = CalendarFake(
        LabSnapshot(
          supported: true,
          microphone: true,
          notifications: true,
          calendar: calendar(),
        ),
      );
      await openCalendar(tester, fake);
      await tester.scrollUntilVisible(
        find.byKey(const Key('calendar-arm')),
        150,
      );
      await tester.tap(find.byKey(const Key('calendar-arm')));
      await tester.pumpAndSettle();
      expect(find.text('캘린더 자동 녹음을 켤까요?'), findsOneWidget);
      await tester.tap(find.text('취소'));
      await tester.pumpAndSettle();
      expect(fake.calls, isEmpty);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('calendar selection is locked while monitoring is armed', (
    tester,
  ) async {
    final fake = CalendarFake(
      LabSnapshot(supported: true, busy: true, calendar: calendar(armed: true)),
    );
    await openCalendar(tester, fake);
    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('calendar-a')),
      250,
    );
    expect(
      tester
          .widget<CheckboxListTile>(find.byKey(const ValueKey('calendar-a')))
          .onChanged,
      isNull,
    );
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('calendar page fits a narrow phone with large text', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.textScaleFactorTestValue = 1.6;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await openCalendar(
      tester,
      CalendarFake(LabSnapshot(supported: true, calendar: calendar())),
    );
    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('calendar-b')),
      250,
    );
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
  });

  test('new calendar audio metadata and old M4A records both parse', () {
    final old = LabRecord.fromMap({
      'id': 'test',
      'createdAt': 0,
      'scheduledAt': 0,
    });
    expect(old.extension, 'm4a');
    final record = LabRecord.fromMap({
      'id': 'test2',
      'createdAt': 0,
      'scheduledAt': 0,
      'mode': 'calendar',
      'extension': 'wav',
      'eventTitle': 'My appointment',
      'speechSeen': true,
    });
    expect(record.extension, 'wav');
    expect(record.eventTitle, 'My appointment');
    expect(record.speechSeen, isTrue);
  });
}
