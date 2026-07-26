#!/bin/bash
# update.sh - Rebuild Sonar and install it for the current user.
# Run from project root:  ./update.sh
#
# No root required.  Everything goes under $HOME using XDG locations, so
# the build never runs as root and target/ stays owned by you.
#
#   ./update.sh              rebuild and install
#   ./update.sh --uninstall  remove the per-user install
set -euo pipefail

# Kept in sync with package.sh: exported so the child build stamps the same
# version this script then looks for.
VERSION="${VERSION:-1.0}"
export VERSION
TARBALL="target/sonar-${VERSION}-linux.tar.gz"
EXTRACT_DIR="target/sonar-extract"

DATA_HOME="${XDG_DATA_HOME:-${HOME}/.local/share}"
PREFIX="${DATA_HOME}/sonar"
BIN_DIR="${HOME}/.local/bin"
APP_DIR="${DATA_HOME}/applications"
ICON_DIR="${DATA_HOME}/icons/hicolor/256x256/apps"
DBUS_DIR="${DATA_HOME}/dbus-1/services"

# Running under sudo would build as root (leaving target/ root-owned) and
# resolve $HOME to root's home, installing to the wrong place.
if [ "$(id -u)" -eq 0 ]; then
    printf "Do not run this as root. It installs under \$HOME and needs no privileges.\n"
    printf "Run: ./update.sh\n"
    exit 1
fi

refresh_caches() {
    if command -v update-desktop-database >/dev/null 2>&1; then
        update-desktop-database "$APP_DIR" >/dev/null 2>&1 || true
    fi
    if command -v gtk-update-icon-cache >/dev/null 2>&1; then
        gtk-update-icon-cache -t -q "${DATA_HOME}/icons/hicolor" >/dev/null 2>&1 || true
    fi
}

remove_user_install() {
    rm -rf "$PREFIX"
    rm -f "${BIN_DIR}/sonar"
    rm -f "${APP_DIR}/sonar.desktop"
    rm -f "${ICON_DIR}/sonar.png"
    rm -f "${DBUS_DIR}/org.mpris.MediaPlayer2.sonar.service"
}

if [ "${1:-}" = "--uninstall" ]; then
    echo "=== Removing per-user Sonar install ==="
    remove_user_install
    refresh_caches
    echo "Removed."
    exit 0
fi

# ── Step 1: Build the package ─────────────────────────────────────
echo "=== Building Sonar ${VERSION} ==="
./package.sh

if [ ! -f "${TARBALL}" ]; then
    printf "ERROR: Package not found at %s\n" "${TARBALL}"
    exit 1
fi

# ── Step 2: Remove any previous per-user install ──────────────────
if [ -e "$PREFIX" ] || [ -e "${BIN_DIR}/sonar" ]; then
    echo "=== Removing previous install ==="
    remove_user_install
fi

# ── Step 3: Extract & install ─────────────────────────────────────
echo "=== Installing Sonar ${VERSION} to ${PREFIX} ==="
rm -rf "${EXTRACT_DIR}"
mkdir -p "${EXTRACT_DIR}"
tar xzf "${TARBALL}" -C "${EXTRACT_DIR}"

SRC="${EXTRACT_DIR}/sonar-${VERSION}"
mkdir -p "$PREFIX" "$BIN_DIR" "$APP_DIR" "$ICON_DIR" "$DBUS_DIR"

cp -r "${SRC}/runtime" "${PREFIX}/"
cp "${SRC}/sonar" "${PREFIX}/sonar"
# Keep the license notices alongside the installed binaries.
cp -r "${SRC}/share/doc/sonar" "${PREFIX}/doc"
chmod 755 "${PREFIX}/sonar" "${PREFIX}/runtime/bin/"*
chmod 755 "${PREFIX}/runtime/lib/sonar_mpris_d"

# The launcher resolves its own directory, so a symlink here works.
ln -sf "${PREFIX}/sonar" "${BIN_DIR}/sonar"

# The packaged .desktop and D-Bus files hardcode the /opt prefix used by
# install.sh, so point them at the per-user location instead.
sed "s|^Exec=.*|Exec=${PREFIX}/sonar|" \
    "${SRC}/share/applications/sonar.desktop" > "${APP_DIR}/sonar.desktop"
sed "s|^Exec=.*|Exec=${PREFIX}/sonar|" \
    "${SRC}/share/dbus-1/services/org.mpris.MediaPlayer2.sonar.service" \
    > "${DBUS_DIR}/org.mpris.MediaPlayer2.sonar.service"
cp "${SRC}/share/icons/hicolor/256x256/apps/sonar.png" "${ICON_DIR}/sonar.png"

refresh_caches

# ── Step 4: Verify ─────────────────────────────────────────────────
echo ""
echo "=== Installation complete ==="
echo "  Binary:    ${PREFIX}/sonar"
echo "  Launcher:  ${BIN_DIR}/sonar"
echo "  Desktop:   ${APP_DIR}/sonar.desktop"
echo "  MPRIS:     ${DBUS_DIR}/org.mpris.MediaPlayer2.sonar.service"
echo "  Daemon:    ${PREFIX}/runtime/lib/sonar_mpris_d"
echo "  Licenses:  ${PREFIX}/doc/licenses"
echo ""

case ":${PATH}:" in
    *":${BIN_DIR}:"*) ;;
    *) printf "Note: %s is not on your PATH. Add it, or run %s/sonar directly.\n\n" \
              "$BIN_DIR" "$PREFIX" ;;
esac

if [ -d /opt/sonar ]; then
    printf "Note: an older system-wide install is still present at /opt/sonar\n"
    printf "and will shadow this one if /usr/local/bin is earlier in your PATH.\n"
    printf "Remove it once with: sudo /opt/sonar/uninstall.sh\n\n"
fi

echo "Run: sonar"
echo "MPRIS test: playerctl -p sonar play-pause"
