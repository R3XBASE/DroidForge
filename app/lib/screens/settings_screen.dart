import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:droidforge/theme/droid_theme.dart';
import 'package:droidforge/state/app_state.dart';
import 'package:droidforge/services/platform_bridge.dart';

/// Settings Screen — app configuration and system options.
class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

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
                    Text('Settings', style: DroidTheme.headingLg),
                  ],
                ),
              ),

              const SizedBox(height: 16),

              // ── Settings List ──
              Expanded(
                child: ListView(
                  padding: const EdgeInsets.symmetric(horizontal: 24),
                  children: [
                    // ── General ──
                    _sectionHeader('GENERAL'),

                    _SettingsTile(
                      icon: Icons.battery_charging_full_rounded,
                      iconColor: DroidTheme.warning,
                      title: 'Battery Optimization',
                      subtitle: 'Disable to prevent session killing',
                      onTap: () async {
                        final result = await DroidDeskPlatform.requestBatteryOptimization();
                        if (context.mounted) {
                          ScaffoldMessenger.of(context).showSnackBar(
                            SnackBar(
                              content: const Text('Opened battery settings — add DroidForge to the ignore list'),
                              backgroundColor: DroidTheme.info,
                            ),
                          );
                        }
                      },
                    ),

                    _SettingsTile(
                      icon: Icons.sync_rounded,
                      iconColor: DroidTheme.secondary,
                      title: 'Auto-Sync Menu',
                      subtitle: state.autoSyncMenu ? 'Enabled' : 'Disabled',
                      trailing: Switch(
                        value: state.autoSyncMenu,
                        onChanged: (v) => state.setAutoSyncMenu(v),
                        activeThumbColor: DroidTheme.primary,
                      ),
                    ),

                    _SettingsTile(
                      icon: Icons.volume_up_rounded,
                      iconColor: DroidTheme.accent,
                      title: 'Auto-Start Audio',
                      subtitle: state.autoStartAudio ? 'Enabled' : 'Disabled',
                      trailing: Switch(
                        value: state.autoStartAudio,
                        onChanged: (v) => state.setAutoStartAudio(v),
                        activeThumbColor: DroidTheme.primary,
                      ),
                    ),

                    const SizedBox(height: 24),
                    _sectionHeader('DISPLAY'),

                    _SettingsTile(
                      icon: Icons.aspect_ratio_rounded,
                      iconColor: DroidTheme.info,
                      title: 'VNC Resolution',
                      subtitle: '${state.vncWidth}x${state.vncHeight}',
                      onTap: () => _showResolutionPicker(context, state),
                    ),

                    const SizedBox(height: 24),
                    _sectionHeader('ENVIRONMENT'),

                    _SettingsTile(
                      icon: Icons.refresh_rounded,
                      iconColor: DroidTheme.error,
                      title: 'Reinstall Linux',
                      subtitle: 'Re-download and set up rootfs',
                      onTap: () => _showReinstallDialog(context, state),
                    ),

                    _SettingsTile(
                      icon: Icons.sync_rounded,
                      iconColor: DroidTheme.secondary,
                      title: 'Sync Proot Apps',
                      subtitle: 'Resync app menu from container',
                      onTap: () async {
                        await state.executeCommand('bash ~/proot-menu-sync.sh');
                        if (context.mounted) {
                          ScaffoldMessenger.of(context).showSnackBar(
                            const SnackBar(content: Text('Menu synced')),
                          );
                        }
                      },
                    ),

                    const SizedBox(height: 24),
                    _sectionHeader('ABOUT'),

                    _SettingsTile(
                      icon: Icons.info_outline_rounded,
                      iconColor: DroidTheme.textMuted,
                      title: 'DroidForge',
                      subtitle: 'v2.0.0 — Advanced Linux Desktop for Android',
                    ),

                    const SizedBox(height: 32),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _sectionHeader(String title) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Text(title, style: DroidTheme.label),
    );
  }

  void _showResolutionPicker(BuildContext context, AppState state) {
    final resolutions = [
      [1280, 720],
      [1600, 900],
      [1920, 1080],
      [2560, 1440],
    ];

    showModalBottomSheet(
      context: context,
      backgroundColor: DroidTheme.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (context) => Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('VNC Resolution', style: DroidTheme.headingMd),
            const SizedBox(height: 16),
            ...resolutions.map((res) => ListTile(
                  title: Text('${res[0]}x${res[1]}'),
                  subtitle: Text(
                    res[0] == state.vncWidth ? 'Current' : '',
                    style: DroidTheme.bodySm.copyWith(color: DroidTheme.accent),
                  ),
                  trailing: res[0] == state.vncWidth
                      ? const Icon(Icons.check_circle, color: DroidTheme.accent)
                      : null,
                  onTap: () {
                    state.setVncResolution(res[0], res[1]);
                    Navigator.pop(context);
                  },
                )),
          ],
        ),
      ),
    );
  }

  void _showReinstallDialog(BuildContext context, AppState state) {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: DroidTheme.surface,
        title: const Text('Reinstall Linux?'),
        content: const Text(
          'This will re-download and set up the rootfs. '
          'Your installed packages may be lost.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            onPressed: () {
              Navigator.pop(context);
              // Trigger reinstall flow
              state.setSetupStep(0);
            },
            style: ElevatedButton.styleFrom(backgroundColor: DroidTheme.error),
            child: const Text('Reinstall'),
          ),
        ],
      ),
    );
  }
}

class _SettingsTile extends StatelessWidget {
  final IconData icon;
  final Color iconColor;
  final String title;
  final String subtitle;
  final Widget? trailing;
  final VoidCallback? onTap;

  const _SettingsTile({
    required this.icon,
    required this.iconColor,
    required this.title,
    required this.subtitle,
    this.trailing,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: Container(
        width: 40,
        height: 40,
        decoration: BoxDecoration(
          color: iconColor.withValues(alpha: 0.15),
          borderRadius: BorderRadius.circular(DroidTheme.radiusSm),
        ),
        child: Icon(icon, color: iconColor, size: 20),
      ),
      title: Text(title, style: DroidTheme.headingSm.copyWith(fontSize: 15)),
      subtitle: Text(subtitle, style: DroidTheme.bodySm),
      trailing: trailing ?? (onTap != null ? const Icon(Icons.chevron_right_rounded, color: DroidTheme.textDim) : null),
      onTap: onTap,
    );
  }
}
