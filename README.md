# DroidForge

<p align="center">
  <img src="app/assets/icons/logo.png" width="120" alt="DroidForge Logo">
</p>

<h3 align="center">Advanced Linux Desktop for Android</h3>

<p align="center">
  <strong>Multi-Distro · Multi-DE · GPU Accelerated · No Root Required</strong>
</p>

<p align="center">
  <a href="#features">Features</a> · 
  <a href="#quick-start">Quick Start</a> · 
  <a href="#standalone-app">Standalone App</a> · 
  <a href="#commands">Commands</a> · 
  <a href="#development">Development</a> ·
  <a href="#contributing">Contributing</a>
</p>

---

## What is DroidForge?

DroidForge is an advanced Linux desktop environment for Android. It runs a full Linux desktop (XFCE4, LXQt, MATE, or KDE Plasma) directly on your phone using Termux, with GPU acceleration, multi-distro support, and a beautiful Flutter-based management app.

**Run VS Code, Firefox, LibreOffice, Blender, Wireshark, Metasploit, local AI — anything that runs on Linux.**

Connect your phone to a monitor and it becomes a Linux PC. Unplug it and your entire setup comes with you.

## Features

- **5 Linux Distros**: Ubuntu 24.04, Debian 12, Kali Linux, Arch Linux, Alpine Linux
- **4 Desktop Environments**: XFCE4, LXQt, MATE, KDE Plasma
- **GPU Acceleration**: Turnip (Adreno), Panfrost (Mali), Zink (fallback)
- **No Root Required**: Everything runs in PRoot containers
- **App Bridge**: Proot apps automatically appear in desktop menu
- **Auto-Patcher**: Electron apps (VS Code, Chrome, Discord) auto-patched for root
- **Modern Dark Theme**: Adwaita-dark + Dracula terminal
- **VNC Support**: Connect from any device
- **Recovery Mode**: Automatic rollback on failures
- **Comprehensive Logging**: Full setup logs for debugging
- **Flutter App**: Beautiful Material 3 dark UI with animated setup wizard
- **Auto-Release CI/CD**: Build + release APK automatically via GitHub Actions

## Quick Start

### Option 1: Termux Setup (Recommended)

**Requirements:**
- Any Android phone (ARM64)
- [Termux](https://f-droid.org/en/packages/com.termux/) (from F-Droid, NOT Play Store)
- [Termux-X11](https://github.com/termux/termux-x11/releases/tag/nightly)

**Install:**

```bash
# One-liner setup
curl -sL https://raw.githubusercontent.com/R3XBASE/DroidForge/main/termux-linux-setup.sh -o setup.sh
bash setup.sh
```

The script will guide you through:
1. Choosing a distro (Ubuntu recommended)
2. Choosing a desktop environment (XFCE4 recommended)
3. Automatic installation with progress tracking

**Start Desktop:**
```bash
bash ~/start-x11.sh
# Then open the Termux-X11 app
```

### Option 2: Standalone App

Download the latest APK from the [Releases](https://github.com/R3XBASE/DroidForge/releases) tab. The app handles everything automatically:

1. Install the APK
2. Open DroidForge
3. Follow the setup wizard
4. Launch your Linux desktop

## Commands Reference

| Command | Description |
|---------|-------------|
| `bash ~/start-x11.sh` | Start desktop via Termux-X11 |
| `bash ~/start-vnc.sh` | Start desktop via VNC |
| `bash ~/start-proot.sh` | Open proot Linux shell |
| `bash ~/proot-menu-sync.sh` | Sync proot apps to desktop menu |
| `bash ~/droidforge-status.sh` | Check system status |
| `bash ~/stop-linux.sh` | Stop all sessions |
| `bash ~/start-recovery.sh` | Recovery mode |

## Raspberry Pi Monitor Bridge

For phones without USB-C display output:

```bash
# On your Raspberry Pi
curl -sL https://raw.githubusercontent.com/R3XBASE/DroidForge/main/scripts/pi-display-bridge.sh -o ~/pi-bridge.sh
chmod +x ~/pi-bridge.sh
bash ~/pi-bridge.sh
```

See [scripts/pi-display-bridge.sh](scripts/pi-display-bridge.sh) for full instructions.

## Development

### Building from Source

```bash
# Clone the repo
git clone https://github.com/R3XBASE/DroidForge.git
cd DroidForge/app

# Install dependencies
flutter pub get

# Run in debug mode
flutter run

# Build release APK
flutter build apk --release
```

### GitHub Actions (Auto-Release)

The project includes a full CI/CD pipeline that auto-releases APK on every push to `main`:

```
Push to main → Build APK → Auto-Release to GitHub
```

| Trigger | What Happens |
|---------|-------------|
| Push to `main` | Build + Auto-Release APK |
| Push to `develop` | Build only |
| Pull Request | Build only |
| Manual trigger | Build + optional release |

To trigger a release manually:
1. Go to GitHub → Actions → "Build & Release DroidForge APK"
2. Click "Run workflow"
3. Select build mode + toggle "Create Release"
4. Click "Run workflow"

### Project Structure

```
DroidForge/
├── .github/workflows/
│   └── build.yml              # CI/CD pipeline (auto-release)
├── app/                        # Flutter application
│   ├── android/
│   │   ├── app/src/main/
│   │   │   ├── AndroidManifest.xml
│   │   │   └── kotlin/com/droidforge/core/
│   │   │       ├── MainActivity.kt       # Flutter ↔ Kotlin bridge
│   │   │       ├── ProotManager.kt       # PRoot lifecycle
│   │   │       ├── RootfsManager.kt      # Rootfs download/extract
│   │   │       ├── VncManager.kt         # VNC/X11 session
│   │   │       ├── TerminalManager.kt    # In-app terminal
│   │   │       └── DeviceInfoHelper.kt   # Device detection
│   │   └── build.gradle.kts
│   ├── lib/
│   │   ├── main.dart
│   │   ├── config/constants.dart
│   │   ├── screens/
│   │   │   ├── welcome_screen.dart
│   │   │   ├── home_screen.dart
│   │   │   ├── terminal_screen.dart
│   │   │   ├── vnc_desktop_screen.dart
│   │   │   ├── settings_screen.dart
│   │   │   └── setup/
│   │   │       ├── distro_picker.dart
│   │   │       ├── de_picker.dart
│   │   │       ├── install_screen.dart
│   │   │       └── de_install_screen.dart
│   │   ├── services/platform_bridge.dart
│   │   ├── state/app_state.dart
│   │   └── theme/droid_theme.dart
│   └── pubspec.yaml
├── scripts/
│   ├── pi-display-bridge.sh
│   └── proot-menu-sync.sh
├── termux-linux-setup.sh
├── fetch_deps.sh
├── .gitignore
└── README.md
```

## Contributing

1. Fork the repository from [github.com/R3XBASE/DroidForge](https://github.com/R3XBASE/DroidForge)
2. Create a feature branch
3. Make your changes
4. Run `flutter analyze` and `flutter test`
5. Submit a pull request

## License

MIT License — see [LICENSE](LICENSE) for details.
