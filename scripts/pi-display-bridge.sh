#!/bin/bash

# ========================================================
# DroidForge Raspberry Pi Display Bridge
# Auto-detects phone via USB and launches VNC viewer
# ========================================================

echo ""
echo "═══════════════════════════════════════════════"
echo "  DroidForge Raspberry Pi Display Bridge"
echo "═══════════════════════════════════════════════"
echo ""

# Configuration
MAX_RETRIES=30
VNC_PORT=5901
PHONE_IP=""

echo "[*] Detecting phone via USB tethering..."

for i in $(seq 1 $MAX_RETRIES); do
    PHONE_IP=$(ip route | grep default | awk '{print $3}' | head -n 1)
    
    if [ -n "$PHONE_IP" ]; then
        echo "[+] Phone detected at IP: $PHONE_IP"
        break
    else
        echo "[-] Waiting for USB Tethering... ($((MAX_RETRIES - i))s remaining)"
        sleep 1
    fi
done

if [ -n "$PHONE_IP" ]; then
    echo ""
    echo "[+] Launching VNC Desktop in FullScreen..."
    echo "    Target: ${PHONE_IP}:${VNC_PORT}"
    echo ""
    
    # Check if vncviewer is available
    if command -v vncviewer &> /dev/null; then
        vncviewer "${PHONE_IP}::${VNC_PORT}" \
            -FullScreen \
            -QualityLevel 5 \
            -CompressLevel 6 \
            -LowColorLevel 0 \
            -MenuKey=F8
    elif command -v realvnc-vnc-viewer &> /dev/null; then
        realvnc-vnc-viewer "${PHONE_IP}::${VNC_PORT}" \
            -FullScreen \
            -QualityLevel 5 \
            -CompressLevel 6
    else
        echo "[!] No VNC viewer found."
        echo "[!] Install one with:"
        echo "    sudo apt install realvnc-vnc-viewer"
        echo "    or"
        echo "    sudo apt install tigervnc-viewer"
        exit 1
    fi
else
    echo ""
    echo "[!] ERROR: No phone detected via USB."
    echo ""
    echo "[!] Troubleshooting:"
    echo "    1. Connect phone to Pi via USB cable"
    echo "    2. Enable USB Tethering on phone:"
    echo "       Settings → Network → Tethering → USB Tethering"
    echo "    3. Start VNC on phone: bash ~/start-vnc.sh"
    echo "    4. Run this script again"
    echo ""
    exit 1
fi
