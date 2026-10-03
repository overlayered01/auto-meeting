import 'package:flutter/material.dart';
import 'recording_lab.dart';

class CalendarPane extends StatelessWidget {
  const CalendarPane({super.key, required this.controller});
  final LabController controller;

  Future<void> _arm(BuildContext context) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('캘린더 자동 녹음을 켤까요?'),
        content: const SingleChildScrollView(
          child: Text(
            '선택한 캘린더에서 앞으로 시작하는 시간 지정 일정에 맞춰 주변 소리를 녹음합니다. '
            '이미 시작한 일정과 종일 일정은 자동 녹음하지 않습니다.\n\n'
            '말소리가 약 60초 동안 감지되지 않거나 일정이 끝나면 저장하고 종료합니다. '
            '종료된 같은 일정은 다시 시작하지 않습니다.\n\n'
            '화면을 끄거나 최근 앱 목록을 닫아도 대기를 유지하며 알림에서 끌 수 있습니다. '
            '대기는 최대 12시간, 한 녹음은 최대 4시간입니다. 강제 중지·재부팅 후에는 다시 켜야 합니다.\n\n'
            '음성 감지는 오판할 수 있습니다. 녹음할 사람에게 알리고 필요한 동의를 받은 일정에 사용하세요.',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('취소'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('자동 대기 켜기'),
          ),
        ],
      ),
    );
    if (confirmed == true) await controller.command('calendarArm');
  }

  @override
  Widget build(BuildContext context) {
    final state = controller.snapshot;
    final c = state.calendar;
    final working = controller.working || c.reading || c.launching;
    final canArm =
        state.supported &&
        state.microphone &&
        c.notifications &&
        c.permission &&
        c.exactAlarms &&
        c.selectedIds.isNotEmpty &&
        !state.busy &&
        !working;
    final future = c.events
        .where((e) => e.end.isAfter(DateTime.now()))
        .take(30)
        .toList();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Text(
          '내 캘린더,\n필요할 때만 녹음.',
          style: TextStyle(
            fontSize: 28,
            height: 1.3,
            fontWeight: FontWeight.w800,
          ),
        ),
        const SizedBox(height: 10),
        const Text('개인 일정도 포함합니다. 선택한 캘린더의 시작·종료 시간이 있는 일정만 사용합니다.'),
        const SizedBox(height: 20),
        Container(
          padding: const EdgeInsets.all(22),
          decoration: BoxDecoration(
            color: c.recording
                ? const Color(0xFF7D2834)
                : const Color(0xFF153F49),
            borderRadius: BorderRadius.circular(24),
          ),
          child: DefaultTextStyle(
            style: const TextStyle(color: Colors.white, height: 1.5),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  c.recording
                      ? (state.current?.status == 'recording'
                            ? '● 일정 녹음 중'
                            : '녹음 준비 중')
                      : c.armed
                      ? '캘린더 녹음 대기 중'
                      : '자동 대기가 꺼져 있습니다',
                  style: const TextStyle(
                    fontSize: 21,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 12),
                if (c.recording) ...[
                  Text(state.current?.eventTitle ?? '선택한 일정'),
                  Text(
                    c.speech
                        ? '말소리 감지 · 종료 타이머 초기화'
                        : '말소리 미감지 ${formatDuration(c.silenceMs, roundUp: false)} / 01:00',
                  ),
                  const SizedBox(height: 8),
                  LinearProgressIndicator(
                    value: (c.silenceMs / 60000).clamp(0, 1),
                    color: const Color(0xFFFFBCC4),
                    backgroundColor: Colors.white24,
                  ),
                ] else
                  Text(
                    c.armed
                        ? '대기 중에는 마이크로 소리를 수집하지 않습니다.'
                        : '앱을 연 상태에서 대기를 켜면 화면을 잠가도 일정 시작을 기다립니다.',
                  ),
                if (c.armed) Text('대기 종료 예정: ${_dateTime(c.sessionEnd)}'),
              ],
            ),
          ),
        ),
        if (c.error != null)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: Text(explainReason(c.error)),
          ),
        const SizedBox(height: 16),
        if (c.armed) ...[
          FilledButton.icon(
            key: const Key('calendar-disarm'),
            onPressed: working
                ? null
                : () => controller.command('calendarDisarm'),
            icon: const Icon(Icons.power_settings_new),
            label: const Text('자동 대기 끄기 · 녹음 저장'),
          ),
          if (c.recording)
            TextButton(
              onPressed: working
                  ? null
                  : () => controller.command('calendarStopRecording'),
              child: const Text('이 녹음만 종료 · 다음 일정 대기 유지'),
            ),
        ] else
          FilledButton.icon(
            key: const Key('calendar-arm'),
            onPressed: canArm ? () => _arm(context) : null,
            icon: const Icon(Icons.event_available),
            label: const Text('캘린더 자동 대기 켜기'),
          ),
        const SizedBox(height: 20),
        _panel([
          const Text(
            '1. 접근 권한',
            style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 8),
          Text(
            '마이크 ${state.microphone ? "허용" : "필요"} · 알림 ${c.notifications ? "허용" : "필요"} · 캘린더 ${c.permission ? "허용" : "필요"} · 정각 알람 ${c.exactAlarms ? "허용" : "필요"}',
          ),
          Wrap(
            spacing: 8,
            children: [
              if (!state.microphone || !c.notifications)
                TextButton(
                  onPressed: working || c.armed
                      ? null
                      : () => controller.command('requestPermissions'),
                  child: const Text('마이크·알림 허용'),
                ),
              if (!c.permission)
                TextButton(
                  onPressed: working || c.armed
                      ? null
                      : () => controller.command('calendarPermission'),
                  child: const Text('캘린더 읽기 허용'),
                ),
              if (!c.exactAlarms)
                TextButton(
                  onPressed: working || c.armed
                      ? null
                      : () => controller.command('calendarExactAlarmSettings'),
                  child: const Text('알람 및 리마인더 허용'),
                ),
              TextButton(
                onPressed: working
                    ? null
                    : () => controller.command('openSettings'),
                child: const Text('앱 설정'),
              ),
            ],
          ),
        ]),
        const SizedBox(height: 16),
        _panel([
          const Text(
            '2. 녹음할 캘린더 선택',
            style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 8),
          const Text(
            '여러 계정에서 선택할 수 있습니다. Google 계정은 휴대폰 설정에서 추가하고 캘린더 동기화·표시를 켜 주세요.',
          ),
          Wrap(
            spacing: 8,
            children: [
              TextButton.icon(
                onPressed: working || c.armed
                    ? null
                    : () => controller.command('calendarAccountSettings'),
                icon: const Icon(Icons.person_add_alt_1),
                label: const Text('Google 계정 추가'),
              ),
              TextButton.icon(
                key: const Key('calendar-refresh'),
                onPressed: working || !c.permission
                    ? null
                    : () => controller.command('calendarRefresh'),
                icon: const Icon(Icons.refresh),
                label: const Text('기기 일정 새로고침'),
              ),
            ],
          ),
          if (c.reading) const LinearProgressIndicator(),
          if (c.sources.isEmpty)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 12),
              child: Text('아직 읽은 캘린더가 없습니다. 권한을 허용하고 새로고침해 주세요.'),
            ),
          for (final source in c.sources)
            CheckboxListTile(
              key: ValueKey('calendar-${source.id}'),
              contentPadding: EdgeInsets.zero,
              controlAffinity: ListTileControlAffinity.leading,
              value: source.selected,
              title: Text(source.name),
              subtitle: Text(
                '${source.account}\n${source.owner ? "내 소유" : "접근 가능한 캘린더"} · ${source.accountType}',
              ),
              onChanged: working || c.armed
                  ? null
                  : (value) {
                      final ids = c.selectedIds;
                      if (value == true) {
                        ids.add(source.id);
                      } else {
                        ids.remove(source.id);
                      }
                      controller.command('calendarSelect', {
                        'ids': ids.toList(),
                      });
                    },
            ),
          if (c.armed) const Text('선택을 바꾸려면 자동 대기를 먼저 꺼 주세요.'),
          const Divider(height: 24),
          const Text('네이버 캘린더', style: TextStyle(fontWeight: FontWeight.w700)),
          const Text(
            '네이버 직접 로그인·일정 조회는 아직 연결되지 않았습니다. 네이버 앱에만 있는 일정은 이 목록에서 읽을 수 없습니다. '
            '공개 API는 일정 추가만 제공하며 Android CalDAV는 공식 미지원입니다. 기기 캘린더로 실제 동기화되어 목록에 표시되는 경우에만 선택할 수 있습니다.',
          ),
        ]),
        const SizedBox(height: 16),
        _panel([
          const Text(
            '3. 선택한 일정 확인',
            style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 8),
          Text(
            '기기 조회: ${_dateTime(c.readAt)} · 조회 범위: ${_dateTime(c.windowEnd)}까지',
          ),
          const Text('서버의 마지막 동기화 시각이 아닙니다. 잠금·절전 중 외부 일정 변경이 늦게 반영될 수 있습니다.'),
          if (future.isEmpty)
            const Padding(
              padding: EdgeInsets.only(top: 16),
              child: Text('선택한 캘린더에 조회된 시간 지정 일정이 없습니다.'),
            ),
          for (final event in future)
            ListTile(
              contentPadding: EdgeInsets.zero,
              title: Text(event.title),
              subtitle: Text(
                '${_dateTime(event.begin)} → ${_dateTime(event.end)}\n${event.calendarName} · ${event.account}',
              ),
            ),
          if (c.events.length > 30)
            const Text('가까운 일정 최대 30개 표시 · 예약 판단은 조회된 전체 일정에 적용'),
        ]),
        const SizedBox(height: 16),
        const Text(
          '녹음 종료: 말소리 미감지 60초 / 일정 종료 / 수동 종료 중 먼저 도달한 조건. '
          '처음부터 말소리가 없어도 약 1분 후 종료합니다. TV·스피커의 말소리도 음성으로 감지될 수 있습니다. '
          '겹친 별도 일정은 건너뛰며 녹음함에 이유를 남깁니다.\n\n'
          '일정 녹음은 기기 내 WAV(16kHz·모노, 약 1.9MB/분)로 저장합니다. 기존 테스트 M4A는 유지됩니다.',
        ),
      ],
    );
  }

  static Widget _panel(List<Widget> children) => Card(
    child: Padding(
      padding: const EdgeInsets.all(18),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: children,
      ),
    ),
  );
  static String _dateTime(DateTime? date) =>
      date == null ? '아직 없음' : '${date.month}/${date.day} ${formatTime(date)}';
}
