import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:droidforge/theme/droid_theme.dart';
import 'package:droidforge/state/app_state.dart';
import 'package:droidforge/screens/setup/de_picker.dart';

/// Distro selection screen — Step 1 of setup.
class DistroPickerScreen extends StatelessWidget {
  const DistroPickerScreen({super.key});

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
                    Text('Choose Distribution', style: DroidTheme.headingLg),
                  ],
                ),
              ),

              // ── Progress ──
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 16, 24, 0),
                child: Row(
                  children: List.generate(4, (i) {
                    final isActive = i <= 0;
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

              // ── Distro Cards ──
              Expanded(
                child: ListView.builder(
                  padding: const EdgeInsets.symmetric(horizontal: 24),
                  itemCount: state.availableDistros.length,
                  itemBuilder: (context, index) {
                    final distro = state.availableDistros[index];
                    final isSelected = state.selectedDistro == distro['id'];
                    final color = Color(
                      int.parse(distro['color']!.replaceFirst('#', '0xFF')),
                    );

                    return Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: GestureDetector(
                        onTap: () => state.setSelectedDistro(distro['id']!),
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
                                child: Center(
                                  child: Text(
                                    distro['name']![0],
                                    style: TextStyle(
                                      color: color,
                                      fontSize: 20,
                                      fontWeight: FontWeight.w800,
                                    ),
                                  ),
                                ),
                              ),
                              const SizedBox(width: 14),
                              Expanded(
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(distro['name']!, style: DroidTheme.headingSm),
                                    const SizedBox(height: 2),
                                    Text(distro['desc']!, style: DroidTheme.bodySm),
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
                        MaterialPageRoute(builder: (_) => const DEPickerScreen()),
                      );
                    },
                    child: const Text('Next: Choose Desktop'),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
