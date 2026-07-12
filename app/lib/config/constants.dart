// DroidForge Configuration Constants
// Central config for API URLs, paths, and default values.

class DroidConfig {
  // Distro rootfs download URLs
  static const Map<String, String> distroUrls = {
    'ubuntu':
        'https://cloud-images.ubuntu.com/releases/24.04/release/ubuntu-24.04-server-cloudimg-arm64-root.tar.xz',
    'debian':
        'https://cdimage.debian.org/cdimage/cloud/bookworm/daily/latest/debian-12-generic-arm64-daily.root.tar.xz',
    'kali-nethunter':
        'https://images.kali.org/kali-2024.2-kali-rootfs-arm64.tar.xz',
    'archlinux':
        'https://gitlab.archlinux.org/archlinux/archlinux-docker/-/packages/20849842/raw/latest/file/RootFs-x86_64.tar.xz',
    'alpine':
        'https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-rootfs-3.20.1-aarch64.tar.gz',
  };

  // Distro display names
  static const Map<String, String> distroNames = {
    'ubuntu': 'Ubuntu 24.04 LTS',
    'debian': 'Debian 12 Bookworm',
    'kali-nethunter': 'Kali Linux',
    'archlinux': 'Arch Linux',
    'alpine': 'Alpine Linux 3.20',
  };

  // DE display names
  static const Map<String, String> deNames = {
    'xfce4': 'XFCE4',
    'lxqt': 'LXQt',
    'mate': 'MATE',
    'kde': 'KDE Plasma',
  };

  // Default paths (relative to Termux home)
  static const String prootDir = 'proot';
  static const String rootfsDir = 'proot/ubuntu';
  static const String downloadsDir = 'downloads';
  static const String scriptsDir = 'scripts';
  static const String logDir = '.droidforge';

  // App metadata
  static const String appName = 'DroidForge';
  static const String appVersion = '2.0.0';
  static const String appId = 'com.droidforge.core';
  static const String channelName = 'com.droidforge/core';

  // Defaults
  static const int defaultVncWidth = 1920;
  static const int defaultVncHeight = 1080;
  static const int defaultVncDepth = 24;
  static const String defaultDistro = 'ubuntu';
  static const String defaultDE = 'xfce4';

  // Supported ABIs
  static const List<String> supportedAbis = ['arm64-v8a'];

  // Min SDK (API 28 to bypass Android 10+ execve() block)
  static const int minSdk = 28;
  static const int targetSdk = 28;
}
