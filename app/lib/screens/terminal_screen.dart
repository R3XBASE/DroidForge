import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:droidforge/theme/droid_theme.dart';
import 'package:droidforge/state/app_state.dart';

/// Terminal Screen — full-screen terminal with command execution.
class TerminalScreen extends StatefulWidget {
  const TerminalScreen({super.key});

  @override
  State<TerminalScreen> createState() => _TerminalScreenState();
}

class _TerminalScreenState extends State<TerminalScreen> {
  final _controller = TextEditingController();
  final _scrollController = ScrollController();
  final _focusNode = FocusNode();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _focusNode.requestFocus();
    });
  }

  @override
  void dispose() {
    _controller.dispose();
    _scrollController.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  void _runCommand() async {
    final cmd = _controller.text.trim();
    if (cmd.isEmpty) return;
    _controller.clear();

    final state = context.read<AppState>();
    await state.executeCommand(cmd);

    // Auto-scroll
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          _scrollController.position.maxScrollExtent,
          duration: const Duration(milliseconds: 100),
          curve: Curves.easeOut,
        );
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final state = context.watch<AppState>();

    return Scaffold(
      backgroundColor: const Color(0xFF0D1117),
      appBar: AppBar(
        backgroundColor: const Color(0xFF161B22),
        title: Row(
          children: [
            const Icon(Icons.terminal_rounded, size: 18, color: DroidTheme.secondary),
            const SizedBox(width: 8),
            Text('Terminal', style: DroidTheme.headingSm),
            const Spacer(),
            Text(
              'proot · ${state.installedDistro}',
              style: DroidTheme.monoSm,
            ),
          ],
        ),
        actions: [
          IconButton(
            onPressed: () {
              state.interruptCommand();
              state.appendTerminalOutput('\n^C (interrupted)\n');
            },
            icon: const Icon(Icons.stop_circle_rounded, color: DroidTheme.error),
            tooltip: 'Interrupt (Ctrl+C)',
          ),
          IconButton(
            onPressed: () => state.clearTerminal(),
            icon: const Icon(Icons.delete_sweep_rounded, color: DroidTheme.textMuted),
            tooltip: 'Clear Terminal',
          ),
        ],
      ),
      body: Column(
        children: [
          // ── Output ──
          Expanded(
            child: GestureDetector(
              onTap: () => _focusNode.requestFocus(),
              child: ListView.builder(
                controller: _scrollController,
                padding: const EdgeInsets.all(12),
                itemCount: state.terminalOutput.length,
                itemBuilder: (context, index) {
                  final line = state.terminalOutput[index];
                  final isCommand = line.startsWith('\$');
                  return SelectableText(
                    line,
                    style: DroidTheme.mono.copyWith(
                      color: isCommand ? DroidTheme.accent : DroidTheme.textSecondary,
                      height: 1.4,
                    ),
                  );
                },
              ),
            ),
          ),

          // ── Input ──
          Container(
            padding: const EdgeInsets.fromLTRB(12, 8, 8, 16),
            decoration: const BoxDecoration(
              color: Color(0xFF0D0D0D),
              border: Border(top: BorderSide(color: DroidTheme.surfaceBorder)),
            ),
            child: SafeArea(
              top: false,
              child: Row(
                children: [
                  Text(
                    '\$ ',
                    style: DroidTheme.mono.copyWith(color: DroidTheme.accent),
                  ),
                  Expanded(
                    child: TextField(
                      controller: _controller,
                      focusNode: _focusNode,
                      style: DroidTheme.mono.copyWith(fontSize: 13),
                      decoration: const InputDecoration(
                        border: InputBorder.none,
                        hintText: 'Enter command...',
                        hintStyle: TextStyle(color: DroidTheme.textDim),
                        isDense: true,
                        contentPadding: EdgeInsets.zero,
                      ),
                      onSubmitted: (_) => _runCommand(),
                      textInputAction: TextInputAction.send,
                    ),
                  ),
                  IconButton(
                    onPressed: _runCommand,
                    icon: const Icon(Icons.send_rounded, size: 20),
                    color: DroidTheme.primary,
                    padding: EdgeInsets.zero,
                    constraints: const BoxConstraints(minWidth: 36, minHeight: 36),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
