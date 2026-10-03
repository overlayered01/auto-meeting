import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'recording_lab.dart';
import 'calendar_page.dart';

void main() {
  LicenseRegistry.addLicense(() async* {
    yield LicenseEntryWithLineBreaks([
      'android-vad / Silero / ONNX Runtime',
    ], await rootBundle.loadString('assets/licenses/voice_detection.txt'));
  });
  runApp(const AutoMeetingApp());
}

class AutoMeetingApp extends StatelessWidget {
  const AutoMeetingApp({super.key, this.gateway});
  final LabGateway? gateway;

  @override
  Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    title: 'Auto Meeting Lab',
    theme: ThemeData(
      useMaterial3: true,
      colorScheme: ColorScheme.fromSeed(
        seedColor: const Color(0xFF244C5B),
        primary: const Color(0xFF214B59),
        surface: const Color(0xFFF6F7F5),
      ),
      scaffoldBackgroundColor: const Color(0xFFF6F7F5),
      cardTheme: CardThemeData(
        elevation: 0,
        color: Colors.white,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(22),
          side: const BorderSide(color: Color(0xFFE1E7E2)),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(minimumSize: const Size(48, 54)),
      ),
    ),
    home: RecordingLabPage(gateway: gateway ?? AndroidLabGateway()),
  );
}

class RecordingLabPage extends StatefulWidget {
  const RecordingLabPage({super.key, required this.gateway});
  final LabGateway gateway;
  @override
  State<RecordingLabPage> createState() => _RecordingLabPageState();
}

class _RecordingLabPageState extends State<RecordingLabPage>
    with WidgetsBindingObserver {
  late final LabController controller;
  Timer? poll;
  int tab = 0;
  int delaySeconds = 120;
  bool standby = false;

  @override
  void initState() {
    super.initState();
    controller = LabController(widget.gateway);
    WidgetsBinding.instance.addObserver(this);
    _poll();
  }

  void _poll() {
    poll?.cancel();
    unawaited(controller.refresh());
    poll = Timer.periodic(
      const Duration(milliseconds: 500),
      (_) => controller.refresh(),
    );
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _poll();
    } else {
      poll?.cancel();
    }
  }

  @override
  void dispose() {
    poll?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    controller.dispose();
    super.dispose();
  }

  Future<void> startTest() async {
    if (standby && delaySeconds > 0) {
      final proceed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('화면 잠금 후 시작 실험'),
          content: const Text(
            '예약 시각에 주변 소리 녹음을 시도합니다. 대기 중에는 소리를 수집하지 않습니다.\n\n'
            '지속 알림과 짧은 CPU 대기를 사용합니다. 기기별 성공 여부는 아직 검증되지 않았습니다.',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('취소'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('실험 시작'),
            ),
          ],
        ),
      );
      if (proceed != true || !mounted) return;
    }
    await controller.schedule(
      delaySeconds: delaySeconds,
      standby: standby && delaySeconds > 0,
    );
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: controller,
    builder: (context, _) {
      final snapshot = controller.snapshot;
      return Scaffold(
        appBar: AppBar(
          title: const Row(
            children: [
              Icon(Icons.graphic_eq_rounded, size: 25),
              SizedBox(width: 10),
              Expanded(
                child: Text(
                  'Auto Meeting',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(fontWeight: FontWeight.w700, fontSize: 20),
                ),
              ),
            ],
          ),
          actions: [
            const Chip(
              label: Text('LAB 02'),
              visualDensity: VisualDensity.compact,
            ),
            IconButton(
              tooltip: '상태 새로고침',
              onPressed: controller.refresh,
              icon: const Icon(Icons.refresh),
            ),
          ],
        ),
        bottomNavigationBar: NavigationBar(
          selectedIndex: tab,
          onDestinationSelected: (value) => setState(() => tab = value),
          destinations: const [
            NavigationDestination(
              icon: Icon(Icons.timer_outlined),
              selectedIcon: Icon(Icons.timer),
              label: '녹음 테스트',
            ),
            NavigationDestination(
              icon: Icon(Icons.calendar_month_outlined),
              selectedIcon: Icon(Icons.calendar_month),
              label: '캘린더',
            ),
            NavigationDestination(
              icon: Icon(Icons.folder_outlined),
              selectedIcon: Icon(Icons.folder),
              label: '녹음함',
            ),
            NavigationDestination(
              icon: Icon(Icons.fact_check_outlined),
              selectedIcon: Icon(Icons.fact_check),
              label: '진단',
            ),
          ],
        ),
        body: SafeArea(
          child: Align(
            alignment: Alignment.topCenter,
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 760),
              child: ListView(
                key: ValueKey(tab),
                padding: const EdgeInsets.fromLTRB(20, 16, 20, 28),
                children: [
                  if (controller.error != null || snapshot.lastError != null)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 16),
                      child: _note(
                        controller.error ?? explainReason(snapshot.lastError),
                        icon: Icons.error_outline,
                      ),
                    ),
                  if (!controller.initialized)
                    const LinearProgressIndicator()
                  else if (!snapshot.supported)
                    _note(
                      '이번 검증 앱의 녹음 기능은 Android용입니다. iOS는 프로젝트 구조만 준비되어 있습니다.',
                      icon: Icons.phone_android,
                    ),
                  const SizedBox(height: 12),
                  if (tab == 0) ..._testPage(snapshot),
                  if (tab == 1) CalendarPane(controller: controller),
                  if (tab == 2) ..._library(snapshot),
                  if (tab == 3) ..._diagnostics(snapshot),
                ],
              ),
            ),
          ),
        ),
      );
    },
  );

  List<Widget> _testPage(LabSnapshot snapshot) {
    if (snapshot.calendar.armed) {
      return [
        _note('캘린더 자동 대기가 켜져 있습니다. 별도의 테스트 녹음은 동시에 실행하지 않습니다.'),
        const SizedBox(height: 16),
        FilledButton(
          onPressed: () => setState(() => tab = 1),
          child: const Text('캘린더 녹음 상태 보기'),
        ),
      ];
    }
    final recording = snapshot.current?.status == 'recording' && snapshot.busy;
    final scheduled = snapshot.current?.status == 'scheduled' && snapshot.busy;
    return [
      const Text(
        '일정에 맞춰,\n기록을 시작하세요.',
        style: TextStyle(
          fontSize: 29,
          height: 1.3,
          fontWeight: FontWeight.w800,
        ),
      ),
      const SizedBox(height: 10),
      const Text(
        '짧은 녹음 테스트입니다. 실제 일정 자동 녹음은 캘린더 탭에서 켜 주세요.',
        style: TextStyle(color: Color(0xFF65756D), height: 1.5),
      ),
      const SizedBox(height: 24),
      Container(
        padding: const EdgeInsets.all(24),
        decoration: BoxDecoration(
          color: recording ? const Color(0xFF7D2834) : const Color(0xFF153F49),
          borderRadius: BorderRadius.circular(26),
        ),
        child: DefaultTextStyle(
          style: const TextStyle(color: Colors.white),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(
                    recording ? Icons.mic : Icons.schedule,
                    color: const Color(0xFFCDE9DA),
                    size: 20,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      recording
                          ? '녹음 중 · 자동 종료까지'
                          : scheduled
                          ? '예약 완료 · 시작까지'
                          : snapshot.launching
                          ? '서비스 시작 중'
                          : '예약 녹음 테스트',
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 22),
              Text(
                snapshot.busy
                    ? formatDuration(snapshot.remainingMs)
                    : delaySeconds == 0
                    ? '지금 시작'
                    : formatDuration(delaySeconds * 1000),
                style: const TextStyle(
                  fontSize: 44,
                  fontWeight: FontWeight.w600,
                  letterSpacing: 1,
                ),
              ),
              const SizedBox(height: 10),
              Text(
                recording
                    ? '녹음은 알림에서도 종료할 수 있습니다.'
                    : scheduled
                    ? (snapshot.current?.mode == 'standby'
                          ? '화면 잠금 실험 · 아직 소리를 수집하지 않습니다.'
                          : '앱을 열어 두세요. 화면을 벗어나면 예약을 취소합니다.')
                    : '시작 후 ${delaySeconds == 10 ? '10초' : '1분'} 녹음 · 기기에 저장',
                style: const TextStyle(color: Color(0xFFD5E2DF), height: 1.5),
              ),
              if (recording) ...[
                const SizedBox(height: 18),
                Semantics(
                  label: '마이크 입력 레벨',
                  child: LinearProgressIndicator(
                    value: (snapshot.amplitude / 32767).clamp(0.0, 1.0),
                    backgroundColor: Colors.white24,
                    color: const Color(0xFFFFBCC4),
                    minHeight: 6,
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
      const SizedBox(height: 20),
      if (!snapshot.busy)
        _panel([
          const Text(
            '테스트 시간',
            style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16),
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final preset in [
                (120, '2분 후 / 1분 녹음'),
                (10, '10초 후 / 10초 녹음'),
                (0, '즉시 / 1분 녹음'),
              ])
                ChoiceChip(
                  label: Text(preset.$2),
                  selected: delaySeconds == preset.$1,
                  onSelected: controller.working
                      ? null
                      : (_) => setState(() => delaySeconds = preset.$1),
                ),
            ],
          ),
          const SizedBox(height: 12),
          SwitchListTile.adaptive(
            contentPadding: EdgeInsets.zero,
            title: const Text(
              '화면 잠금 후 시작 실험',
              style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
            ),
            subtitle: const Text('실기기 미검증 · 기본값은 끔'),
            value: standby && delaySeconds > 0,
            onChanged: delaySeconds == 0 || controller.working
                ? null
                : (value) => setState(() => standby = value),
          ),
        ]),
      if (snapshot.supported &&
          (!snapshot.microphone || !snapshot.notifications)) ...[
        const SizedBox(height: 16),
        _panel([
          const Text(
            '녹음을 준비해 주세요',
            style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 8),
          Text(
            '마이크 ${snapshot.microphone ? '허용됨' : '권한 필요'} · 알림 ${snapshot.notifications ? '허용됨' : '권한 필요'}',
          ),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            children: [
              TextButton.icon(
                onPressed: controller.working || snapshot.busy
                    ? null
                    : () => controller.command('requestPermissions'),
                icon: const Icon(Icons.verified_user_outlined),
                label: const Text('권한 허용'),
              ),
              TextButton(
                onPressed: controller.working
                    ? null
                    : () => controller.command('openSettings'),
                child: const Text('앱 설정 열기'),
              ),
            ],
          ),
        ]),
      ],
      const SizedBox(height: 20),
      if (snapshot.busy)
        FilledButton.icon(
          onPressed: controller.working || snapshot.launching
              ? null
              : () => controller.command('stop'),
          icon: const Icon(Icons.stop_rounded),
          label: Text(recording ? '종료하고 저장' : '예약 취소'),
        )
      else
        FilledButton.icon(
          key: const Key('schedule-button'),
          onPressed: controller.canStart ? startTest : null,
          icon: const Icon(Icons.mic_none_rounded),
          label: Text(delaySeconds == 0 ? '지금 녹음 시작' : '예약 테스트 시작'),
        ),
      const SizedBox(height: 16),
      _note('테스트를 시작하면 주변 소리가 녹음됩니다. 파일 저장 후 직접 재생해 소리와 누락 여부를 확인해 주세요.'),
      if (!snapshot.busy && snapshot.records.isNotEmpty) ...[
        const SizedBox(height: 24),
        const Text(
          '최근 테스트',
          style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
        ),
        const SizedBox(height: 12),
        _recordCard(snapshot.records.first, snapshot),
      ],
    ];
  }

  List<Widget> _library(LabSnapshot snapshot) => [
    const Text(
      '녹음함',
      style: TextStyle(fontSize: 28, fontWeight: FontWeight.w800),
    ),
    const SizedBox(height: 8),
    Text('기록 ${snapshot.records.length}개 · 파일은 이 기기에 저장됩니다.'),
    const SizedBox(height: 20),
    if (snapshot.records.isEmpty)
      _panel([
        const Icon(
          Icons.audio_file_outlined,
          size: 40,
          color: Color(0xFF6A8178),
        ),
        const SizedBox(height: 16),
        const Text('아직 녹음한 파일이 없습니다.'),
        const SizedBox(height: 8),
        const Text('녹음 테스트를 마치면 결과가 여기에 표시됩니다.'),
      ]),
    for (final record in snapshot.records)
      Padding(
        padding: const EdgeInsets.only(bottom: 12),
        child: _recordCard(record, snapshot),
      ),
    const SizedBox(height: 8),
    _note(
      '앱을 삭제하거나 데이터를 지우면 녹음이 사라집니다. 보관할 파일은 먼저 내보내세요. 재생은 앱 화면을 벗어나면 중지됩니다.',
    ),
  ];

  Widget _recordCard(LabRecord record, LabSnapshot snapshot) {
    final playing = snapshot.playingId == record.id;
    final disabled = snapshot.busy || controller.working;
    return _panel([
      Wrap(
        spacing: 8,
        runSpacing: 8,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          Text(
            record.statusLabel,
            style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
          ),
          Chip(
            label: Text(
              record.mode == 'calendar'
                  ? '캘린더 · 음성 감지'
                  : record.mode == 'standby'
                  ? '잠금 실험'
                  : '기본 테스트',
            ),
            visualDensity: VisualDensity.compact,
          ),
        ],
      ),
      if (record.eventTitle != null) ...[
        const SizedBox(height: 8),
        Text(
          record.eventTitle!,
          style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w600),
        ),
        Text('${record.calendarName ?? ""} · ${record.sourceAccount ?? ""}'),
      ],
      Text(
        '${record.createdAt.month}/${record.createdAt.day} ${formatTime(record.createdAt)} · ${formatDuration(record.durationMs, roundUp: false)} · ${(record.bytes / 1024).toStringAsFixed(0)} KB',
      ),
      if (record.reason != null)
        Padding(
          padding: const EdgeInsets.only(top: 8),
          child: Text(
            explainReason(record.reason),
            style: const TextStyle(height: 1.5),
          ),
        ),
      if (record.hasFile && record.peakAmplitude == 0 && !record.isActive)
        const Padding(
          padding: EdgeInsets.only(top: 8),
          child: Text('입력 신호 미확인 · 재생하여 실제 소리를 확인하세요.'),
        ),
      const SizedBox(height: 8),
      Wrap(
        spacing: 4,
        runSpacing: 4,
        children: [
          TextButton.icon(
            onPressed: disabled || !record.canPlay
                ? null
                : () => controller.command(playing ? 'stopPlayback' : 'play', {
                    'id': record.id,
                  }),
            icon: Icon(
              playing ? Icons.stop_circle_outlined : Icons.play_circle_outline,
            ),
            label: Text(
              playing
                  ? snapshot.playbackState == 'loading'
                        ? '로딩 취소'
                        : '재생 중지'
                  : record.status == 'interrupted'
                  ? '복구 파일 재생 시도'
                  : '재생',
            ),
          ),
          TextButton.icon(
            onPressed: disabled || !record.canPlay
                ? null
                : () => controller.command('share', {'id': record.id}),
            icon: const Icon(Icons.ios_share),
            label: const Text('내보내기'),
          ),
          IconButton(
            tooltip: '테스트 기록 삭제',
            onPressed: disabled ? null : () => _delete(record),
            icon: const Icon(Icons.delete_outline),
          ),
        ],
      ),
      ExpansionTile(
        tilePadding: EdgeInsets.zero,
        childrenPadding: const EdgeInsets.only(bottom: 8),
        title: const Text('시간 기록', style: TextStyle(fontSize: 14)),
        children: [
          _detail('예약 시각', formatTime(record.scheduledAt)),
          _detail('실제 시작', formatTime(record.startedAt)),
          _detail('실제 종료', formatTime(record.endedAt)),
          _detail(
            '오디오 길이',
            '${(record.durationMs / 1000).toStringAsFixed(3)}초',
          ),
          _detail(
            '시작 차이',
            record.startedAt == null
                ? '시작하지 않음'
                : '${record.startedAt!.difference(record.scheduledAt).inMilliseconds} ms',
          ),
          _detail('최대 입력 신호', '${record.peakAmplitude} / 32767'),
        ],
      ),
    ]);
  }

  Future<void> _delete(LabRecord record) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('테스트 기록을 삭제할까요?'),
        content: const Text('연결된 녹음 파일도 삭제됩니다. 삭제 후 되돌릴 수 없습니다.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('취소'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('삭제'),
          ),
        ],
      ),
    );
    if (confirmed == true && mounted) {
      await controller.command('delete', {'id': record.id});
    }
  }

  List<Widget> _diagnostics(LabSnapshot snapshot) => [
    const Text(
      '검증 상태',
      style: TextStyle(fontSize: 28, fontWeight: FontWeight.w800),
    ),
    const SizedBox(height: 8),
    const Text('구현된 기능과 실제 기기 검증은 별도로 확인합니다.'),
    const SizedBox(height: 20),
    _panel([
      _detail('기기', snapshot.device),
      _detail('마이크', snapshot.microphone ? '권한 허용' : '미허용'),
      _detail('알림', snapshot.notifications ? '권한 허용' : '미허용'),
      _detail('파일 위치', snapshot.storage),
      _detail('기존 테스트 예약', '사용자 실행 서비스 · 최대 2분 대기'),
      _detail('화면 잠금 후 시작', '실험 구현 · 실제 음성 검증 필요'),
      _detail('Google 다중 계정', '기기 동기화 캘린더 읽기 · 계정 추가는 Android 설정'),
      _detail('네이버 직접 연결', '미연결 · 연동 방식 확인 필요'),
      _detail(
        '일정 조회',
        '기기 Calendar Provider · 최근 조회 ${formatTime(snapshot.calendar.readAt)}',
      ),
      _detail('캘린더 자동 대기', snapshot.calendar.armed ? '켜짐 · 최대 12시간' : '꺼짐'),
      _detail('음성 감지', '기기 내 Silero VAD · 60초 말소리 미감지 시 종료'),
      _detail('iOS 녹음', '아직 구현되지 않음'),
      TextButton(
        onPressed: () => showLicensePage(
          context: context,
          applicationName: 'Auto Meeting',
          applicationVersion: '0.2.0',
        ),
        child: const Text('오픈소스 라이선스'),
      ),
      if (snapshot.unreadableCount > 0)
        _detail('읽지 못한 기록', '${snapshot.unreadableCount}개 · 파일은 유지됨'),
    ]),
    const SizedBox(height: 16),
    _note(
      'OS가 앱을 종료하거나 기기를 재부팅하면 테스트를 자동 재시작하지 않습니다. '
      '캘린더 대기도 앱에서 다시 켜야 하며 장시간·절전 상태 성공률은 추가 검증이 필요합니다. '
      '다른 앱·통화의 내부 오디오가 아니라 기기 마이크의 주변 소리를 녹음합니다.',
    ),
    const SizedBox(height: 16),
    _note(
      '앱 자체 음성 업로드는 없습니다. Android 백업·기기 이전 제외 설정을 적용했습니다. '
      '기기별 백업 동작은 추가 확인이 필요합니다.',
    ),
    const SizedBox(height: 16),
    OutlinedButton.icon(
      onPressed: () async {
        final latest = snapshot.records.isEmpty ? null : snapshot.records.first;
        final report =
            'Auto Meeting Lab\n${snapshot.device}\n'
            'microphone=${snapshot.microphone}, notifications=${snapshot.notifications}\n'
            'latest=${latest?.status ?? 'none'}, mode=${latest?.mode ?? '-'}\n'
            'scheduled=${latest?.scheduledAt.toIso8601String() ?? '-'}\n'
            'started=${latest?.startedAt?.toIso8601String() ?? '-'}\n'
            'ended=${latest?.endedAt?.toIso8601String() ?? '-'}\n'
            'durationMs=${latest?.durationMs ?? 0}, bytes=${latest?.bytes ?? 0}\n'
            'peak=${latest?.peakAmplitude ?? 0}, reason=${latest?.reason ?? '-'}\n'
            'calendarArmed=${snapshot.calendar.armed}, calendarRead=${snapshot.calendar.readAt?.toIso8601String() ?? "-"}\n'
            'calendarError=${snapshot.calendar.error ?? "-"}, silenceMs=${snapshot.calendar.silenceMs}\n'
            '실제 음성 재생 확인: 사용자가 기록 필요';
        await Clipboard.setData(ClipboardData(text: report));
        if (mounted) {
          ScaffoldMessenger.of(
            context,
          ).showSnackBar(const SnackBar(content: Text('진단 내용을 복사했습니다.')));
        }
      },
      icon: const Icon(Icons.copy),
      label: const Text('최근 결과 복사'),
    ),
  ];

  Widget _panel(List<Widget> children) => Card(
    child: Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: children,
      ),
    ),
  );

  Widget _note(String message, {IconData icon = Icons.info_outline}) => Row(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Icon(icon, size: 19, color: const Color(0xFF65756D)),
      const SizedBox(width: 9),
      Expanded(
        child: Text(
          message,
          style: const TextStyle(
            fontSize: 13,
            color: Color(0xFF65756D),
            height: 1.5,
          ),
        ),
      ),
    ],
  );

  Widget _detail(String label, String value) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 7),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          width: 110,
          child: Text(label, style: const TextStyle(color: Color(0xFF65756D))),
        ),
        Expanded(
          child: Text(
            value,
            style: const TextStyle(fontWeight: FontWeight.w500),
          ),
        ),
      ],
    ),
  );
}
