#!/bin/bash

# ============================================================
# DroidForge - Native Dependency Fetcher
# Downloads pre-built native libraries for the Android app
# ============================================================

echo "Fetching DroidForge native dependencies..."

URL_BASE="https://packages-cf.termux.dev/apt/termux-main/pool/main"

PACKAGES=(
    "w/wlroots/wlroots_0.17.4-1_aarch64.deb"
    "w/wayland/wayland_1.22.0-1_aarch64.deb"
    "l/libxkbcommon/libxkbcommon_1.7.0-1_aarch64.deb"
    "p/pixman/pixman_0.43.4-1_aarch64.deb"
    "l/libdrm/libdrm_2.4.120-1_aarch64.deb"
    "l/libffi/libffi_3.4.6_aarch64.deb"
)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JNILIBS_DIR="${SCRIPT_DIR}/app/android/app/src/main/jniLibs/arm64-v8a"
INCLUDE_DIR="${SCRIPT_DIR}/app/android/app/src/main/cpp/include"

mkdir -p "$JNILIBS_DIR"
mkdir -p "$INCLUDE_DIR"
mkdir -p /tmp/dforge_deps
cd /tmp/dforge_deps

for pkg in "${PACKAGES[@]}"; do
    filename=$(basename "$pkg")
    echo "[*] Downloading $filename..."
    curl -sL "$URL_BASE/$pkg" -o "$filename"
    
    # Extract
    if command -v bsdtar &> /dev/null; then
        bsdtar -xf "$filename"
        bsdtar -xf data.tar.xz 2>/dev/null || bsdtar -xf data.tar.gz 2>/dev/null
    elif command -v ar &> /dev/null; then
        ar -xf "$filename"
        tar -xf data.tar.xz 2>/dev/null || tar -xf data.tar.gz 2>/dev/null
    else
        echo "[!] Neither bsdtar nor ar found. Install: pkg install libarchive"
        exit 1
    fi
    
    # Copy shared libraries
    find ./data/data/com.termux/files/usr/lib -name "*.so*" -type f -exec cp {} "$JNILIBS_DIR/" \; 2>/dev/null
    find ./data/data/com.termux/files/usr/lib -name "*.so*" -type l -exec cp -a {} "$JNILIBS_DIR/" \; 2>/dev/null
    
    # Copy headers
    if [ -d "./data/data/com.termux/files/usr/include" ]; then
        cp -r ./data/data/com.termux/files/usr/include/* "$INCLUDE_DIR/" 2>/dev/null
    fi
    
    # Clean up for next package
    rm -rf data control.tar.xz data.tar.xz data.tar.gz debian-binary
done

echo ""
echo "Done! Native dependencies fetched."
echo "  Libraries: $JNILIBS_DIR"
echo "  Headers:   $INCLUDE_DIR"
