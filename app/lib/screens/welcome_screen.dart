import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:droidforge/theme/droid_theme.dart';
import 'package:droidforge/state/app_state.dart';
import 'package:droidforge/screens/setup/distro_picker.dart';

/// Welcome screen — first thing the user sees.
/// Premium, animated landing with the DroidForge brand.
class WelcomeScreen extends StatelessWidget {
  const WelcomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Container(
        decoration: const BoxDecoration(gradient: DroidTheme.backgroundGradient),
        child: SafeArea(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 32),
            child: Column(
              children: [
                const Spacer(flex: 2),

                // ── Logo ──
                Container(
                  width: 100,
                  height: 100,
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(28),
                    boxShadow: DroidTheme.glowShadow,
                  ),
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(28),
                    child: Image.asset('assets/icons/logo.png', fit: BoxFit.cover),
                  ),
                )
                    .animate()
                    .scale(begin: const Offset(0.5, 0.5), duration: 600.ms, curve: Curves.elasticOut)
                    .fadeIn(duration: 400.ms),

                const SizedBox(height: 32),

                // ── Title ──
                Text('DroidForge', style: DroidTheme.heading2xl)
                    .animate()
                    .fadeIn(delay: 200.ms, duration: 500.ms)
                    .slideY(begin: 0.3, duration: 500.ms, curve: Curves.easeOut),

                const SizedBox(height: 12),

                // ── Tagline ──
                Text(
                  'Advanced Linux Desktop for Android',
                  style: DroidTheme.bodyLg.copyWith(color: DroidTheme.textSecondary),
                )
                    .animate()
                    .fadeIn(delay: 400.ms, duration: 500.ms)
                    .slideY(begin: 0.3, duration: 500.ms, curve: Curves.easeOut),

                const SizedBox(height: 8),

                Text(
                  'Multi-Distro · Multi-DE · GPU Accelerated',
                  style: DroidTheme.bodySm.copyWith(
                    color: DroidTheme.secondary,
                    fontWeight: FontWeight.w500,
                    letterSpacing: 1.5,
                  ),
                ).animate().fadeIn(delay: 600.ms, duration: 500.ms),

                const Spacer(flex: 1),

                // ── Feature Grid ──
                _buildFeatureGrid()
                    .animate()
                    .fadeIn(delay: 800.ms, duration: 400.ms)
                    .slideX(begin: -0.1, duration: 400.ms),

                const Spacer(flex: 2),

                // ── Version Badge ──
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                  decoration: BoxDecoration(
                    color: DroidTheme.surfaceLight,
                    borderRadius: BorderRadius.circular(DroidTheme.radiusFull),
                    border: Border.all(color: DroidTheme.surfaceBorder),
                  ),
                  child: Text(
                    'v2.0.0',
                    style: DroidTheme.monoSm.copyWith(color: DroidTheme.textMuted),
                  ),
                ).animate().fadeIn(delay: 1000.ms, duration: 400.ms),

                const SizedBox(height: 16),

                // ── Get Started Button ──
                SizedBox(
                  width: double.infinity,
                  height: 56,
                  child: ElevatedButton(
                    onPressed: () {
                      Navigator.of(context).push(
                        PageRouteBuilder(
                          pageBuilder: (_, __, ___) => const DistroPickerScreen(),
                          transitionsBuilder: (_, animation, __, child) {
                            return FadeTransition(
                              opacity: animation,
                              child: SlideTransition(
                                position: Tween<Offset>(
                                  begin: const Offset(0, 0.05),
                                  end: Offset.zero,
                                ).animate(CurvedAnimation(parent: animation, curve: Curves.easeOut)),
                                child: child,
                              ),
                            );
                          },
                          transitionDuration: const Duration(milliseconds: 400),
                        ),
                      );
                    },
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Text('Get Started', style: DroidTheme.headingSm.copyWith(color: Colors.white)),
                        const SizedBox(width: 8),
                        const Icon(Icons.arrow_forward_rounded, size: 20),
                      ],
                    ),
                  ),
                )
                    .animate()
                    .fadeIn(delay: 1200.ms, duration: 500.ms)
                    .slideY(begin: 0.3, duration: 500.ms, curve: Curves.easeOut),

                const SizedBox(height: 48),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildFeatureGrid() {
    final features = [
      _FeatureData(Icons.storage_rounded, 'Multi-Distro', 'Ubuntu, Debian, Kali, Arch, Alpine'),
      _FeatureData(Icons.desktop_windows_rounded, 'Multi-DE', 'XFCE4, LXQt, MATE, KDE Plasma'),
      _FeatureData(Icons.speed_rounded, 'GPU Accelerated', 'Turnip, Panfrost, Zink drivers'),
      _FeatureData(Icons.security_rounded, 'No Root Needed', 'Sandboxed via PRoot containers'),
    ];

    return Column(
      children: [
        Row(
          children: [
            Expanded(child: _buildFeatureCard(features[0])),
            const SizedBox(width: 10),
            Expanded(child: _buildFeatureCard(features[1])),
          ],
        ),
        const SizedBox(height: 10),
        Row(
          children: [
            Expanded(child: _buildFeatureCard(features[2])),
            const SizedBox(width: 10),
            Expanded(child: _buildFeatureCard(features[3])),
          ],
        ),
      ],
    );
  }

  Widget _buildFeatureCard(_FeatureData feature) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: DroidTheme.cardBg,
        borderRadius: BorderRadius.circular(DroidTheme.radiusMd),
        border: Border.all(color: DroidTheme.surfaceBorder),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 32,
            height: 32,
            decoration: BoxDecoration(
              color: DroidTheme.primary.withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(DroidTheme.radiusSm),
            ),
            child: Icon(feature.icon, size: 18, color: DroidTheme.primary),
          ),
          const SizedBox(height: 10),
          Text(feature.title, style: DroidTheme.headingSm.copyWith(fontSize: 13)),
          const SizedBox(height: 4),
          Text(feature.subtitle, style: DroidTheme.bodySm.copyWith(fontSize: 10)),
        ],
      ),
    );
  }
}

class _FeatureData {
  final IconData icon;
  final String title;
  final String subtitle;
  const _FeatureData(this.icon, this.title, this.subtitle);
}
