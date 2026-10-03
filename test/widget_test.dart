import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:auto_meeting/main.dart';
import 'package:auto_meeting/recording_lab.dart';

class FakeGateway implements LabGateway {
  FakeGateway(this.state);
  LabSnapshot state;
  final calls = <(String, Map<String, Object?>?)>[];
  @override
  Future<LabSnapshot> snapshot() async => state;
  @override
  Future<void> command(String name, [Map<String, Object?>? arguments]) async {
    calls.add((name, arguments));
  }
}

void main() {
  const ready = LabSnapshot(
    supported: true,
    microphone: true,
    notifications: true,
  );

  testWidgets('microphone denial disables scheduling', (tester) async {
    await tester.pumpWidget(
      AutoMeetingApp(
        gateway: FakeGateway(
          const LabSnapshot(supported: true, notifications: true),
        ),
      ),
    );
    await tester.pump();
    await tester.scrollUntilVisible(
      find.byKey(const Key('schedule-button')),
      200,
    );
    final button = tester.widget<FilledButton>(
      find.byKey(const Key('schedule-button')),
    );
    expect(button.onPressed, isNull);
    expect(find.text('권한 허용'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('standard preset dispatches two minutes then one minute', (
    tester,
  ) async {
    final gateway = FakeGateway(ready);
    await tester.pumpWidget(AutoMeetingApp(gateway: gateway));
    await tester.pump();
    await tester.scrollUntilVisible(
      find.byKey(const Key('schedule-button')),
      200,
    );
    await tester.tap(find.byKey(const Key('schedule-button')));
    await tester.pump();
    expect(gateway.calls.single.$1, 'schedule');
    expect(gateway.calls.single.$2, {
      'delayMs': 120000,
      'durationMs': 60000,
      'mode': 'foreground',
    });
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('unsupported platforms never enable recording', (tester) async {
    await tester.pumpWidget(
      AutoMeetingApp(gateway: FakeGateway(const LabSnapshot())),
    );
    await tester.pump();
    expect(find.textContaining('이번 검증 앱의 녹음 기능은 Android용'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.byKey(const Key('schedule-button')),
      200,
    );
    expect(
      tester
          .widget<FilledButton>(find.byKey(const Key('schedule-button')))
          .onPressed,
      isNull,
    );
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('narrow phone with large text has no layout exceptions', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    tester.platformDispatcher.textScaleFactorTestValue = 1.6;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await tester.pumpWidget(AutoMeetingApp(gateway: FakeGateway(ready)));
    await tester.pump();
    await tester.scrollUntilVisible(
      find.byKey(const Key('schedule-button')),
      200,
    );
    expect(tester.takeException(), isNull);
    await tester.tap(find.text('진단'));
    await tester.pump();
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
  });

  test('missing start time stays unknown for interrupted records', () {
    final entry = LabRecord.fromMap({
      'id': 'test',
      'createdAt': 1000,
      'scheduledAt': 2000,
      'status': 'interrupted',
      'hasFile': true,
      'bytes': 128,
    });
    expect(entry.startedAt, isNull);
    expect(entry.statusLabel, '중단됨');
    expect(entry.canPlay, isTrue);
  });

  testWidgets('cancelling the standby confirmation never starts a service', (
    tester,
  ) async {
    final gateway = FakeGateway(ready);
    await tester.pumpWidget(AutoMeetingApp(gateway: gateway));
    await tester.pump();
    await tester.scrollUntilVisible(find.byType(SwitchListTile), 200);
    await Scrollable.ensureVisible(
      tester.element(find.byType(SwitchListTile)),
      alignment: 0.5,
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byType(SwitchListTile));
    await tester.pump();
    await tester.scrollUntilVisible(
      find.byKey(const Key('schedule-button')),
      200,
    );
    await tester.tap(find.byKey(const Key('schedule-button')));
    await tester.pumpAndSettle();
    expect(find.text('실험 시작'), findsOneWidget);
    expect(gateway.calls, isEmpty);
    await tester.tap(find.text('취소'));
    await tester.pumpAndSettle();
    expect(gateway.calls, isEmpty);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('saved audio can be played from the library', (tester) async {
    final gateway = FakeGateway(
      LabSnapshot(
        supported: true,
        microphone: true,
        notifications: true,
        records: [
          LabRecord.fromMap({
            'id': 'test',
            'createdAt': 1000,
            'scheduledAt': 2000,
            'status': 'saved',
            'hasFile': true,
            'bytes': 12000,
            'actualDurationMs': 10033,
          }),
        ],
      ),
    );
    await tester.pumpWidget(AutoMeetingApp(gateway: gateway));
    await tester.pump();
    await tester.tap(find.text('녹음함'));
    await tester.pump();
    await tester.tap(find.text('재생'));
    await tester.pump();
    expect(gateway.calls.single.$1, 'play');
    expect(gateway.calls.single.$2, {'id': 'test'});
    await tester.pumpWidget(const SizedBox());
  });

  test('countdowns round up but saved file durations do not gain a second', () {
    expect(formatDuration(10033), '00:11');
    expect(formatDuration(10033, roundUp: false), '00:10');
    expect(formatDuration(-1), '00:00');
  });

  test('active native session prevents scheduling twice', () async {
    final gateway = FakeGateway(
      const LabSnapshot(
        supported: true,
        microphone: true,
        notifications: true,
        busy: true,
      ),
    );
    final controller = LabController(gateway);
    await controller.refresh();
    await controller.schedule(delaySeconds: 120, standby: false);
    expect(gateway.calls, isEmpty);
    controller.dispose();
  });

  test('controller can be disposed while refresh is pending', () async {
    final gateway = DelayedGateway();
    final controller = LabController(gateway);
    final pending = controller.refresh();
    controller.dispose();
    gateway.result.complete(ready);
    await pending;
  });
}

class DelayedGateway implements LabGateway {
  final result = Completer<LabSnapshot>();
  @override
  Future<LabSnapshot> snapshot() => result.future;
  @override
  Future<void> command(String name, [Map<String, Object?>? arguments]) async {}
}
