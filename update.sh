#!/bin/bash
# update.sh — Rebuild Sonar, uninstall old version, install new one.
# Run from project root:  sudo ./update.sh
set -euo pipefail

VERSION="1.0"
TARBALL="target/sonar-${VERSION}-linux.tar.gz"
EXTRACT_DIR="target/sonar-extract"

if [ "$(id -u)" -ne 0 ]; then
    printf "Must run as root (sudo ./update.sh)\n"
    exit 1
fi

# ── Step 1: Build the package ─────────────────────────────────────
echo "=== Building Sonar ${VERSION} ==="
./package.sh

if [ ! -f "${TARBALL}" ]; then
    printf "ERROR: Package not found at %s\n" "${TARBALL}"
    exit 1
fi

# ── Step 2: Uninstall old version if present ──────────────────────
if [ -d /opt/sonar ]; then
    echo "=== Removing previous Sonar installation ==="
    rm -f /usr/local/bin/sonar
    rm -f /usr/share/applications/sonar.desktop
    rm -f /usr/share/icons/hicolor/256x256/apps/sonar.png
    rm -f /usr/share/dbus-1/services/org.mpris.MediaPlayer2.sonar.service
    rm -rf /opt/sonar
    command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache /usr/share/icons/hicolor/ || true
    command -v update-desktop-database >/dev/null && update-desktop-database /usr/share/applications/ || true
    echo "Old version removed."
fi

# ── Step 3: Extract & install ─────────────────────────────────────
echo "=== Installing Sonar ${VERSION} ==="
rm -rf "${EXTRACT_DIR}"
mkdir -p "${EXTRACT_DIR}"
tar xzf "${TARBALL}" -C "${EXTRACT_DIR}"

cd "${EXTRACT_DIR}/sonar-${VERSION}"
./install.sh
cd - >/dev/null

# ── Step 4: Verify ─────────────────────────────────────────────────
echo ""
echo "=== Installation complete ==="
echo "  Binary:    /opt/sonar/sonar"
echo "  Desktop:   /usr/share/applications/sonar.desktop"
echo "  MPRIS:     /usr/share/dbus-1/services/org.mpris.MediaPlayer2.sonar.service"
echo "  Daemon:    /opt/sonar/runtime/lib/sonar_mpris_d"
echo ""
echo "Run: sonar"
echo "MPRIS test: playerctl -p sonar play-pause"
