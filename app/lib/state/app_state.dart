import 'package:flutter/material.dart';
import 'package:droidforge/services/platform_bridge.dart';

/// Central state management for the entire DroidForge app.
class AppState extends ChangeNotifier {
  // ── Setup State ──
  bool _isBootstrapped = false;
  bool _isRunning = false;
  String _installedDistro = '';
  String _installedDE = '';
  String _selectedDistro = 'ubuntu';
  String _selectedDE = 'xfce4';
  String _installType = 'minimal'; // 'minimal' or 'full'
  int _setupStep = 0;

  // ── Download/Install Progress ──
  double _downloadProgress = 0.0;
  String _downloadStatus = '';
  double _extractProgress = 0.0;
  String _extractStatus = '';
  bool _isDownloading = false;
  bool _isExtracting = false;
  String? _statusMessage;

  // ── Terminal ──
  final List<String> _terminalOutput = [
    'DroidForge Linux Terminal v2.0\nType commands below.\n',
  ];

  // ── Device Info ──
  Map<String, dynamic> _deviceInfo = {};

  // ── Error State ──
  String? _errorMessage;

  // ── Settings ──
  bool _autoSyncMenu = true;
  bool _autoStartAudio = true;
  int _vncWidth = 1920;
  int _vncHeight = 1080;

  // ── Getters ──
  bool get isBootstrapped => _isBootstrapped;
  bool get isRunning => _isRunning;
  String get installedDistro => _installedDistro;
  String get installedDE => _installedDE;
  String get selectedDistro => _selectedDistro;
  String get selectedDE => _selectedDE;
  String get installType => _installType;
  int get setupStep => _setupStep;
  double get downloadProgress => _downloadProgress;
  String get downloadStatus => _downloadStatus;
  double get extractProgress => _extractProgress;
  String get extractStatus => _extractStatus;
  String? get statusMessage => _statusMessage;
  bool get isDownloading => _isDownloading;
  bool get isExtracting => _isExtracting;
  List<String> get terminalOutput => _terminalOutput;
  Map<String, dynamic> get deviceInfo => _deviceInfo;
  String? get errorMessage => _errorMessage;
  bool get autoSyncMenu => _autoSyncMenu;
  bool get autoStartAudio => _autoStartAudio;
  int get vncWidth => _vncWidth;
  int get vncHeight => _vncHeight;

  bool get isSetupComplete => _isBootstrapped && _installedDistro.isNotEmpty;
  bool get isDEInstalled => _installedDE.isNotEmpty;

  String get gpuType {
    final vendor = _deviceInfo['gpuVendor']?.toString() ?? '';
    if (vendor.contains('adreno')) return 'Adreno (Snapdragon)';
    if (vendor.contains('mali')) return 'Mali (MediaTek/Exynos)';
    if (vendor.contains('powervr')) return 'PowerVR';
    return 'Unknown GPU';
  }

  String get gpuDriverType {
    final vendor = _deviceInfo['gpuVendor']?.toString() ?? '';
    if (vendor.contains('adreno')) return 'Turnip (HW accelerated)';
    if (vendor.contains('mali')) return 'Panfrost (partial HW)';
    return 'LLVMpipe (Software)';
  }

  String get distroFullName {
    switch (_installedDistro) {
      case 'ubuntu': return 'Ubuntu 24.04 LTS';
      case 'debian': return 'Debian 12 Bookworm';
      case 'kali-nethunter': return 'Kali Linux';
      case 'archlinux': return 'Arch Linux (Rolling)';
      case 'alpine': return 'Alpine Linux 3.20';
      default: return _installedDistro;
    }
  }

  String get deDisplayName {
    switch (_selectedDE) {
      case 'xfce4': return 'XFCE4';
      case 'lxqt': return 'LXQt';
      case 'mate': return 'MATE';
      case 'kde': return 'KDE Plasma';
      default: return _selectedDE;
    }
  }

  // ── Available Options ──
  List<Map<String, String>> get availableDistros => [
    {'id': 'ubuntu', 'name': 'Ubuntu 24.04', 'desc': 'Most popular, large package repo', 'color': '#E95420'},
    {'id': 'debian', 'name': 'Debian 12', 'desc': 'Stable, minimal footprint', 'color': '#A80030'},
    {'id': 'kali-nethunter', 'name': 'Kali Linux', 'desc': 'Security & pentesting tools', 'color': '#367BF0'},
    {'id': 'archlinux', 'name': 'Arch Linux', 'desc': 'Rolling release, bleeding edge', 'color': '#1793D1'},
    {'id': 'alpine', 'name': 'Alpine Linux', 'desc': 'Tiny footprint, musl-based', 'color': '#0D597F'},
  ];

  List<Map<String, String>> get availableDEs => [
    {'id': 'xfce4', 'name': 'XFCE4', 'desc': 'Fast & customizable (Recommended)', 'color': '#3D6FA5'},
    {'id': 'lxqt', 'name': 'LXQt', 'desc': 'Ultra lightweight', 'color': '#94B9E1'},
    {'id': 'mate', 'name': 'MATE', 'desc': 'Classic GNOME 2 style', 'color': '#4E9A29'},
    {'id': 'kde', 'name': 'KDE Plasma', 'desc': 'Full featured, modern', 'color': '#1D99F3'},
  ];

  // ── Initialization ──

  Future<void> initialize() async {
    // Set up progress callbacks
    DroidDeskPlatform.onDownloadProgress = (progress, status) {
      _downloadProgress = progress;
      _downloadStatus = status;
      if (progress < 0) {
        _isDownloading = false;
        _errorMessage = status;
      } else if (progress >= 1.0) {
        if (_isDownloading) {
          _isDownloading = false;
          runExtraction();
        }
      }
      notifyListeners();
    };

    DroidDeskPlatform.onExtractProgress = (progress, status) {
      _extractProgress = progress;
      _extractStatus = status;
      if (progress < 0) {
        _isExtracting = false;
        _errorMessage = status;
      } else if (progress >= 1.0) {
        if (_isExtracting) {
          _isExtracting = false;
          refreshStatus();
        }
      }
      notifyListeners();
    };

    DroidDeskPlatform.onInstallProgress = (progress, status) {
      _extractProgress = progress;
      _extractStatus = status;
      _statusMessage = status;
      if (progress < 0) {
        _isExtracting = false;
        _errorMessage = status;
      } else if (progress >= 1.0) {
        if (_isExtracting) {
          _isExtracting = false;
          refreshStatus();
        }
      }
      notifyListeners();
    };

    DroidDeskPlatform.onTerminalOutput = (text) {
      if (_terminalOutput.isEmpty) _terminalOutput.add('');

      final cleanedText = text.replaceAll(RegExp(r'.*\r(?!\n)'), '');
      final lines = cleanedText.split('\n');

      for (int i = 0; i < lines.length; i++) {
        if (i == 0) {
          _terminalOutput[_terminalOutput.length - 1] += lines[i];
        } else {
          _terminalOutput.add(lines[i]);
        }
      }
      notifyListeners();
    };

    await refreshStatus();
    await loadDeviceInfo();
  }

  Future<void> refreshStatus() async {
    try {
      final status = await DroidDeskPlatform.getRuntimeStatus();
      _isBootstrapped = status['isBootstrapped'] == true;
      _isRunning = status['isRunning'] == true;
      _installedDistro = status['distro']?.toString() ?? '';
      _installedDE = status['installedDE']?.toString() ?? '';
      notifyListeners();
    } catch (e) {
      _errorMessage = 'Failed to get runtime status: $e';
      notifyListeners();
    }
  }

  Future<void> loadDeviceInfo() async {
    try {
      _deviceInfo = await DroidDeskPlatform.getDeviceInfo();
      notifyListeners();
    } catch (e) {
      // Non-fatal
    }
  }

  // ── Setup Flow ──

  void setSelectedDistro(String distro) {
    _selectedDistro = distro;
    notifyListeners();
  }

  void setSelectedDE(String de) {
    _selectedDE = de;
    notifyListeners();
  }

  void setInstallType(String type) {
    _installType = type;
    notifyListeners();
  }

  void setSetupStep(int step) {
    _setupStep = step;
    _errorMessage = null;
    notifyListeners();
  }

  Future<void> runSetup() async {
    try {
      _errorMessage = null;
      _setupStep = 3;
      notifyListeners();
      await DroidDeskPlatform.setupBootstrap();

      _isDownloading = true;
      _downloadProgress = 0.0;
      notifyListeners();
      await DroidDeskPlatform.downloadRootfs(_selectedDistro);
    } catch (e) {
      _errorMessage = 'Setup failed: $e';
      _isDownloading = false;
      notifyListeners();
    }
  }

  Future<void> runExtraction() async {
    try {
      _isExtracting = true;
      _extractProgress = 0.0;
      _errorMessage = null;
      notifyListeners();
      await DroidDeskPlatform.extractRootfs(_selectedDistro);
    } catch (e) {
      _errorMessage = 'Extraction failed: $e';
      _isExtracting = false;
      notifyListeners();
    }
  }

  Future<void> installDesktopEnvironment() async {
    try {
      _isExtracting = true;
      _extractProgress = 0.0;
      _statusMessage = 'Installing Desktop Environment...';
      _errorMessage = null;
      notifyListeners();
      await DroidDeskPlatform.installDesktopEnvironment(
        _selectedDE,
        type: _installType,
      );
    } catch (e) {
      _errorMessage = 'Installation failed: $e';
      _isExtracting = false;
      notifyListeners();
    }
  }

  // ── Session Control ──

  Future<void> startLinux({
    String mode = 'vnc',
    int width = 1920,
    int height = 1080,
  }) async {
    try {
      _errorMessage = null;
      await DroidDeskPlatform.startLinux(
        de: _selectedDE,
        mode: mode,
        width: width,
        height: height,
      );
      _isRunning = true;
      notifyListeners();
    } catch (e) {
      _errorMessage = 'Failed to start: $e';
      notifyListeners();
    }
  }

  Future<void> launchDesktopActivity() async {
    try {
      await DroidDeskPlatform.launchDesktopActivity();
    } catch (e) {
      _errorMessage = 'Failed to launch desktop: $e';
      notifyListeners();
    }
  }

  Future<void> stopLinux() async {
    try {
      await DroidDeskPlatform.stopLinux();
      _isRunning = false;
      notifyListeners();
    } catch (e) {
      _errorMessage = 'Failed to stop: $e';
      notifyListeners();
    }
  }

  // ── Terminal ──

  Future<String> executeCommand(String command) async {
    try {
      _terminalOutput.add('\$ $command\n');
      notifyListeners();
      return await DroidDeskPlatform.executeCommand(command);
    } catch (e) {
      return "Error executing command: $e";
    }
  }

  void appendTerminalOutput(String output) {
    if (_terminalOutput.isEmpty) _terminalOutput.add('');
    _terminalOutput[_terminalOutput.length - 1] += output;
    notifyListeners();
  }

  void clearTerminal() {
    _terminalOutput.clear();
    _terminalOutput.add('DroidForge Linux Terminal v2.0\nType commands below.\n');
    notifyListeners();
  }

  Future<void> interruptCommand() async {
    try {
      await DroidDeskPlatform.interruptCommand();
    } catch (e) {
      debugPrint("Error interrupting: $e");
    }
  }

  // ── Settings ──

  void setAutoSyncMenu(bool value) {
    _autoSyncMenu = value;
    notifyListeners();
  }

  void setAutoStartAudio(bool value) {
    _autoStartAudio = value;
    notifyListeners();
  }

  void setVncResolution(int width, int height) {
    _vncWidth = width;
    _vncHeight = height;
    notifyListeners();
  }

  // ── Error Handling ──

  void clearError() {
    _errorMessage = null;
    notifyListeners();
  }

  void showError(String message) {
    _errorMessage = message;
    notifyListeners();
  }
}
