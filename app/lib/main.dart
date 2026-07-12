import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:droidforge/theme/droid_theme.dart';
import 'package:droidforge/state/app_state.dart';
import 'package:droidforge/services/platform_bridge.dart';
import 'package:droidforge/screens/welcome_screen.dart';
import 'package:droidforge/screens/home_screen.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  DroidDeskPlatform.init();

  runApp(
    MultiProvider(
      providers: [
        ChangeNotifierProvider(create: (_) => AppState()),
      ],
      child: const DroidForgeApp(),
    ),
  );
}

class DroidForgeApp extends StatefulWidget {
  const DroidForgeApp({super.key});

  @override
  State<DroidForgeApp> createState() => _DroidForgeAppState();
}

class _DroidForgeAppState extends State<DroidForgeApp> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      context.read<AppState>().initialize();
    });
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'DroidForge',
      debugShowCheckedModeBanner: false,
      theme: DroidTheme.themeData,
      darkTheme: DroidTheme.themeData,
      themeMode: ThemeMode.dark,
      home: Consumer<AppState>(
        builder: (context, state, _) {
          if (state.isSetupComplete) {
            return const HomeScreen();
          }
          return const WelcomeScreen();
        },
      ),
    );
  }
}
