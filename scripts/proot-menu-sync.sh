#!/data/data/com.termux/files/usr/bin/bash

# ============================================================
#  DroidForge Proot App Menu Bridge v5
#  Syncs proot .desktop files into native DE menu
#  Supports XFCE4, LXQt, MATE, KDE Plasma
# ============================================================

PROOT_DISTRO="${1:-$(cat ~/.droidforge/installed-distro 2>/dev/null || echo ubuntu)}"
PROOT_BIN="/data/data/com.termux/files/usr/bin/proot-distro"
PROOT_ROOTFS="/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/$PROOT_DISTRO"
PROOT_APPS="$PROOT_ROOTFS/usr/share/applications"
BRIDGE_DIR="$HOME/.local/share/applications/proot-bridge"
WRAPPER_DIR="$HOME/.local/share/proot-wrappers"
TERMUX_TMP="${TMPDIR:-/data/data/com.termux/files/usr/tmp}"

if [ ! -f "$PROOT_BIN" ]; then
    echo "[!] proot-distro not found. Run: pkg install proot-distro"
    exit 1
fi
if [ ! -d "$PROOT_ROOTFS" ]; then
    echo "[!] Proot distro '$PROOT_DISTRO' not installed."
    exit 1
fi
if [ ! -d "$PROOT_APPS" ]; then
    echo "[!] No proot apps found. Install with: proot-distro login $PROOT_DISTRO -- apt install <pkg>"
    exit 0
fi

mkdir -p "$BRIDGE_DIR" "$WRAPPER_DIR"

# Detect GPU capability
HAS_GPU="software"
[ -d "/dev/dri" ] && HAS_GPU="zink"

# Ensure dbus-x11 in proot
if ! "$PROOT_BIN" login "$PROOT_DISTRO" -- which dbus-run-session > /dev/null 2>&1; then
    echo "[*] Installing dbus-x11 in proot..."
    "$PROOT_BIN" login "$PROOT_DISTRO" -- apt-get install -y -q dbus-x11 > /dev/null 2>&1
fi

SYNCED=0
REMOVED=0

# Remove stale bridges
for bridge_file in "$BRIDGE_DIR"/proot-*.desktop; do
    [ -f "$bridge_file" ] || continue
    original_name=$(basename "$bridge_file" | sed 's/^proot-//')
    if [ ! -f "$PROOT_APPS/$original_name" ]; then
        rm -f "$bridge_file" "$WRAPPER_DIR/proot-${original_name%.desktop}.sh"
        REMOVED=$((REMOVED + 1))
    fi
done

# Sync active apps
for desktop_file in "$PROOT_APPS"/*.desktop; do
    [ -f "$desktop_file" ] || continue

    filename=$(basename "$desktop_file")
    appname="${filename%.desktop}"
    output="$BRIDGE_DIR/proot-$filename"
    wrapper="$WRAPPER_DIR/proot-${appname}.sh"

    # Skip hidden apps
    grep -q "^NoDisplay=true" "$desktop_file" 2>/dev/null && continue
    grep -q "^Hidden=true"    "$desktop_file" 2>/dev/null && continue

    ORIGINAL_EXEC=$(grep "^Exec=" "$desktop_file" | head -1 | sed 's/^Exec=//')
    [ -z "$ORIGINAL_EXEC" ] && continue
    CLEAN_EXEC=$(echo "$ORIGINAL_EXEC" | sed 's/ %[a-zA-Z]//g; s/%[a-zA-Z]//g')

    APP_CMD="$CLEAN_EXEC"
    EXTRA_ENV=""

    # App-specific fixes
    echo "$appname" | grep -qi "libreoffice\|soffice" && \
        APP_CMD="$CLEAN_EXEC --norestore --nofirststartwizard"

    if echo "$appname" | grep -qi "blender"; then
        if "$PROOT_BIN" login "$PROOT_DISTRO" -- ldconfig -p 2>/dev/null | grep -q "libvulkan.so.1"; then
            EXTRA_ENV="export GALLIUM_DRIVER=zink; export MESA_GL_VERSION_OVERRIDE=4.6;"
            echo "  [+] Blender: Zink GPU mode"
        else
            EXTRA_ENV="export LIBGL_ALWAYS_SOFTWARE=1; export GALLIUM_DRIVER=llvmpipe; export MESA_GL_VERSION_OVERRIDE=4.5;"
            echo "  [!] Blender: Software mode (install libvulkan1 for GPU)"
        fi
    fi

    # Generate wrapper script
    cat > "$wrapper" << WRAPEOF
#!/data/data/com.termux/files/usr/bin/bash
PROOT_BIN="$PROOT_BIN"
PROOT_DISTRO="$PROOT_DISTRO"
TERMUX_TMP="\${TMPDIR:-/data/data/com.termux/files/usr/tmp}"
LOG="\$TERMUX_TMP/proot-${appname}.log"

BINDS=""
X11_DIR="\$TERMUX_TMP/.X11-unix"
[ -d "\$X11_DIR" ]     && BINDS="\$BINDS --bind \$X11_DIR:/tmp/.X11-unix"
[ -d "/dev/dri" ]      && BINDS="\$BINDS --bind /dev/dri:/dev/dri"
[ -e "/dev/kgsl-3d0" ] && BINDS="\$BINDS --bind /dev/kgsl-3d0:/dev/kgsl-3d0"

{
echo "[+] Launching $appname at \$(date)"
echo "    X11=\$X11_DIR  BINDS=\$BINDS"
\$PROOT_BIN login "\$PROOT_DISTRO" \$BINDS -- /bin/bash -c "
export DISPLAY=:0
export XDG_RUNTIME_DIR=/tmp
export MESA_NO_ERROR=1
$EXTRA_ENV
dbus-run-session $APP_CMD
"
EXIT_CODE=\$?
echo "Exit: \$EXIT_CODE at \$(date)"
} > "\$LOG" 2>&1

[ \$EXIT_CODE -ne 0 ] && \
    xfce4-terminal --title="$appname error" \
        -e "bash -c 'cat \$LOG; echo; read -p \"Press Enter\"'" &
WRAPEOF
    chmod +x "$wrapper"

    # Copy and modify desktop file
    cp "$desktop_file" "$output"
    sed -i \
        -e "s|^Exec=.*|Exec=$wrapper|" \
        -e "s|^TryExec=.*|TryExec=$wrapper|" \
        -e '/^NoDisplay=/d' -e '/^Hidden=/d' \
        "$output"
    echo "NoDisplay=false" >> "$output"

    # Add [P] prefix to app name
    APP_NAME=$(grep "^Name=" "$output" | head -1 | sed 's/^Name=//')
    [[ "$APP_NAME" != \[P\]* ]] && sed -i "s|^Name=.*|Name=[P] $APP_NAME|" "$output"
    SYNCED=$((SYNCED + 1))
done

echo "[+] Bridge complete: $SYNCED apps synced, $REMOVED stale removed."
echo "    Logs: \$TERMUX_TMP/proot-<appname>.log"
echo "    Re-run after new installs: bash ~/proot-menu-sync.sh"

# Restart panels to pick up changes
pgrep -x "xfce4-panel" > /dev/null 2>&1 && xfce4-panel --restart > /dev/null 2>&1 &
pgrep -x "xfdesktop"   > /dev/null 2>&1 && { sleep 1; xfdesktop --reload > /dev/null 2>&1 & }
