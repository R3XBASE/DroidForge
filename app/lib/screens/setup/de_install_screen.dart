import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:droidforge/theme/droid_theme.dart';
import 'package:droidforge/state/app_state.dart';
import 'package:droidforge/screens/vnc_desktop_screen.dart';

/// DE Install screen — handles installing desktop environment packages.
class DEInstallScreen extends StatelessWidget {
  const DEInstallScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final state = context.watch<AppState>();

    return Scaffold(
      body: Container(
        decoration: const BoxDecoration(gradient: DroidTheme.backgroundGradient),
        child: SafeArea(
          child: Padding(
            padding: const EdgeInsets.all(32),
            child: Column(
              children: [
                const Spacer(),

                // ── Icon ──
                Container(
                  width: 80,
                  height: 80,
                  decoration: BoxDecoration(
                    color: DroidTheme.secondary.withValues(alpha: 0.15),
                    borderRadius: BorderRadius.circular(20),
                  ),
                  child: const Icon(
                    Icons.desktop_windows_rounded,
                    color: DroidTheme.secondary,
                    size: 40,
                  ),
                ),

                const SizedBox(height: 24),

                Text(
                  'Install ${state.deDisplayName}',
                  style: DroidTheme.headingLg,
                  textAlign: TextAlign.center,
                ),

                const SizedBox(height: 12),

                Text(
                  'This will install the ${state.deDisplayName} desktop environment\n'
                  'inside your ${state.distroFullName} container.',
                  style: DroidTheme.bodyMd,
                  textAlign: TextAlign.center,
                ),

                const SizedBox(height: 24),

                // ── Install Options ──
                Row(
                  children: [
                    Expanded(
                      child: _OptionCard(
                        title: 'Minimal',
                        desc: 'Desktop only, ~500MB',
                        icon: Icons.flash_on_rounded,
                        color: DroidTheme.accent,
                        isSelected: state.installType == 'minimal',
                        onTap: () => state.setInstallType('minimal'),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: _OptionCard(
                        title: 'Full',
                        desc: 'Desktop + apps, ~1.5GB',
                        icon: Icons.inventory_2_rounded,
                        color: DroidTheme.primary,
                        isSelected: state.installType == 'full',
                        onTap: () => state.setInstallType('full'),
                      ),
                    ),
                  ],
                ),

                const Spacer(),

                // ── Progress ──
                if (state.isExtracting) ...[
                  ClipRRect(
                    borderRadius: BorderRadius.circular(4),
                    child: LinearProgressIndicator(
                      value: state.extractProgress.clamp(0.0, 1.0),
                      backgroundColor: DroidTheme.surfaceBorder,
                      valueColor: const AlwaysStoppedAnimation(DroidTheme.secondary),
                      minHeight: 4,
                    ),
                  ),
                  const SizedBox(height: 12),
                  Text(
                    state.statusMessage ?? 'Installing...',
                    style: DroidTheme.bodySm,
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 24),
                ],

                // ── Error ──
                if (state.errorMessage != null) ...[
                  Container(
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: DroidTheme.error.withValues(alpha: 0.1),
                      borderRadius: BorderRadius.circular(DroidTheme.radiusSm),
                    ),
                    child: Text(
                      state.errorMessage!,
                      style: DroidTheme.bodySm.copyWith(color: DroidTheme.error),
                      textAlign: TextAlign.center,
                    ),
                  ),
                  const SizedBox(height: 16),
                ],

                // ── Buttons ──
                SizedBox(
                  width: double.infinity,
                  height: 52,
                  child: ElevatedButton(
                    onPressed: state.isExtracting
                        ? null
                        : () async {
                            await state.installDesktopEnvironment();
                            if (context.mounted && state.isDEInstalled) {
                              ScaffoldMessenger.of(context).showSnackBar(
                                SnackBar(
                                  content: Text('${state.deDisplayName} installed successfully!'),
                                  backgroundColor: DroidTheme.success,
                                ),
                              );
                              Navigator.pop(context);
                            }
                          },
                    child: state.isExtracting
                        ? const SizedBox(
                            width: 20,
                            height: 20,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: Colors.white,
                            ),
                          )
                        : Text('Install ${state.deDisplayName}'),
                  ),
                ),

                const SizedBox(height: 12),

                TextButton(
                  onPressed: () => Navigator.pop(context),
                  child: const Text('Skip for now'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _OptionCard extends StatelessWidget {
  final String title;
  final String desc;
  final IconData icon;
  final Color color;
  final bool isSelected;
  final VoidCallback onTap;

  const _OptionCard({
    required this.title,
    required this.desc,
    required this.icon,
    required this.color,
    required this.isSelected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: isSelected ? color.withValues(alpha: 0.1) : DroidTheme.cardBg,
          borderRadius: BorderRadius.circular(DroidTheme.radiusMd),
          border: Border.all(
            color: isSelected ? color : DroidTheme.surfaceBorder,
            width: isSelected ? 2 : 1,
          ),
        ),
        child: Column(
          children: [
            Icon(icon, color: color, size: 28),
            const SizedBox(height: 8),
            Text(title, style: DroidTheme.headingSm),
            const SizedBox(height: 4),
            Text(desc, style: DroidTheme.bodySm, textAlign: TextAlign.center),
          ],
        ),
      ),
    );
  }
}
