import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:percent_indicator/circular_percent_indicator.dart';
import 'package:droidforge/theme/droid_theme.dart';
import 'package:droidforge/state/app_state.dart';
import 'package:droidforge/screens/home_screen.dart';

/// Install screen — handles rootfs download, extraction, and DE install.
class InstallScreen extends StatefulWidget {
  const InstallScreen({super.key});

  @override
  State<InstallScreen> createState() => _InstallScreenState();
}

class _InstallScreenState extends State<InstallScreen> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      context.read<AppState>().runSetup();
    });
  }

  @override
  Widget build(BuildContext context) {
    final state = context.watch<AppState>();

    // Auto-navigate when setup is complete
    if (state.isSetupComplete && !state.isDownloading && !state.isExtracting) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        Navigator.pushAndRemoveUntil(
          context,
          MaterialPageRoute(builder: (_) => const HomeScreen()),
          (_) => false,
        );
      });
    }

    final progress = state.isDownloading
        ? state.downloadProgress
        : state.isExtracting
            ? state.extractProgress
            : 0.0;

    final statusText = state.isDownloading
        ? state.downloadStatus
        : state.isExtracting
            ? (state.extractStatus.isNotEmpty ? state.extractStatus : state.statusMessage ?? 'Processing...')
            : 'Preparing...';

    return Scaffold(
      body: Container(
        decoration: const BoxDecoration(gradient: DroidTheme.backgroundGradient),
        child: SafeArea(
          child: Padding(
            padding: const EdgeInsets.all(32),
            child: Column(
              children: [
                const Spacer(flex: 2),

                // ── Progress Circle ──
                CircularPercentIndicator(
                  radius: 80,
                  lineWidth: 8,
                  percent: progress.clamp(0.0, 1.0),
                  center: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        '${(progress * 100).toInt()}%',
                        style: DroidTheme.headingXl.copyWith(fontSize: 32),
                      ),
                      Text(
                        _stepLabel(state),
                        style: DroidTheme.bodySm,
                      ),
                    ],
                  ),
                  progressColor: DroidTheme.primary,
                  backgroundColor: DroidTheme.surfaceBorder,
                  circularStrokeCap: CircularStrokeCap.round,
                  animation: true,
                  animateFromLastPercent: true,
                  animationDuration: 300,
                ).animate().fadeIn(duration: 500.ms),

                const SizedBox(height: 32),

                // ── Status Text ──
                Text(
                  statusText,
                  style: DroidTheme.headingSm,
                  textAlign: TextAlign.center,
                ).animate().fadeIn(delay: 200.ms),

                const SizedBox(height: 12),

                // ── Detail Text ──
                Text(
                  _detailText(state),
                  style: DroidTheme.bodyMd,
                  textAlign: TextAlign.center,
                ),

                const Spacer(flex: 2),

                // ── Error Message ──
                if (state.errorMessage != null)
                  Container(
                    padding: const EdgeInsets.all(16),
                    decoration: BoxDecoration(
                      color: DroidTheme.error.withValues(alpha: 0.1),
                      borderRadius: BorderRadius.circular(DroidTheme.radiusMd),
                      border: Border.all(color: DroidTheme.error.withValues(alpha: 0.3)),
                    ),
                    child: Column(
                      children: [
                        Row(
                          children: [
                            const Icon(Icons.error_outline, color: DroidTheme.error, size: 20),
                            const SizedBox(width: 8),
                            Expanded(
                              child: Text(
                                state.errorMessage!,
                                style: DroidTheme.bodyMd.copyWith(color: DroidTheme.error),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 12),
                        Row(
                          children: [
                            Expanded(
                              child: OutlinedButton(
                                onPressed: () {
                                  state.clearError();
                                  state.runSetup();
                                },
                                child: const Text('Retry'),
                              ),
                            ),
                            const SizedBox(width: 8),
                            Expanded(
                              child: OutlinedButton(
                                onPressed: () => Navigator.pop(context),
                                child: const Text('Go Back'),
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ).animate().fadeIn(),

                // ── Progress Bar ──
                if (state.errorMessage == null) ...[
                  ClipRRect(
                    borderRadius: BorderRadius.circular(4),
                    child: LinearProgressIndicator(
                      value: progress.clamp(0.0, 1.0),
                      backgroundColor: DroidTheme.surfaceBorder,
                      valueColor: const AlwaysStoppedAnimation(DroidTheme.primary),
                      minHeight: 4,
                    ),
                  ),
                ],

                const SizedBox(height: 48),
              ],
            ),
          ),
        ),
      ),
    );
  }

  String _stepLabel(AppState state) {
    if (state.isDownloading) return 'Downloading';
    if (state.isExtracting) return 'Installing';
    return 'Ready';
  }

  String _detailText(AppState state) {
    if (state.isDownloading) {
      return 'Downloading ${state.distroFullName} rootfs...\nThis may take a few minutes depending on your connection.';
    }
    if (state.isExtracting) {
      return 'Extracting and configuring ${state.distroFullName}...\nAlmost done!';
    }
    return 'Setting up your Linux environment...';
  }
}
