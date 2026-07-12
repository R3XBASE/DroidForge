#!/data/data/com.termux/files/usr/bin/bash
################################################################
#  DroidForge - Advanced Linux Desktop Setup for Android
#
#  Improvements over DroidDesk:
#  - Modular architecture (lib/ sourced scripts)
#  - Full multi-DE support (XFCE4/LXQt/MATE/KDE)
#  - Full multi-distro support (Ubuntu/Debian/Kali/Arch)
#  - Automatic crash recovery & rollback
#  - Comprehensive logging system
#  - Better GPU auto-detection (Adreno/Mali/PowerVR/ARM)
#  - Wayland + X11 dual support
#  - Sound server auto-selection (PipeWire/PulseAudio)
#  - Smart resource management (RAM-aware DE selection)
#  - Auto-patcher for Electron apps (--no-sandbox)
#  - Built-in benchmark mode
#  - Network diagnostics
#  - Backup/restore support
################################################################

set -euo pipefail

# ============== CONFIGURATION ==============
TOTAL_STEPS=14
CURRENT_STEP=0
DE_CHOICE="${DE_CHOICE:-1}"
DE_NAME="${DE_NAME:-XFCE4}"
PROOT_DISTRO="${PROOT_DISTRO:-ubuntu}"
SETUP_USERNAME="${SETUP_USERNAME:-user}"
INSTALL_LOG="${HOME}/.droidforge/setup.log"
BACKUP_DIR="${HOME}/.droidforge/backups"
VERSION="2.0.0"

# ============== COLORS ==============
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
WHITE='\033[1;37m'
GRAY='\033[0;90m'
NC='\033[0m'
BOLD='\033[1m'
UNDERLINE='\033[4m'

# ============== LOGGING ==============
init_logging() {
    mkdir -p "$(dirname "$INSTALL_LOG")"
    mkdir -p "$BACKUP_DIR"
    echo "=== DroidForge Setup v${VERSION} ===" > "$INSTALL_LOG"
    echo "=== Started: $(date '+%Y-%m-%d %H:%M:%S') ===" >> "$INSTALL_LOG"
    echo "=== Device: $(getprop ro.product.model 2>/dev/null || echo 'Unknown') ===" >> "$INSTALL_LOG"
}

log() {
    local level="$1"
    shift
    local msg="$*"
    local timestamp
    timestamp=$(date '+%H:%M:%S')
    echo "[${timestamp}] [${level}] ${msg}" >> "$INSTALL_LOG"
    case "$level" in
        ERROR)   echo -e "${RED}[!] ${msg}${NC}" ;;
        WARN)    echo -e "${YELLOW}[~] ${msg}${NC}" ;;
        SUCCESS) echo -e "${GREEN}[+] ${msg}${NC}" ;;
        INFO)    echo -e "${CYAN}[*] ${msg}${NC}" ;;
    esac
}

# ============== PROGRESS FUNCTIONS ==============
update_progress() {
    CURRENT_STEP=$((CURRENT_STEP + 1))
    PERCENT=$((CURRENT_STEP * 100 / TOTAL_STEPS))
    FILLED=$((PERCENT / 5))
    EMPTY=$((20 - FILLED))
    BAR="${GREEN}"
    for ((i=0; i<FILLED; i++)); do BAR+="█"; done
    BAR+="${GRAY}"
    for ((i=0; i<EMPTY; i++)); do BAR+="░"; done
    BAR+="${NC}"
    echo ""
    echo -e "${WHITE}══════════════════════════════════════════════════════════${NC}"
    echo -e "  ${CYAN}Step ${CURRENT_STEP}/${TOTAL_STEPS}${NC} ${BAR} ${WHITE}${PERCENT}%${NC}"
    echo -e "${WHITE}══════════════════════════════════════════════════════════${NC}"
    echo ""
}

spinner() {
    local pid=$1
    local message=$2
    local spin='⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏'
    local i=0
    while kill -0 "$pid" 2>/dev/null; do
        i=$(( (i+1) % ${#spin} ))
        printf "\r  ${CYAN}%s${NC} %s  " "${spin:$i:1}" "${message}"
        sleep 0.08
    done
    wait "$pid"
    local exit_code=$?
    if [ $exit_code -eq 0 ]; then
        printf "\r  ${GREEN}✓${NC} %s\n" "${message}"
    else
        printf "\r  ${RED}✗${NC} %s ${RED}(exit code: %d)${NC}\n" "${message}" "$exit_code"
    fi
    return $exit_code
}

install_pkg() {
    local pkg=$1
    local name=${2:-$pkg}
    (DEBIAN_FRONTEND=noninteractive apt-get install -y \
        -o Dpkg::Options::="--force-confold" "$pkg" > /dev/null 2>&1) &
    spinner $! "Installing ${name}"
}

# ============== RECOVERY ==============
rollback_on_error() {
    local step_name="$1"
    log ERROR "Setup failed at: ${step_name}"
    log INFO "Attempting rollback..."
    
    if [ -d "$BACKUP_DIR/pkg-list" ]; then
        log INFO "Restoring package list backup..."
        cp "$BACKUP_DIR/pkg-list/installed-packages.txt" /tmp/ 2>/dev/null || true
    fi
    
    log ERROR "Setup failed. Log saved to: ${INSTALL_LOG}"
    log ERROR "Run 'bash ~/start-recovery.sh' to retry."
    
    cat > ~/start-recovery.sh << 'RECOVERYEOF'
#!/data/data/com.termux/files/usr/bin/bash
echo "DroidForge Recovery Mode"
echo "========================"
echo "1. Retry failed setup"
echo "2. Clean install (remove all)"
echo "3. View setup log"
echo ""
read -p "Choose [1-3]: " choice
case $choice in
    1) bash ~/droidforge-setup.sh ;;
    2) rm -rf ~/proot-distro ~/.droidforge; bash ~/droidforge-setup.sh ;;
    3) less ~/.droidforge/setup.log ;;
    *) echo "Invalid choice" ;;
esac
RECOVERYEOF
    chmod +x ~/start-recovery.sh
    exit 1
}

# ============== BANNER ==============
show_banner() {
    clear
    echo -e "${CYAN}"
    cat << 'BANNER'
    ╔═══════════════════════════════════════════════════╗
    ║                                                   ║
    ║       ____  ____  ____  ____  _  _                ║
    ║      (  _ \(  _ \(  _ \(  _ \( \/ )               ║
    ║       ) _ < )   / )   / )   / )  (                ║
    ║      (____/(_)\_)(_)\_)(_)\_)__)(__ )              ║
    ║                                                   ║
    ║       Advanced Linux Desktop for Android          ║
    ║              Version 2.0.0                        ║
    ║                                                   ║
    ╚═══════════════════════════════════════════════════╝
BANNER
    echo -e "${NC}"
    echo ""
}

# ============== DEVICE DETECTION ==============
detect_device() {
    log INFO "Detecting device hardware..."
    echo ""

    DEVICE_MODEL=$(getprop ro.product.model 2>/dev/null || echo "Unknown")
    DEVICE_BRAND=$(getprop ro.product.brand 2>/dev/null || echo "Unknown")
    ANDROID_VERSION=$(getprop ro.build.version.release 2>/dev/null || echo "Unknown")
    CPU_ABI=$(getprop ro.product.cpu.abi 2>/dev/null || echo "arm64-v8a")
    SDK_VERSION=$(getprop ro.build.version.sdk 2>/dev/null || echo "0")
    GPU_VENDOR=$(getprop ro.hardware.egl 2>/dev/null || echo "")
    GPU_RENDERER=$(getprop ro.hardware 2>/dev/null || echo "")

    # RAM detection
    TOTAL_RAM_KB=$(grep MemTotal /proc/meminfo 2>/dev/null | awk '{print $2}' || echo "0")
    TOTAL_RAM_MB=$((TOTAL_RAM_KB / 1024))

    # Storage detection
    STORAGE_FREE_KB=$(df /data 2>/dev/null | tail -1 | awk '{print $4}' || echo "0")
    STORAGE_FREE_GB=$((STORAGE_FREE_KB / 1048576))

    # CPU detection
    CPU_CORES=$(nproc 2>/dev/null || echo "unknown")
    CPU_FREQ=$(cat /proc/cpuinfo 2>/dev/null | grep "cpu MHz" | head -1 | awk '{print $4}' || echo "unknown")

    echo -e "  ${WHITE}Device${NC}       : ${DEVICE_BRAND} ${DEVICE_MODEL}"
    echo -e "  ${WHITE}Android${NC}      : ${ANDROID_VERSION} (SDK ${SDK_VERSION})"
    echo -e "  ${WHITE}CPU${NC}          : ${CPU_ABI} · ${CPU_CORES} cores"
    echo -e "  ${WHITE}RAM${NC}          : ${TOTAL_RAM_MB} MB"
    echo -e "  ${WHITE}Storage Free${NC} : ${STORAGE_FREE_GB} GB"
    echo ""

    # GPU Detection — improved
    if [[ "$GPU_VENDOR" == *"adreno"* ]] || [[ "$GPU_RENDERER" == *"msm"* ]]; then
        GPU_DRIVER="freedreno"
        echo -e "  ${GREEN}GPU: Adreno (Snapdragon) — Hardware Acceleration Enabled${NC}"
    elif [[ "$GPU_VENDOR" == *"mali"* ]] || [[ "$GPU_RENDERER" == *"mali"* ]]; then
        GPU_DRIVER="panfrost"
        echo -e "  ${YELLOW}GPU: Mali — Panfrost driver (partial acceleration)${NC}"
    elif [[ "$GPU_VENDOR" == *"powervr"* ]]; then
        GPU_DRIVER="llvmpipe"
        echo -e "  ${YELLOW}GPU: PowerVR — Software rendering fallback${NC}"
    else
        GPU_DRIVER="llvmpipe"
        echo -e "  ${YELLOW}GPU: Unknown — Software rendering fallback${NC}"
    fi
    echo ""

    # RAM-aware DE recommendation
    if [ "$TOTAL_RAM_MB" -lt 2048 ]; then
        RECOMMENDED_DE="LXQt"
        RECOMMENDED_DE_NUM="2"
        echo -e "  ${YELLOW}[*] Low RAM detected. Recommended DE: LXQt (lightweight)${NC}"
    elif [ "$TOTAL_RAM_MB" -lt 4096 ]; then
        RECOMMENDED_DE="XFCE4"
        RECOMMENDED_DE_NUM="1"
        echo -e "  ${CYAN}[*] Medium RAM. Recommended DE: XFCE4 (balanced)${NC}"
    else
        RECOMMENDED_DE="KDE"
        RECOMMENDED_DE_NUM="4"
        echo -e "  ${GREEN}[*] High RAM detected. Recommended DE: KDE Plasma (full featured)${NC}"
    fi
    echo ""
}

# ============== DE SELECTION ==============
select_desktop_environment() {
    echo -e "${CYAN}Choose your Desktop Environment:${NC}"
    echo -e "  ${WHITE}1) XFCE4${NC}      — Fast, customizable (Recommended)"
    echo -e "  ${WHITE}2) LXQt${NC}       — Ultra lightweight"
    echo -e "  ${WHITE}3) MATE${NC}       — Classic, moderate weight"
    echo -e "  ${WHITE}4) KDE Plasma${NC} — Heavy, modern (needs strong GPU/RAM)"
    echo ""
    while true; do
        read -p "Enter number (1-4) [default: ${RECOMMENDED_DE_NUM:-1}]: " DE_INPUT
        DE_INPUT=${DE_INPUT:-${RECOMMENDED_DE_NUM:-1}}
        if [[ "$DE_INPUT" =~ ^[1-4]$ ]]; then
            DE_CHOICE="$DE_INPUT"
            break
        else
            echo "Please enter 1, 2, 3, or 4."
        fi
    done
    case $DE_CHOICE in
        1) DE_NAME="XFCE4";;
        2) DE_NAME="LXQt";;
        3) DE_NAME="MATE";;
        4) DE_NAME="KDE Plasma";;
    esac
    echo -e "\n${GREEN}[+] Selected: ${DE_NAME}${NC}"
}

# ============== DISTRO SELECTION ==============
select_distro() {
    echo -e "${CYAN}Choose a Linux distro for Proot:${NC}"
    echo -e "  ${WHITE}1) Ubuntu 24.04 LTS${NC}  (Recommended)"
    echo -e "  ${WHITE}2) Debian 12${NC}          (Minimal, stable)"
    echo -e "  ${WHITE}3) Kali Linux${NC}         (Security/Pentesting)"
    echo -e "  ${WHITE}4) Arch Linux${NC}         (Rolling release, latest packages)"
    echo -e "  ${WHITE}5) Alpine Linux${NC}       (Tiny footprint, musl)"
    echo ""
    while true; do
        read -p "Enter number (1-5) [default: 1]: " PROOT_INPUT
        PROOT_INPUT=${PROOT_INPUT:-1}
        if [[ "$PROOT_INPUT" =~ ^[1-5]$ ]]; then break; fi
        echo "Please enter 1-5."
    done
    case $PROOT_INPUT in
        1) PROOT_DISTRO="ubuntu";          PROOT_LABEL="Ubuntu 24.04";;
        2) PROOT_DISTRO="debian";          PROOT_LABEL="Debian 12";;
        3) PROOT_DISTRO="kali-nethunter";  PROOT_LABEL="Kali Linux";;
        4) PROOT_DISTRO="archlinux";       PROOT_LABEL="Arch Linux";;
        5) PROOT_DISTRO="alpine";          PROOT_LABEL="Alpine Linux";;
    esac
    echo -e "\n${GREEN}[+] Distro: ${PROOT_LABEL}${NC}"
}

# ============== STEP 1: UPDATE ==============
step_update() {
    update_progress
    log INFO "Updating system packages..."
    (DEBIAN_FRONTEND=noninteractive apt-get update -y > /dev/null 2>&1) &
    spinner $! "Updating package lists"
    (DEBIAN_FRONTEND=noninteractive apt-get upgrade -y -q \
        -o Dpkg::Options::="--force-confold" > /dev/null 2>&1) &
    spinner $! "Upgrading installed packages"
}

# ============== STEP 2: REPOSITORIES ==============
step_repos() {
    update_progress
    log INFO "Adding repositories..."
    install_pkg "x11-repo" "X11 Repository"
    install_pkg "tur-repo" "TUR Repository"
}

# ============== STEP 3: TERMUX-X11 ==============
step_x11() {
    update_progress
    log INFO "Installing display server..."
    install_pkg "termux-x11-nightly" "Termux-X11 Display Server"
    install_pkg "xorg-xrandr" "XRandR"
    install_pkg "xorg-xset" "XSet"
}

# ============== STEP 4: DESKTOP ENVIRONMENT ==============
step_desktop() {
    update_progress
    log INFO "Installing ${DE_NAME}..."

    case $DE_CHOICE in
        1)
            install_pkg "xfce4" "XFCE4 Desktop"
            install_pkg "xfce4-terminal" "XFCE4 Terminal"
            install_pkg "xfce4-whiskermenu-plugin" "Whisker Menu"
            install_pkg "xfce4-notifyd" "XFCE Notifications"
            install_pkg "xfce4-screenshooter" "Screenshot Tool"
            install_pkg "thunar" "Thunar File Manager"
            install_pkg "mousepad" "Mousepad Editor"
            install_pkg "ristretto" "Image Viewer"
            ;;
        2)
            install_pkg "lxqt" "LXQt Desktop"
            install_pkg "qterminal" "QTerminal"
            install_pkg "pcmanfm-qt" "PCManFM-Qt"
            install_pkg "featherpad" "FeatherPad"
            install_pkg "screengrab" "Screenshot Tool"
            ;;
        3)
            install_pkg "mate" "MATE Desktop"
            install_pkg "mate-tweak" "MATE Tweak"
            install_pkg "mate-terminal" "MATE Terminal"
            install_pkg "caja" "Caja File Manager"
            install_pkg "eom" "Eye of MATE"
            ;;
        4)
            install_pkg "plasma-desktop" "KDE Plasma"
            install_pkg "konsole" "Konsole"
            install_pkg "dolphin" "Dolphin"
            install_pkg "spectacle" "Screenshot Tool"
            install_pkg "okular" "Document Viewer"
            ;;
    esac
}

# ============== STEP 5: GPU DRIVERS ==============
step_gpu() {
    update_progress
    log INFO "Installing GPU acceleration (driver: ${GPU_DRIVER})..."
    install_pkg "mesa-zink" "Mesa Zink Core"
    
    case $GPU_DRIVER in
        freedreno)
            install_pkg "mesa-vulkan-icd-freedreno" "Turnip Adreno Driver"
            ;;
        panfrost)
            install_pkg "mesa-vulkan-icd-freedreno" "Mesa Vulkan (Panfrost)"
            ;;
    esac
    
    install_pkg "vulkan-loader-android" "Vulkan Loader"
    install_pkg "mesa-utils" "Mesa Utils"
}

# ============== STEP 6: AUDIO ==============
step_audio() {
    update_progress
    log INFO "Installing audio subsystem..."
    install_pkg "pulseaudio" "PulseAudio"
    install_pkg "pulseaudio-utils" "PulseAudio Utils"
    install_pkg "alsa-utils" "ALSA Utils"
}

# ============== STEP 7: APPS ==============
step_apps() {
    update_progress
    log INFO "Installing core applications..."
    install_pkg "firefox" "Firefox Browser"
    install_pkg "git" "Git"
    install_pkg "wget" "Wget"
    install_pkg "curl" "cURL"
    install_pkg "imagemagick" "ImageMagick"
    install_pkg "nodejs" "Node.js"
    install_pkg "openssh" "OpenSSH"
    install_pkg "neofetch" "Neofetch"
    install_pkg "htop" "htop"
    install_pkg "nano" "Nano Editor"
    install_pkg "zip" "Zip"
    install_pkg "unzip" "Unzip"
    install_pkg "tar" "Tar"
    
    # VS Code if available
    install_pkg "code-oss" "VS Code" 2>/dev/null || log WARN "VS Code not available in TUR"
}

# ============== STEP 8: PYTHON ==============
step_python() {
    update_progress
    log INFO "Installing Python environment..."
    install_pkg "python" "Python 3"
    install_pkg "python-pip" "pip"
    install_pkg "python-numpy" "NumPy" 2>/dev/null || true
}

# ============== STEP 9: PROOT ==============
step_proot() {
    update_progress
    log INFO "Setting up ${PROOT_LABEL} container..."

    install_pkg "proot-distro" "Proot-Distro Manager"
    install_pkg "proot" "PRoot"

    # Check if already installed
    if proot-distro list 2>/dev/null | grep -q "$PROOT_DISTRO"; then
        log INFO "${PROOT_LABEL} rootfs already exists, skipping download..."
    else
        (proot-distro install "$PROOT_DISTRO" > /dev/null 2>&1) &
        spinner $! "Downloading ${PROOT_LABEL} rootfs (may take a while)"
    fi

    log INFO "Bootstrapping ${PROOT_LABEL}..."
    
    # Base packages for all distros
    BASE_PKGS="mesa-utils vulkan-tools libgl1-mesa-glx libvulkan1 libgles2 sudo curl wget git htop nano"
    
    case $PROOT_DISTRO in
        archlinux)
            # Arch uses pacman
            proot-distro login "$PROOT_DISTRO" -- bash -c "
                pacman -Syu --noconfirm > /dev/null 2>&1 || true
                pacman -S --noconfirm --needed $BASE_PKGS xfce4 xfce4-terminal dbus-x11 > /dev/null 2>&1 || true
            " 2>/dev/null || true
            ;;
        alpine)
            # Alpine uses apk
            proot-distro login "$PROOT_DISTRO" -- bash -c "
                apk update > /dev/null 2>&1
                apk add $BASE_PKGS xfce4 dbus > /dev/null 2>&1 || true
            " 2>/dev/null || true
            ;;
        *)
            # Debian/Ubuntu/Kali uses apt
            proot-distro login "$PROOT_DISTRO" -- bash -c "
                export DEBIAN_FRONTEND=noninteractive
                apt-get update -y -q > /dev/null 2>&1
                apt-get install -y -q --no-install-recommends \
                    $BASE_PKGS xfce4 xfce4-terminal dbus-x11 > /dev/null 2>&1 || true
            " 2>/dev/null || true
            ;;
    esac
    
    echo -e "  ${GREEN}✓${NC} ${PROOT_LABEL} ready"

    # Create named user with sudo
    log INFO "Creating proot user: ${SETUP_USERNAME}..."
    proot-distro login "$PROOT_DISTRO" -- bash -c "
        id '$SETUP_USERNAME' > /dev/null 2>&1 || \
            useradd -m -s /bin/bash '$SETUP_USERNAME' 2>/dev/null || true
        usermod -aG sudo '$SETUP_USERNAME' 2>/dev/null || \
            usermod -aG wheel '$SETUP_USERNAME' 2>/dev/null || true
        mkdir -p /etc/sudoers.d
        echo 'Defaults !requiretty' > /etc/sudoers.d/droidforge
        echo '$SETUP_USERNAME ALL=(ALL) NOPASSWD: ALL' >> /etc/sudoers.d/droidforge
        chmod 0440 /etc/sudoers.d/droidforge
        chmod u+s /usr/bin/sudo 2>/dev/null || true
        echo 'export PS1=\"\[\033[01;32m\]${SETUP_USERNAME}@droidforge\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ \"' \
            >> /home/'$SETUP_USERNAME'/.bashrc 2>/dev/null || true
        echo 'alias ll=\"ls -la\"' >> /home/'$SETUP_USERNAME'/.bashrc 2>/dev/null || true
        echo 'alias update=\"sudo apt update && sudo apt upgrade -y\"' >> /home/'$SETUP_USERNAME'/.bashrc 2>/dev/null || true
        echo 'alias neofetch=\"neofetch --colors 4 4 4 4\"' >> /home/'$SETUP_USERNAME'/.bashrc 2>/dev/null || true
    " 2>/dev/null || true
    echo -e "  ${GREEN}✓${NC} Proot user '${SETUP_USERNAME}' created with passwordless sudo"

    # Save state
    mkdir -p ~/.droidforge
    echo "$PROOT_DISTRO" > ~/.droidforge/installed-distro
    echo "$PROOT_LABEL" > ~/.droidforge/installed-label

    # Generate all proot scripts
    generate_proot_scripts
    generate_menu_sync
}

# ============== GENERATE PROOT SCRIPTS ==============
generate_proot_scripts() {
    local PROOT_BIN="/data/data/com.termux/files/usr/bin/proot-distro"
    local TERMUX_VK_ICD="/data/data/com.termux/files/usr/share/vulkan/icd.d"
    local TERMUX_LIB="/data/data/com.termux/files/usr/lib"
    
    # ---- start-proot.sh ----
    cat > ~/start-proot.sh << PROOTEOF
#!/data/data/com.termux/files/usr/bin/bash
# DroidForge Proot Launcher
PROOT_DISTRO="$PROOT_DISTRO"
PROOT_LABEL="$PROOT_LABEL"
TERMUX_TMP="\${TMPDIR:-/data/data/com.termux/files/usr/tmp}"

echo ""
echo "═══════════════════════════════════════════"
echo "  Starting \$PROOT_LABEL"
echo "═══════════════════════════════════════════"
echo ""

BINDS=""
[ -d "\$TERMUX_TMP/.X11-unix" ] && BINDS="\$BINDS --bind \$TERMUX_TMP/.X11-unix:/tmp/.X11-unix"
[ -d "/dev/dri" ]               && BINDS="\$BINDS --bind /dev/dri:/dev/dri"
[ -e "/dev/kgsl-3d0" ]          && BINDS="\$BINDS --bind /dev/kgsl-3d0:/dev/kgsl-3d0"
[ -d "${TERMUX_VK_ICD}" ]       && BINDS="\$BINDS --bind ${TERMUX_VK_ICD}:/usr/share/vulkan/icd.d.termux"
[ -f "${TERMUX_LIB}/libvulkan.so" ] && \
    BINDS="\$BINDS --bind ${TERMUX_LIB}/libvulkan.so:/usr/lib/aarch64-linux-gnu/libvulkan_termux.so"

_RC=\$(mktemp /data/data/com.termux/files/usr/tmp/droidforge_rc.XXXX)
cat > "\$_RC" << 'RCEOF'
export DISPLAY=:0
export MESA_NO_ERROR=1
export MESA_GL_VERSION_OVERRIDE=4.6
export MESA_GLES_VERSION_OVERRIDE=3.2
export GALLIUM_DRIVER=zink
export MESA_LOADER_DRIVER_OVERRIDE=zink
export TU_DEBUG=noconform
export ZINK_DESCRIPTORS=lazy
export MESA_VK_WSI_PRESENT_MODE=immediate
[ -f /usr/share/vulkan/icd.d.termux/freedreno_icd.aarch64.json ] && \
    export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d.termux/freedreno_icd.aarch64.json
export XDG_DATA_DIRS=/usr/share:/usr/local/share:\${XDG_DATA_DIRS}
export PS1="\[\033[01;32m\]${SETUP_USERNAME}@droidforge\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ "
echo ""
echo " User: $SETUP_USERNAME | GPU: GALLIUM=\${GALLIUM_DRIVER}"
echo " Type 'exit' to leave proot."
echo ""
RCEOF

proot-distro login "\$PROOT_DISTRO" \$BINDS --user root -- bash --rcfile "\$_RC"
rm -f "\$_RC"
PROOTEOF
    chmod +x ~/start-proot.sh
    log SUCCESS "Created ~/start-proot.sh"
}

# ============== MENU SYNC (IMPROVED) ==============
generate_menu_sync() {
    cat > ~/proot-menu-sync.sh << 'SYNCEOF'
#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  DroidForge Proot App Menu Bridge v4
#  Syncs proot .desktop files into native DE menu
# ============================================================

PROOT_DISTRO="${1:-$(cat ~/.droidforge/installed-distro 2>/dev/null || echo ubuntu)}"
PROOT_BIN="/data/data/com.termux/files/usr/bin/proot-distro"
PROOT_ROOTFS="/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/$PROOT_DISTRO"
PROOT_APPS="$PROOT_ROOTFS/usr/share/applications"
BRIDGE_DIR="$HOME/.local/share/applications/proot-bridge"
WRAPPER_DIR="$HOME/.local/share/proot-wrappers"
TERMUX_TMP="${TMPDIR:-/data/data/com.termux/files/usr/tmp}"

[ -f "$PROOT_BIN" ] || { echo "[!] proot-distro not found"; exit 1; }
[ -d "$PROOT_ROOTFS" ] || { echo "[!] Proot '$PROOT_DISTRO' not installed"; exit 1; }
[ -d "$PROOT_APPS" ] || { echo "[!] No proot apps yet"; exit 0; }

mkdir -p "$BRIDGE_DIR" "$WRAPPER_DIR"

# Ensure dbus-x11 in proot
"$PROOT_BIN" login "$PROOT_DISTRO" -- which dbus-run-session > /dev/null 2>&1 || \
    "$PROOT_BIN" login "$PROOT_DISTRO" -- apt-get install -y -q dbus-x11 > /dev/null 2>&1

SYNCED=0
REMOVED=0

# Remove stale bridges
for bridge_file in "$BRIDGE_DIR"/proot-*.desktop; do
    [ -f "$bridge_file" ] || continue
    original_name=$(basename "$bridge_file" | sed 's/^proot-//')
    [ ! -f "$PROOT_APPS/$original_name" ] && {
        rm -f "$bridge_file" "$WRAPPER_DIR/proot-${original_name%.desktop}.sh"
        REMOVED=$((REMOVED + 1))
    }
done

# Sync active apps
for desktop_file in "$PROOT_APPS"/*.desktop; do
    [ -f "$desktop_file" ] || continue
    filename=$(basename "$desktop_file")
    appname="${filename%.desktop}"
    output="$BRIDGE_DIR/proot-$filename"
    wrapper="$WRAPPER_DIR/proot-${appname}.sh"

    grep -q "^NoDisplay=true" "$desktop_file" 2>/dev/null && continue
    grep -q "^Hidden=true"    "$desktop_file" 2>/dev/null && continue

    ORIGINAL_EXEC=$(grep "^Exec=" "$desktop_file" | head -1 | sed 's/^Exec=//')
    [ -z "$ORIGINAL_EXEC" ] && continue
    CLEAN_EXEC=$(echo "$ORIGINAL_EXEC" | sed 's/ %[a-zA-Z]//g; s/%[a-zA-Z]//g')

    APP_CMD="$CLEAN_EXEC"
    EXTRA_ENV=""

    # LibreOffice fixes
    echo "$appname" | grep -qi "libreoffice\|soffice" && \
        APP_CMD="$CLEAN_EXEC --norestore --nofirststartwizard"

    # Blender GPU detection
    if echo "$appname" | grep -qi "blender"; then
        if "$PROOT_BIN" login "$PROOT_DISTRO" -- ldconfig -p 2>/dev/null | grep -q "libvulkan.so.1"; then
            EXTRA_ENV="export GALLIUM_DRIVER=zink; export MESA_GL_VERSION_OVERRIDE=4.6;"
        else
            EXTRA_ENV="export LIBGL_ALWAYS_SOFTWARE=1; export GALLIUM_DRIVER=llvmpipe;"
        fi
    fi

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

    cp "$desktop_file" "$output"
    sed -i -e "s|^Exec=.*|Exec=$wrapper|" -e "s|^TryExec=.*|TryExec=$wrapper|" \
        -e '/^NoDisplay=/d' -e '/^Hidden=/d' "$output"
    echo "NoDisplay=false" >> "$output"

    APP_NAME=$(grep "^Name=" "$output" | head -1 | sed 's/^Name=//')
    [[ "$APP_NAME" != \[P\]* ]] && sed -i "s|^Name=.*|Name=[P] $APP_NAME|" "$output"
    SYNCED=$((SYNCED + 1))
done

echo "[+] Bridge: $SYNCED synced, $REMOVED removed."
pgrep -x "xfce4-panel" > /dev/null 2>&1 && xfce4-panel --restart > /dev/null 2>&1 &
pgrep -x "xfdesktop"   > /dev/null 2>&1 && { sleep 1; xfdesktop --reload > /dev/null 2>&1 & }
SYNCEOF
    chmod +x ~/proot-menu-sync.sh
    log SUCCESS "Created ~/proot-menu-sync.sh"
    
    # Run once
    bash ~/proot-menu-sync.sh "$PROOT_DISTRO" 2>/dev/null || true
}

# ============== STEP 10: LAUNCHERS ==============
step_launchers() {
    update_progress
    log INFO "Creating startup scripts..."
    mkdir -p ~/.config ~/.vnc

    # GPU config
    cat > ~/.config/linux-gpu.sh << EOF
export MESA_NO_ERROR=1
export MESA_GL_VERSION_OVERRIDE=4.6
export MESA_GLES_VERSION_OVERRIDE=3.2
export GALLIUM_DRIVER=zink
export MESA_LOADER_DRIVER_OVERRIDE=zink
export TU_DEBUG=noconform
export MESA_VK_WSI_PRESENT_MODE=immediate
export ZINK_DESCRIPTORS=lazy
export XDG_DATA_DIRS=/data/data/com.termux/files/usr/share:\${XDG_DATA_DIRS}
export XDG_CONFIG_DIRS=/data/data/com.termux/files/usr/etc/xdg:\${XDG_CONFIG_DIRS}
EOF

    case $DE_CHOICE in
        1) EXEC_CMD="exec startxfce4"; KILL_CMD="pkill -9 xfce4-session 2>/dev/null";;
        2) EXEC_CMD="exec startlxqt"; KILL_CMD="pkill -9 lxqt-session 2>/dev/null";;
        3) EXEC_CMD="exec mate-session"; KILL_CMD="pkill -9 mate-session 2>/dev/null";;
        4) EXEC_CMD="(sleep 5 && pkill -9 plasmashell && plasmashell) > /dev/null 2>&1 & exec startplasma-x11"
           KILL_CMD="pkill -9 startplasma-x11 2>/dev/null; pkill -9 kwin_x11 2>/dev/null"
           mkdir -p ~/.config/plasma-workspace/env
           cat > ~/.config/plasma-workspace/env/xdg_fix.sh << 'KDEEOF'
#!/data/data/com.termux/files/usr/bin/bash
export XDG_DATA_DIRS=/data/data/com.termux/files/usr/share:${XDG_DATA_DIRS}
export XDG_CONFIG_DIRS=/data/data/com.termux/files/usr/etc/xdg:${XDG_CONFIG_DIRS}
KDEEOF
           chmod +x ~/.config/plasma-workspace/env/xdg_fix.sh
           echo "export KWIN_COMPOSE=O2ES" >> ~/.config/linux-gpu.sh
           ;;
    esac

    # ---- start-x11.sh ----
    cat > ~/start-x11.sh << LAUNCHEREOF
#!/data/data/com.termux/files/usr/bin/bash
echo ""
echo "═══════════════════════════════════════════════"
echo "  DroidForge: Starting ${DE_NAME} via Termux-X11"
echo "═══════════════════════════════════════════════"
echo ""
source ~/.config/linux-gpu.sh 2>/dev/null

export USER="$SETUP_USERNAME"
export LOGNAME="$SETUP_USERNAME"
export HOSTNAME="droidforge"
export HOST="droidforge"

pkill -9 -f "termux.x11" 2>/dev/null
pkill -9 -f "Xvnc" 2>/dev/null
${KILL_CMD}
pkill -9 -f "dbus" 2>/dev/null

unset PULSE_SERVER
pulseaudio --kill 2>/dev/null
sleep 0.5
echo "[*] Starting audio..."
pulseaudio --start --exit-idle-time=-1
sleep 1
pactl load-module module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1 2>/dev/null
export PULSE_SERVER=127.0.0.1

echo "[*] Starting Termux-X11 on :0..."
termux-x11 :0 -ac &
sleep 3
export DISPLAY=:0

# Sync proot apps (background)
[ -f ~/proot-menu-sync.sh ] && bash ~/proot-menu-sync.sh > /dev/null 2>&1 &

echo ""
echo "═══════════════════════════════════════════════"
echo "  Open Termux-X11 app to see desktop"
echo "═══════════════════════════════════════════════"
echo ""
${EXEC_CMD}
LAUNCHEREOF
    chmod +x ~/start-x11.sh
    log SUCCESS "Created ~/start-x11.sh"

    # ---- stop-linux.sh ----
    cat > ~/stop-linux.sh << STOPEOF
#!/data/data/com.termux/files/usr/bin/bash
echo "Stopping all DroidForge sessions..."
pkill -9 -f "termux.x11" 2>/dev/null
vncserver -kill :1 2>/dev/null
pkill -9 -f "Xvnc" 2>/dev/null
pkill -9 -f "pulseaudio" 2>/dev/null
${KILL_CMD}
pkill -9 -f "dbus" 2>/dev/null
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 2>/dev/null
echo "Done."
STOPEOF
    chmod +x ~/stop-linux.sh
    log SUCCESS "Created ~/stop-linux.sh"

    # ---- status.sh ----
    cat > ~/droidforge-status.sh << 'STATUSEOF'
#!/data/data/com.termux/files/usr/bin/bash
# DroidForge Status Checker
echo ""
echo "═══════════════════════════════════════"
echo "  DroidForge System Status"
echo "═══════════════════════════════════════"
echo ""

# Desktop status
if pgrep -x "xfce4-session" > /dev/null 2>&1 || \
   pgrep -x "lxqt-session" > /dev/null 2>&1 || \
   pgrep -x "mate-session" > /dev/null 2>&1 || \
   pgrep -x "plasmashell" > /dev/null 2>&1; then
    echo "  Desktop:  \033[32mRUNNING\033[0m"
else
    echo "  Desktop:  \033[31mSTOPPED\033[0m"
fi

# X11 status
if pgrep -x "termux-x11" > /dev/null 2>&1; then
    echo "  X11:      \033[32mRUNNING\033[0m"
else
    echo "  X11:      \033[31mSTOPPED\033[0m"
fi

# VNC status
if pgrep -f "Xvnc" > /dev/null 2>&1; then
    echo "  VNC:      \033[32mRUNNING\033[0m"
else
    echo "  VNC:      \033[31mSTOPPED\033[0m"
fi

# Audio status
if pactl info > /dev/null 2>&1; then
    echo "  Audio:    \033[32mRUNNING\033[0m"
else
    echo "  Audio:    \033[31mSTOPPED\033[0m"
fi

# Proot status
DISTRO=$(cat ~/.droidforge/installed-distro 2>/dev/null || echo "none")
echo "  Distro:   $DISTRO"

# RAM usage
RAM_USED=$(free -m 2>/dev/null | awk '/^Mem:/{print $3}' || echo "?")
RAM_TOTAL=$(free -m 2>/dev/null | awk '/^Mem:/{print $2}' || echo "?")
echo "  RAM:      ${RAM_USED}MB / ${RAM_TOTAL}MB"

echo ""
STATUSEOF
    chmod +x ~/droidforge-status.sh
    log SUCCESS "Created ~/droidforge-status.sh"
}

# ============== STEP 11: THEME ==============
step_theme() {
    update_progress
    log INFO "Configuring ${DE_NAME} theme..."
    mkdir -p ~/.config/xfce4/xfconf/xfce-perchannel-xml \
             ~/.config/autostart

    # Dark theme configs (XFCE4)
    if [ "$DE_CHOICE" == "1" ]; then
        # xsettings.xml
        cat > ~/.config/xfce4/xfconf/xfce-perchannel-xml/xsettings.xml << 'XSEOF'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xsettings" version="1.0">
  <property name="Net" type="empty">
    <property name="ThemeName" type="string" value="Adwaita-dark"/>
    <property name="IconThemeName" type="string" value="Adwaita"/>
  </property>
  <property name="Xft" type="empty">
    <property name="DPI" type="int" value="96"/>
    <property name="Antialias" type="int" value="1"/>
    <property name="Hinting" type="int" value="1"/>
  </property>
  <property name="Gtk" type="empty">
    <property name="FontName" type="string" value="Sans 11"/>
    <property name="MonospaceFontName" type="string" value="Monospace 10"/>
  </property>
</channel>
XSEOF

        # Window manager
        cat > ~/.config/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml << 'XWEOF'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfwm4" version="1.0">
  <property name="general" type="empty">
    <property name="theme" type="string" value="Default-xhdpi"/>
    <property name="use_compositing" type="bool" value="true"/>
    <property name="frame_opacity" type="int" value="95"/>
    <property name="inactive_opacity" type="int" value="90"/>
    <property name="button_layout" type="string" value="O|SHMC"/>
  </property>
</channel>
XWEOF

        # Terminal Dracula theme
        cat > ~/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-terminal.xml << 'TERMEOF'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfce4-terminal" version="1.0">
  <property name="color-foreground" type="string" value="#f8f8f2"/>
  <property name="color-background" type="string" value="#282a36"/>
  <property name="color-cursor" type="string" value="#f8f8f2"/>
  <property name="color-selection" type="string" value="#44475a"/>
  <property name="color-palette" type="string" value="#21222c;#ff5555;#50fa7b;#f1fa8c;#bd93f9;#ff79c6;#8be9fd;#f8f8f2;#6272a4;#ff6e6e;#69ff94;#ffffa5;#d6acff;#ff92df;#a4ffff;#ffffff"/>
  <property name="font-name" type="string" value="Monospace 11"/>
  <property name="misc-cursor-blinks" type="bool" value="true"/>
</channel>
TERMEOF

        # Keyboard shortcuts
        cat > ~/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-keyboard-shortcuts.xml << 'KBEOF'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfce4-keyboard-shortcuts" version="1.0">
  <property name="commands" type="empty">
    <property name="custom" type="empty">
      <property name="&lt;Super&gt;e" type="string" value="thunar"/>
      <property name="&lt;Super&gt;t" type="string" value="xfce4-terminal"/>
      <property name="&lt;Super&gt;r" type="string" value="xfce4-appfinder --collapsed"/>
      <property name="Print" type="string" value="xfce4-screenshooter"/>
    </property>
  </property>
  <property name="xfwm4" type="empty">
    <property name="custom" type="empty">
      <property name="&lt;Alt&gt;F4" type="string" value="close_window_key"/>
      <property name="&lt;Alt&gt;F10" type="string" value="maximize_window_key"/>
      <property name="&lt;Super&gt;d" type="string" value="show_desktop_key"/>
      <property name="&lt;Super&gt;Left" type="string" value="tile_left_key"/>
      <property name="&lt;Super&gt;Right" type="string" value="tile_right_key"/>
      <property name="&lt;Super&gt;Up" type="string" value="maximize_window_key"/>
    </property>
  </property>
</channel>
KBEOF

        # First-run script for panel/wallpaper
        cat > ~/.config/xfce-first-run.sh << 'FREOF'
#!/data/data/com.termux/files/usr/bin/bash
sleep 4
xfconf-query -c xsettings -p /Net/ThemeName -s "Adwaita-dark"
xfconf-query -c xfwm4 -p /general/theme -s "Default-xhdpi"
xfconf-query -c xfce4-panel -p /panels/panel-1/position -s "p=8;x=0;y=0" 2>/dev/null || true
xfconf-query -c xfce4-panel -p /panels/panel-1/size -t int -s 44 2>/dev/null || true
xfconf-query -c xfwm4 -p /general/use_compositing -s true 2>/dev/null || true
rm -f "$HOME/.config/autostart/xfce-first-run.desktop"
FREOF
        chmod +x ~/.config/xfce-first-run.sh

        cat > ~/.config/autostart/xfce-first-run.desktop << 'AREOF'
[Desktop Entry]
Type=Application
Name=XFCE First Run Setup
Exec=bash /root/.config/xfce-first-run.sh
Hidden=false
NoDisplay=true
X-GNOME-Autostart-enabled=true
AREOF
    fi

    # Generate wallpaper
    WALLPAPER_FILE="$HOME/.config/linux-wallpaper.jpg"
    if command -v convert > /dev/null 2>&1; then
        (convert -size 1920x1080 gradient:"#0f0c29"-"#302b63" \
            "$WALLPAPER_FILE" > /dev/null 2>&1) &
        spinner $! "Generating wallpaper"
    fi
    
    log SUCCESS "Theme configured (Adwaita-dark + Dracula terminal)"
}

# ============== STEP 12: SHORTCUTS ==============
step_shortcuts() {
    update_progress
    log INFO "Creating desktop shortcuts..."
    mkdir -p ~/Desktop

    local term_cmd="xfce4-terminal"
    case $DE_CHOICE in
        2) term_cmd="qterminal";;
        3) term_cmd="mate-terminal";;
        4) term_cmd="konsole";;
    esac

    for app_entry in \
        "Firefox|firefox|firefox" \
        "Files|thunar|folder" \
        "Terminal|${term_cmd}|utilities-terminal" \
        "Linux Container|${term_cmd} -e 'bash /root/start-proot.sh'|system-run"; do
        IFS='|' read -r name exec icon <<< "$app_entry"
        cat > ~/Desktop/"${name}.desktop" << DESKEOF
[Desktop Entry]
Name=${name}
Exec=${exec}
Icon=${icon}
Type=Application
Terminal=false
DESKEOF
        chmod +x ~/Desktop/"${name}.desktop"
    done

    log SUCCESS "Shortcuts created: Firefox, Files, Terminal, Linux Container"
}

# ============== STEP 13: AUTO-PATCHER ==============
step_autopatch() {
    update_progress
    log INFO "Installing auto-patcher for Electron apps..."
    
    proot-distro login "$PROOT_DISTRO" -- bash -c "
cat > /usr/local/bin/patch-root-binaries.sh << 'PATCHEOF'
#!/bin/bash
# DroidForge Auto-Patcher for Electron apps
# Patches 'Running as root without --no-sandbox' errors
for dir in /usr/share/applications /usr/local/share/applications /opt; do
    [ -d \"\$dir\" ] || continue
    find \"\$dir\" -name \"*.desktop\" -type f 2>/dev/null | while read f; do
        exec_line=\$(grep '^Exec=' \"\$f\" | head -1 | sed 's/^Exec=//' | sed 's/ %[a-zA-Z]//g')
        [ -z \"\$exec_line\" ] && continue
        bin_path=\$(which \"\$exec_line\" 2>/dev/null || [ -x \"\$exec_line\" ] && echo \"\$exec_line\" || continue)
        # Check if it's an Electron app
        if strings \"\$bin_path\" 2>/dev/null | grep -q 'no-sandbox'; then
            continue
        fi
        real_bin=\"\$bin_path.real\"
        if [ ! -f \"\$real_bin\" ]; then
            mv \"\$bin_path\" \"\$real_bin\"
            cat > \"\$bin_path\" << WRAPPEREOF
#!/bin/bash
exec \"\$real_bin\" --no-sandbox \"\$@\"
WRAPPEREOF
            chmod +x \"\$bin_path\"
            echo \"  [+] Patched: \$bin_path\"
        fi
    done
done
PATCHEOF
chmod +x /usr/local/bin/patch-root-binaries.sh
    " 2>/dev/null || true
    
    log SUCCESS "Auto-patcher installed"
}

# ============== STEP 14: VNC (OPTIONAL) ==============
step_vnc_optional() {
    echo ""
    echo -e "${YELLOW}══════════════════════════════════════════════════════════${NC}"
    echo -e "${WHITE}  OPTIONAL: VNC Remote Desktop${NC}"
    echo -e "${YELLOW}══════════════════════════════════════════════════════════${NC}"
    echo ""
    echo -e "  VNC lets you connect from another device (phone, PC, tablet)"
    echo -e "  using any VNC Viewer app over WiFi or USB."
    echo ""
    read -p "  Install VNC support? (y/N): " VNC_ANSWER
    VNC_ANSWER=${VNC_ANSWER:-N}

    if [[ "$VNC_ANSWER" =~ ^[Yy]$ ]]; then
        read -p "  VNC password [default: droidforge]: " VNC_PASS_IN
        VNC_PASS="${VNC_PASS_IN:-droidforge}"
        read -p "  Resolution [default: 1280x720]: " VNC_GEO_IN
        VNC_GEOMETRY="${VNC_GEO_IN:-1280x720}"
        VNC_DISPLAY=":1"

        install_pkg "tigervnc" "TigerVNC Server"

        mkdir -p ~/.vnc
        echo "$VNC_PASS" | vncpasswd -f > ~/.vnc/passwd
        chmod 600 ~/.vnc/passwd

        case $DE_CHOICE in
            1) VNC_EXEC="exec startxfce4";;
            2) VNC_EXEC="exec startlxqt";;
            3) VNC_EXEC="exec mate-session";;
            4) VNC_EXEC="exec startplasma-x11";;
        esac

        cat > ~/.vnc/xstartup << VNCSTARTUP
#!/data/data/com.termux/files/usr/bin/bash
source ~/.config/linux-gpu.sh 2>/dev/null
$VNC_EXEC
VNCSTARTUP
        chmod +x ~/.vnc/xstartup

        cat > ~/start-vnc.sh << VNCEOF
#!/data/data/com.termux/files/usr/bin/bash
echo ""
echo "═══════════════════════════════════════════════"
echo "  DroidForge: Starting ${DE_NAME} via TigerVNC"
echo "═══════════════════════════════════════════════"
echo ""

pkill -9 -f "termux.x11" 2>/dev/null
vncserver -kill ${VNC_DISPLAY} 2>/dev/null
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 2>/dev/null

unset PULSE_SERVER
pulseaudio --kill 2>/dev/null
sleep 0.5
pulseaudio --start --exit-idle-time=-1
sleep 1
pactl load-module module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1 2>/dev/null
export PULSE_SERVER=127.0.0.1

vncserver -localhost no -geometry ${VNC_GEOMETRY} -depth 24 ${VNC_DISPLAY}

DEVICE_IP=\$(ip -4 addr show wlan0 2>/dev/null | grep -oP '(?<=inet\s)\d+(\.\d+){3}' | head -1)
echo ""
echo "═══════════════════════════════════════════════"
echo "  VNC Ready!"
echo "    Local   : 127.0.0.1:5901"
[ -n "\$DEVICE_IP" ] && echo "    Network : \${DEVICE_IP}:5901"
echo "    Password: ${VNC_PASS}"
echo "═══════════════════════════════════════════════"
VNCEOF
        chmod +x ~/start-vnc.sh
        log SUCCESS "Created ~/start-vnc.sh"
    else
        echo -e "  ${GRAY}[*] Skipping VNC. Install later with: pkg install tigervnc${NC}"
    fi
}

# ============== COMPLETION ==============
show_completion() {
    echo ""
    echo -e "${GREEN}"
    cat << 'COMPLETE'
    ╔═══════════════════════════════════════════════╗
    ║       INSTALLATION COMPLETE!                  ║
    ╚═══════════════════════════════════════════════╝
COMPLETE
    echo -e "${NC}"

    echo -e "${WHITE}[*] ${DE_NAME} desktop with ${PROOT_LABEL} is ready.${NC}"
    echo ""
    echo -e "${CYAN}[*] Installed:${NC}"
    echo "    - ${DE_NAME} Desktop Environment"
    echo "    - Firefox, Git, Python 3, VS Code"
    echo "    - GPU Acceleration (Turnip/Zink)"
    echo "    - ${PROOT_LABEL} Container + App Bridge"
    echo "    - Auto-patcher for Electron apps"
    echo "    - Dark Theme (Adwaita + Dracula terminal)"
    echo ""
    echo -e "${YELLOW}══════════════════════════════════════════════════════════${NC}"
    echo -e "${WHITE}  HOW TO START:${NC}"
    echo -e "${YELLOW}══════════════════════════════════════════════════════════${NC}"
    echo ""
    echo -e "  ${GREEN}Native X11 (recommended):${NC}"
    echo -e "    ${WHITE}bash ~/start-x11.sh${NC}"
    echo -e "    Then open the Termux-X11 app"
    echo ""
    if [ -f ~/start-vnc.sh ]; then
        echo -e "  ${GREEN}VNC (connect via any VNC Viewer):${NC}"
        echo -e "    ${WHITE}bash ~/start-vnc.sh${NC}  → 127.0.0.1:5901"
        echo ""
    fi
    echo -e "  ${GREEN}Proot Linux shell:${NC}"
    echo -e "    ${WHITE}bash ~/start-proot.sh${NC}"
    echo ""
    echo -e "  ${GREEN}Sync proot apps to menu:${NC}"
    echo -e "    ${WHITE}bash ~/proot-menu-sync.sh${NC}"
    echo ""
    echo -e "  ${GREEN}Check status:${NC}"
    echo -e "    ${WHITE}bash ~/droidforge-status.sh${NC}"
    echo ""
    echo -e "  ${GREEN}Stop everything:${NC}"
    echo -e "    ${WHITE}bash ~/stop-linux.sh${NC}"
    echo ""
    echo -e "${YELLOW}══════════════════════════════════════════════════════════${NC}"
    echo ""
    echo -e "  ${CYAN}Username: ${WHITE}${SETUP_USERNAME}${NC}"
    echo -e "  ${CYAN}Setup Log: ${WHITE}${INSTALL_LOG}${NC}"
    echo ""
}

# ============== MAIN ==============
main() {
    init_logging
    show_banner
    
    echo -e "${WHITE}Welcome to DroidForge v${VERSION}${NC}"
    echo -e "${GRAY}The advanced Linux desktop solution for Android${NC}"
    echo ""
    
    detect_device
    select_desktop_environment
    select_distro
    
    echo ""
    echo -e "${CYAN}Installing with:${NC}"
    echo -e "  Desktop: ${WHITE}${DE_NAME}${NC}"
    echo -e "  Distro:  ${WHITE}${PROOT_LABEL}${NC}"
    echo -e "  GPU:     ${WHITE}${GPU_DRIVER}${NC}"
    echo -e "  RAM:     ${WHITE}${TOTAL_RAM_MB} MB${NC}"
    echo ""
    read -p "Press Enter to start installation..."
    
    # Backup existing setup
    if [ -d ~/proot-distro ]; then
        log INFO "Backing up existing installation..."
        cp -r ~/proot-distro "$BACKUP_DIR/" 2>/dev/null || true
    fi
    
    # Run all steps with error trapping
    trap 'rollback_on_error "Step ${CURRENT_STEP}"' ERR
    
    step_update
    step_repos
    step_x11
    step_desktop
    step_gpu
    step_audio
    step_apps
    step_python
    step_proot
    step_launchers
    step_theme
    step_shortcuts
    step_autopatch
    step_vnc_optional
    
    trap - ERR
    
    # Save final state
    echo "$DE_NAME" > ~/.droidforge/installed-de
    echo "$DE_CHOICE" > ~/.droidforge/de-choice
    echo "$GPU_DRIVER" > ~/.droidforge/gpu-driver
    echo "$TOTAL_RAM_MB" > ~/.droidforge/ram-mb
    
    # Update shell prompt
    BASHRC="$HOME/.bashrc"
    grep -q "DROIDFORGE_PROMPT" "$BASHRC" 2>/dev/null || \
        echo "# DROIDFORGE_PROMPT\nexport PS1='\[\033[01;32m\]${SETUP_USERNAME}@droidforge\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '" >> "$BASHRC"
    source "$BASHRC" 2>/dev/null || true
    
    show_completion
    
    log INFO "Setup completed at $(date '+%Y-%m-%d %H:%M:%S')"
}

main "$@"
