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
  <a href="#app">Standalone App</a> · 
  <a href="#commands">Commands</a> · 
  <a href="#contributing">Contributing</a>
</p>

---

## What is DroidForge?

DroidForge is an advanced Linux desktop environment for Android. It runs a full Linux desktop (XFCE4, LXQt, MATE, or KDE Plasma) directly on your phone using Termux, with GPU acceleration, multi-distro support, and a beautiful Flutter-based management app.

**Run VS Code, Firefox, LibreOffice, Blender, Wireshark, Metasploit, local AI — anything that runs on Linux.**

Connect your phone to a monitor and it becomes a Linux PC. Unplug it and your entire setup comes with you.

## Features

### vs DroidDesk (What's Improved)

| Feature | DroidDesk | DroidForge |
|---------|-----------|------------|
| Desktop Environments | 1 (hardcoded XFCE4) | 4 (XFCE4/LXQt/MATE/KDE) |
| Linux Distros | 1 (hardcoded Ubuntu) | 5 (Ubuntu/Debian/Kali/Arch/Alpine) |
| DE Selection | None (hardcoded) | Interactive picker with RAM-aware recommendations |
| Distro Selection | None (hardcoded) | Interactive picker with descriptions |
| Error Recovery | None | Automatic rollback + recovery mode |
| Logging | Basic | Comprehensive log system |
| GPU Detection | Basic Adreno check | Adreno/Mali/PowerVR detection |
| Auto-patcher | Basic | Enhanced Electron app patching |
| Menu Sync | v3 | v5 with better app detection |
| App UI | Basic | Premium Material 3 dark UI |
| GitHub Actions | None | Full CI/CD pipeline |
| Setup Script | Monolithic | Modular with error trapping |
| Status Script | None | Full system status checker |
| VNC Config | Basic | Configurable resolution picker |
| Settings | Basic | Full settings screen |

### Core Features

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

## Quick Start

### Option 1: Termux Setup (Recommended)

**Requirements:**
- Any Android phone (ARM64)
- [Termux](https://f-droid.org/en/packages/com.termux/) (from F-Droid, NOT Play Store)
- [Termux-X11](https://github.com/termux/termux-x11/releases/tag/nightly)

**Install:**

```bash
# One-liner setup
curl -sL https://raw.githubusercontent.com/YOUR_USERNAME/DroidForge/main/termux-linux-setup.sh -o setup.sh
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

Download the latest APK from the [Releases](../../releases) tab. The app handles everything automatically:

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
curl -sL https://raw.githubusercontent.com/YOUR_USERNAME/DroidForge/main/scripts/pi-display-bridge.sh -o ~/pi-bridge.sh
chmod +x ~/pi-bridge.sh
bash ~/pi-bridge.sh
```

See [scripts/pi-display-bridge.sh](scripts/pi-display-bridge.sh) for full instructions.

## Development

### Building from Source

```bash
# Clone the repo
git clone https://github.com/YOUR_USERNAME/DroidForge.git
cd DroidForge/app

# Install dependencies
flutter pub get

# Run in debug mode
flutter run

# Build release APK
flutter build apk --release
```

### GitHub Actions

The project includes a full CI/CD pipeline:

- **On push to main**: Analyze + Build APK
- **On PR**: Analyze + Build (debug)
- **On release tag**: Build + Upload to GitHub Release

### Project Structure

```
DroidForge/
├── .github/workflows/
│   └── build.yml          # CI/CD pipeline
├── app/                    # Flutter application
│   ├── android/           # Android native code
│   ├── lib/
│   │   ├── main.dart      # App entry point
│   │   ├── screens/       # UI screens
│   │   ├── state/         # State management
│   │   ├── services/      # Platform bridge
│   │   └── theme/         # Design system
│   └── pubspec.yaml
├── scripts/
│   ├── pi-display-bridge.sh
│   └── proot-menu-sync.sh
├── termux-linux-setup.sh   # Main setup script
├── fetch_deps.sh           # Native dependency fetcher
└── README.md
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Run `flutter analyze` and `flutter test`
5. Submit a pull request

## Acknowledgments

Built on top of the work by [orailnoor](https://github.com/orailnoor/DroidDesk) — the original DroidDesk project.

## License

MIT License — see [LICENSE](LICENSE) for details.
