import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:droidforge/theme/droid_theme.dart';
import 'package:droidforge/state/app_state.dart';
import 'package:droidforge/screens/setup/install_screen.dart';

/// Desktop Environment selection screen — Step 2 of setup.
class DEPickerScreen extends StatelessWidget {
  const DEPickerScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final state = context.watch<AppState>();

    return Scaffold(
      body: Container(
        decoration: const BoxDecoration(gradient: DroidTheme.backgroundGradient),
        child: SafeArea(
          child: Column(
            children: [
              // ── Header ──
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 16, 24, 0),
                child: Row(
                  children: [
                    IconButton(
                      onPressed: () => Navigator.pop(context),
                      icon: const Icon(Icons.arrow_back_ios_rounded, size: 20),
                    ),
                    const SizedBox(width: 8),
                    Text('Choose Desktop', style: DroidTheme.headingLg),
                  ],
                ),
              ),

              // ── Progress ──
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 16, 24, 0),
                child: Row(
                  children: List.generate(4, (i) {
                    final isActive = i <= 1;
                    return Expanded(
                      child: Container(
                        height: 3,
                        margin: const EdgeInsets.symmetric(horizontal: 2),
                        decoration: BoxDecoration(
                          color: isActive ? DroidTheme.primary : DroidTheme.surfaceBorder,
                          borderRadius: BorderRadius.circular(2),
                        ),
                      ),
                    );
                  }),
                ),
              ),

              const SizedBox(height: 24),

              // ── DE Cards ──
              Expanded(
                child: ListView.builder(
                  padding: const EdgeInsets.symmetric(horizontal: 24),
                  itemCount: state.availableDEs.length,
                  itemBuilder: (context, index) {
                    final de = state.availableDEs[index];
                    final isSelected = state.selectedDE == de['id'];
                    final color = Color(
                      int.parse(de['color']!.replaceFirst('#', '0xFF')),
                    );

                    return Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: GestureDetector(
                        onTap: () => state.setSelectedDE(de['id']!),
                        child: AnimatedContainer(
                          duration: const Duration(milliseconds: 200),
                          padding: const EdgeInsets.all(16),
                          decoration: BoxDecoration(
                            color: isSelected
                                ? color.withValues(alpha: 0.1)
                                : DroidTheme.cardBg,
                            borderRadius: BorderRadius.circular(DroidTheme.radiusMd),
                            border: Border.all(
                              color: isSelected ? color : DroidTheme.surfaceBorder,
                              width: isSelected ? 2 : 1,
                            ),
                          ),
                          child: Row(
                            children: [
                              Container(
                                width: 44,
                                height: 44,
                                decoration: BoxDecoration(
                                  color: color.withValues(alpha: 0.15),
                                  borderRadius: BorderRadius.circular(DroidTheme.radiusSm),
                                ),
                                child: Icon(
                                  _deIcon(de['id']!),
                                  color: color,
                                  size: 22,
                                ),
                              ),
                              const SizedBox(width: 14),
                              Expanded(
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(de['name']!, style: DroidTheme.headingSm),
                                    const SizedBox(height: 2),
                                    Text(de['desc']!, style: DroidTheme.bodySm),
                                  ],
                                ),
                              ),
                              if (isSelected)
                                Icon(Icons.check_circle_rounded, color: color, size: 22)
                              else
                                Icon(Icons.circle_outlined, color: DroidTheme.textDim, size: 22),
                            ],
                          ),
                        ),
                      ).animate().fadeIn(
                            delay: Duration(milliseconds: 100 * index),
                            duration: 300.ms,
                          ),
                    );
                  },
                ),
              ),

              // ── Next Button ──
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
                child: SizedBox(
                  width: double.infinity,
                  height: 52,
                  child: ElevatedButton(
                    onPressed: () {
                      Navigator.push(
                        context,
                        MaterialPageRoute(builder: (_) => const InstallScreen()),
                      );
                    },
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        const Text('Install '),
                        Text(
                          '${state.distroFullName} + ${state.deDisplayName}',
                          style: const TextStyle(fontWeight: FontWeight.w800),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  IconData _deIcon(String deId) {
    switch (deId) {
      case 'xfce4': return Icons.desktop_windows_rounded;
      case 'lxqt': return Icons.computer_rounded;
      case 'mate': return Icons.window_rounded;
      case 'kde': return Icons.dashboard_rounded;
      default: return Icons.desktop_mac_rounded;
    }
  }
}
