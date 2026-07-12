import 'package:flutter/material.dart';
import 'package:droidforge/theme/droid_theme.dart';
import 'package:droidforge/services/platform_bridge.dart';

/// VNC Desktop Screen — renders the Linux desktop via VNC client.
class VncDesktopScreen extends StatefulWidget {
  const VncDesktopScreen({super.key});

  @override
  State<VncDesktopScreen> createState() => _VncDesktopScreenState();
}

class _VncDesktopScreenState extends State<VncDesktopScreen> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _launchDesktop();
    });
  }

  Future<void> _launchDesktop() async {
    try {
      await DroidDeskPlatform.launchDesktopActivity();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Failed to launch desktop: $e'),
            backgroundColor: DroidTheme.error,
          ),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: DroidTheme.background,
      appBar: AppBar(
        title: const Text('Linux Desktop'),
        backgroundColor: DroidTheme.surface,
        leading: IconButton(
          onPressed: () => Navigator.pop(context),
          icon: const Icon(Icons.arrow_back_rounded),
        ),
        actions: [
          IconButton(
            onPressed: () => _launchDesktop(),
            icon: const Icon(Icons.refresh_rounded),
            tooltip: 'Reconnect',
          ),
          IconButton(
            onPressed: () async {
              await DroidDeskPlatform.stopLinux();
              if (mounted) Navigator.pop(context);
            },
            icon: const Icon(Icons.stop_rounded),
            tooltip: 'Stop Desktop',
            color: DroidTheme.error,
          ),
        ],
      ),
      body: const Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.desktop_mac_rounded, size: 64, color: DroidTheme.textDim),
            SizedBox(height: 16),
            Text('Linux Desktop', style: DroidTheme.headingLg),
            SizedBox(height: 8),
            Text(
              'The VNC client is opening in a separate activity.\n'
              'Use the back button to return to DroidForge.',
              style: DroidTheme.bodyMd,
              textAlign: TextAlign.center,
            ),
            SizedBox(height: 24),
            Text(
              'Press the Stop button to shutdown the desktop.',
              style: DroidTheme.bodySm,
            ),
          ],
        ),
      ),
    );
  }
}
