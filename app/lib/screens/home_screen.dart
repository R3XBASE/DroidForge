import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:droidforge/theme/droid_theme.dart';
import 'package:droidforge/state/app_state.dart';
import 'package:droidforge/screens/vnc_desktop_screen.dart';
import 'package:droidforge/screens/setup/de_install_screen.dart';
import 'package:droidforge/screens/settings_screen.dart';
import 'package:droidforge/screens/terminal_screen.dart';

/// Home dashboard — central hub after setup is complete.
class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final state = context.watch<AppState>();

    return Scaffold(
      body: Container(
        decoration: const BoxDecoration(gradient: DroidTheme.backgroundGradient),
        child: SafeArea(
          child: CustomScrollView(
            slivers: [
              // ── App Bar ──
              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(24, 16, 24, 0),
                  child: Row(
                    children: [
                      Container(
                        width: 40,
                        height: 40,
                        decoration: BoxDecoration(
                          borderRadius: BorderRadius.circular(12),
                        ),
                        child: ClipRRect(
                          borderRadius: BorderRadius.circular(12),
                          child: Image.asset('assets/icons/logo.png', fit: BoxFit.cover),
                        ),
                      ),
                      const SizedBox(width: 12),
                      Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text('DroidForge', style: DroidTheme.headingSm),
                          Text(
                            state.isRunning ? 'Desktop Active' : 'Ready',
                            style: DroidTheme.bodySm.copyWith(
                              color: state.isRunning ? DroidTheme.accent : DroidTheme.textMuted,
                            ),
                          ),
                        ],
                      ),
                      const Spacer(),
                      // Status indicator
                      if (state.isRunning)
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                          decoration: BoxDecoration(
                            color: DroidTheme.accent.withValues(alpha: 0.15),
                            borderRadius: BorderRadius.circular(DroidTheme.radiusFull),
                            border: Border.all(color: DroidTheme.accent.withValues(alpha: 0.3)),
                          ),
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Container(
                                width: 6,
                                height: 6,
                                decoration: const BoxDecoration(
                                  shape: BoxShape.circle,
                                  color: DroidTheme.accent,
                                ),
                              ),
                              const SizedBox(width: 6),
                              Text('LIVE', style: DroidTheme.monoSm.copyWith(
                                color: DroidTheme.accent,
                                fontWeight: FontWeight.w700,
                              )),
                            ],
                          ),
                        ),
                      const SizedBox(width: 8),
                      IconButton(
                        onPressed: () => Navigator.push(
                          context,
                          MaterialPageRoute(builder: (_) => const SettingsScreen()),
                        ),
                        icon: const Icon(Icons.settings_rounded, color: DroidTheme.textMuted),
                      ),
                    ],
                  ),
                ),
              ),

              // ── Status Card ──
              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(24, 24, 24, 0),
                  child: _buildStatusCard(state)
                      .animate()
                      .fadeIn(duration: 500.ms)
                      .slideY(begin: 0.05, duration: 500.ms),
                ),
              ),

              // ── Quick Actions ──
              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(24, 20, 24, 0),
                  child: Text('QUICK ACTIONS', style: DroidTheme.label)
                      .animate().fadeIn(delay: 200.ms, duration: 400.ms),
                ),
              ),

              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(24, 12, 24, 0),
                  child: Column(
                    children: [
                      // ── Install Desktop ──
                      _ActionCard(
                        icon: state.isDEInstalled
                            ? Icons.check_circle_rounded
                            : Icons.download_rounded,
                        title: state.isDEInstalled
                            ? '${state.deDisplayName} Installed'
                            : 'Install ${state.deDisplayName}',
                        subtitle: state.isDEInstalled
                            ? 'Desktop environment and GUI tools are ready.'
                            : 'Install desktop environment packages (one-time setup)',
                        color: state.isDEInstalled ? DroidTheme.success : DroidTheme.secondary,
                        onTap: state.isDEInstalled
                            ? () {}
                            : () => Navigator.push(
                                  context,
                                  MaterialPageRoute(builder: (_) => const DEInstallScreen()),
                                ),
                      ),

                      const SizedBox(height: 10),

                      // ── Launch / Return Desktop ──
                      if (state.isRunning)
                        Padding(
                          padding: const EdgeInsets.only(bottom: 10),
                          child: _ActionCard(
                            icon: Icons.fullscreen_rounded,
                            title: 'Return to Desktop',
                            subtitle: '${state.deDisplayName} is running in background',
                            color: DroidTheme.primary,
                            gradient: DroidTheme.primaryGradient,
                            onTap: () => Navigator.push(
                              context,
                              MaterialPageRoute(builder: (_) => const VncDesktopScreen()),
                            ),
                          ),
                        ),

                      _ActionCard(
                        icon: state.isRunning
                            ? Icons.stop_circle_rounded
                            : Icons.desktop_mac_rounded,
                        title: state.isRunning ? 'Stop Server' : 'Launch Desktop',
                        subtitle: state.isRunning
                            ? 'Shutdown Linux environment'
                            : 'Start ${state.deDisplayName} desktop environment',
                        color: state.isRunning ? DroidTheme.error : DroidTheme.primary,
                        gradient: state.isRunning ? null : DroidTheme.primaryGradient,
                        onTap: () async {
                          if (state.isRunning) {
                            state.stopLinux();
                          } else {
                            if (!state.isDEInstalled) {
                              ScaffoldMessenger.of(context).showSnackBar(
                                SnackBar(
                                  content: const Text('Install Desktop Environment first'),
                                  backgroundColor: DroidTheme.error,
                                ),
                              );
                              return;
                            }
                            final size = MediaQuery.of(context).size;
                            final maxDim = size.width > size.height ? size.width : size.height;
                            final minDim = size.width < size.height ? size.width : size.height;

                            final vncWidth = state.vncWidth;
                            final vncHeight = (vncWidth * (minDim / maxDim)).toInt();

                            await state.startLinux(mode: 'vnc', width: vncWidth, height: vncHeight);
                            if (context.mounted) {
                              Navigator.push(
                                context,
                                MaterialPageRoute(builder: (_) => const VncDesktopScreen()),
                              );
                            }
                          }
                        },
                      ),

                      const SizedBox(height: 10),

                      // ── Terminal ──
                      _ActionCard(
                        icon: Icons.terminal_rounded,
                        title: 'Terminal',
                        subtitle: 'Open a Linux shell in the proot environment',
                        color: DroidTheme.secondary,
                        onTap: () => Navigator.push(
                          context,
                          MaterialPageRoute(builder: (_) => const TerminalScreen()),
                        ),
                      ),

                      const SizedBox(height: 10),

                      // ── Sync Menu ──
                      _ActionCard(
                        icon: Icons.sync_rounded,
                        title: 'Sync Proot Apps',
                        subtitle: 'Sync installed proot apps to desktop menu',
                        color: DroidTheme.info,
                        onTap: () async {
                          await state.executeCommand('bash ~/proot-menu-sync.sh');
                          if (context.mounted) {
                            ScaffoldMessenger.of(context).showSnackBar(
                              SnackBar(
                                content: const Text('Menu sync completed'),
                                backgroundColor: DroidTheme.surface,
                              ),
                            );
                          }
                        },
                      ),

                      const SizedBox(height: 10),

                      // ── System Monitor ──
                      _ActionCard(
                        icon: Icons.monitor_heart_rounded,
                        title: 'System Monitor',
                        subtitle: 'Check CPU, RAM, and storage usage',
                        color: DroidTheme.warning,
                        onTap: () {
                          Navigator.push(
                            context,
                            MaterialPageRoute(builder: (_) => const TerminalScreen()),
                          );
                          // Could auto-run htop here
                        },
                      ),
                    ]
                        .animate()
                        .fadeIn(delay: 300.ms, duration: 400.ms)
                        .slideY(begin: 0.05, duration: 400.ms),
                  ),
                ),
              ),

              // ── System Info ──
              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(24, 24, 24, 0),
                  child: Text('SYSTEM', style: DroidTheme.label)
                      .animate().fadeIn(delay: 500.ms, duration: 400.ms),
                ),
              ),

              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(24, 12, 24, 32),
                  child: Container(
                    padding: const EdgeInsets.all(16),
                    decoration: BoxDecoration(
                      color: DroidTheme.cardBg,
                      borderRadius: BorderRadius.circular(DroidTheme.radiusMd),
                      border: Border.all(color: DroidTheme.surfaceBorder),
                    ),
                    child: Column(
                      children: [
                        _infoRow('Distribution', state.distroFullName),
                        _divider(),
                        _infoRow('Desktop', state.deDisplayName),
                        _divider(),
                        _infoRow('GPU', state.gpuType),
                        _divider(),
                        _infoRow('Driver', state.gpuDriverType),
                        _divider(),
                        _infoRow(
                          'Device',
                          '${state.deviceInfo['brand'] ?? ''} ${state.deviceInfo['model'] ?? ''}',
                        ),
                        _divider(),
                        _infoRow(
                          'Android',
                          '${state.deviceInfo['androidVersion'] ?? ''} (SDK ${state.deviceInfo['sdkVersion'] ?? ''})',
                        ),
                        _divider(),
                        _infoRow('RAM', '${state.deviceInfo['totalRamMB'] ?? 'N/A'} MB'),
                        _divider(),
                        _infoRow(
                          'Storage Free',
                          '${state.deviceInfo['availableStorageMB'] ?? 'N/A'} MB',
                        ),
                      ],
                    ),
                  ).animate().fadeIn(delay: 600.ms, duration: 400.ms),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  // ── Status Card ──
  Widget _buildStatusCard(AppState state) {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        gradient: state.isRunning ? DroidTheme.successGradient : DroidTheme.cardGradient,
        borderRadius: BorderRadius.circular(DroidTheme.radiusLg),
        border: Border.all(
          color: state.isRunning
              ? DroidTheme.accent.withValues(alpha: 0.3)
              : DroidTheme.surfaceBorder,
        ),
      ),
      child: Row(
        children: [
          Container(
            width: 12,
            height: 12,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: state.isRunning ? DroidTheme.accent : DroidTheme.textDim,
              boxShadow: state.isRunning
                  ? [BoxShadow(color: DroidTheme.accent.withValues(alpha: 0.5), blurRadius: 10)]
                  : [],
            ),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  state.isRunning ? 'Desktop Active' : 'Desktop Idle',
                  style: DroidTheme.headingSm.copyWith(
                    color: state.isRunning ? DroidTheme.accent : DroidTheme.textPrimary,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  state.isRunning
                      ? '${state.deDisplayName} · ${state.distroFullName}'
                      : 'Tap "Launch Desktop" to start',
                  style: DroidTheme.bodySm,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  // ── Helpers ──
  Widget _infoRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        children: [
          Text(label, style: DroidTheme.bodySm),
          const Spacer(),
          Text(value, style: DroidTheme.monoSm.copyWith(color: DroidTheme.textSecondary)),
        ],
      ),
    );
  }

  Widget _divider() {
    return Divider(height: 1, color: DroidTheme.surfaceBorder.withValues(alpha: 0.5));
  }
}

// ── Action Card Widget ──
class _ActionCard extends StatelessWidget {
  final IconData icon;
  final String title;
  final String subtitle;
  final Color color;
  final Gradient? gradient;
  final VoidCallback onTap;

  const _ActionCard({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.color,
    this.gradient,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.all(18),
        decoration: BoxDecoration(
          gradient: gradient != null
              ? LinearGradient(
                  colors: [
                    color.withValues(alpha: 0.15),
                    color.withValues(alpha: 0.05),
                  ],
                )
              : null,
          color: gradient == null ? DroidTheme.cardBg : null,
          borderRadius: BorderRadius.circular(DroidTheme.radiusMd),
          border: Border.all(color: color.withValues(alpha: 0.3)),
        ),
        child: Row(
          children: [
            Container(
              width: 44,
              height: 44,
              decoration: BoxDecoration(
                color: color.withValues(alpha: 0.15),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Icon(icon, color: color, size: 22),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: DroidTheme.headingSm),
                  Text(subtitle, style: DroidTheme.bodySm, maxLines: 2, overflow: TextOverflow.ellipsis),
                ],
              ),
            ),
            Icon(Icons.chevron_right_rounded, color: DroidTheme.textDim),
          ],
        ),
      ),
    );
  }
}
