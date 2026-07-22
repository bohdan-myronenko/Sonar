#!/bin/bash
# Build a self-contained Sonar Linux package using jlink.
# Run from project root:  ./package.sh
set -euo pipefail

# Locate the JDK used by Maven (needed for jdeps, javac, jlink, jar)
JAVA_HOME="${JAVA_HOME:-}"
if [ -z "${JAVA_HOME}" ]; then
    # Match both "Java home:" (older Maven) and "runtime:" (newer JDK output)
    JAVA_HOME="$(./mvnw -v 2>/dev/null | awk '/Java home:|runtime:/ {print $NF}')"
fi
if [ -z "${JAVA_HOME}" ] || [ ! -d "${JAVA_HOME}" ]; then
    echo "ERROR: Cannot find JDK. Set JAVA_HOME or ensure ./mvnw -v shows a Java home."
    exit 1
fi
export JAVA_HOME
JDK_BIN="${JAVA_HOME}/bin"

VERSION="1.0"
JFX="27-ea+25"
MP3AGIC="0.9.1"
OUTPUT="target/sonar-${VERSION}-linux.tar.gz"
MODS="target/mods"
GEN="target/genmods"
JLINK="target/sonar-jlink"

# ── Step 0: Build the native MPRIS C daemon ───────────────────────
# Must run BEFORE mvnw clean (which wipes target/),
# or save the binary outside target/.  We use a temp location.
echo "=== Step 0: Build MPRIS C daemon ==="
DBUS_CFLAGS="-I/usr/include/dbus-1.0 -I/usr/lib/dbus-1.0/include -ldbus-1"
# Try pkg-config first if available
if command -v pkg-config >/dev/null 2>&1; then
    DBUS_CFLAGS="$(pkg-config --cflags --libs dbus-1)"
fi
gcc -O2 -Wall -o target/sonar_mpris_d \
    src/main/c/sonar_mpris_d.c \
    ${DBUS_CFLAGS}
if [ ! -x target/sonar_mpris_d ]; then
    echo "ERROR: MPRIS daemon failed to build"
    exit 1
fi
# Save binary outside target/ so mvnw clean doesn't delete it
cp target/sonar_mpris_d /tmp/sonar_mpris_d

# ── Step 1: Compile Java & copy dependencies ──────────────────────
echo "=== Step 1: Compile & copy deps ==="
./mvnw -q clean compile dependency:copy-dependencies \
    -DincludeScope=runtime -DoutputDirectory="$MODS"

# Restore the C daemon
cp /tmp/sonar_mpris_d target/sonar_mpris_d

# ── Step 2: Inject module-info into mp3agic (automatic module) ────
echo "=== Step 2: Inject module-info into mp3agic (automatic module) ==="
rm -rf "$GEN" "$JLINK"
mkdir -p "$GEN"
"${JDK_BIN}/jdeps" --generate-module-info "$GEN" "$MODS/mp3agic-${MP3AGIC}.jar"
mkdir -p "$GEN/mp3agic-classes"
"${JDK_BIN}/javac" --patch-module "mp3agic=$MODS/mp3agic-${MP3AGIC}.jar" \
      -d "$GEN/mp3agic-classes" "$GEN/mp3agic/module-info.java"
cp "$MODS/mp3agic-${MP3AGIC}.jar" "$GEN/mp3agic-${MP3AGIC}.jar"
"${JDK_BIN}/jar" --update --file="$GEN/mp3agic-${MP3AGIC}.jar" \
    -C "$GEN/mp3agic-classes" module-info.class

echo "=== Step 3: jlink ==="
MP="$GEN/mp3agic-${MP3AGIC}.jar"
for m in base controls fxml graphics media swing; do
    MP="$MP:$MODS/javafx-${m}-${JFX}.jar:$MODS/javafx-${m}-${JFX}-linux.jar"
done
MP="$MP:target/classes"

"${JDK_BIN}/jlink" --output "$JLINK" --module-path "$MP" \
      --add-modules java.base,java.desktop,java.logging,java.scripting,java.xml,java.datatransfer,jdk.unsupported,jdk.net,jdk.security.auth,javafx.base,javafx.controls,javafx.fxml,javafx.graphics,javafx.media,javafx.swing,mp3agic,folltrace.sonar \
      --launcher "sonar=folltrace.sonar/folltrace.sonar.SonarMain" \
      --strip-debug --no-header-files --no-man-pages --compress=zip-6

# ── Step 4: Assemble the package directory ────────────────────────
echo "=== Step 4: Assemble package ==="
PKG="target/package/sonar-${VERSION}"
rm -rf "$PKG"
mkdir -p "$PKG"/{runtime,share/applications,share/icons/hicolor/256x256/apps,share/doc/sonar,share/dbus-1/services}
cp -r "$JLINK"/* "$PKG/runtime/"
chmod 755 "$PKG/runtime/bin/"*

# Install the MPRIS C daemon alongside the runtime lib
mkdir -p "$PKG/runtime/lib"
cp target/sonar_mpris_d "$PKG/runtime/lib/"
chmod 755 "$PKG/runtime/lib/sonar_mpris_d"

# ── Launcher script ───────────────────────────────────────────────
cat > "$PKG/sonar" << 'LAUNCHER'
#!/bin/sh
exec "$(dirname "$(readlink -f "$0")")/runtime/bin/sonar" "$@"
LAUNCHER
chmod 755 "$PKG/sonar"

# ── Desktop entry ─────────────────────────────────────────────────
cat > "$PKG/share/applications/sonar.desktop" << DESKTOP
[Desktop Entry]
Name=Sonar
Comment=A modern music player
Exec=/opt/sonar/sonar
Icon=sonar
Terminal=false
Type=Application
Categories=AudioVideo;Audio;Player;
Keywords=music;player;audio;
StartupNotify=true
StartupWMClass=Sonar
DBusActivatable=false
DESKTOP

cp src/main/resources/logo.png \
   "$PKG/share/icons/hicolor/256x256/apps/sonar.png"

# ── D-Bus service file (for auto-discovery, not auto-activation) ─
# While we don't support true DBus activation (the C daemon is
# spawned by the Java app, not by dbus-daemon), shipping this file
# helps desktop environments discover the MPRIS identity.
cat > "$PKG/share/dbus-1/services/org.mpris.MediaPlayer2.sonar.service" << DBUSSVC
[D-BUS Service]
Name=org.mpris.MediaPlayer2.sonar
Exec=/opt/sonar/sonar
DBUSSVC

# ── Install script ────────────────────────────────────────────────
cat > "$PKG/install.sh" << 'INSTALL'
#!/bin/sh
set -e
DEST="/opt/sonar"
SRC="$(cd "$(dirname "$0")" && pwd)"
printf "Installing Sonar to %s...\n" "${DEST}"
if [ "$(id -u)" -ne 0 ]; then printf "Must run as root (sudo).\n"; exit 1; fi
[ -d "${DEST}" ] && rm -rf "${DEST}"
mkdir -p "${DEST}"
cp -r "${SRC}/runtime" "${SRC}/sonar" "${SRC}/share" "${DEST}/"
chmod +x "${DEST}/sonar" "${DEST}/runtime/bin/"*
chmod +x "${DEST}/runtime/lib/sonar_mpris_d"
install -m 644 "${DEST}/share/applications/sonar.desktop" /usr/share/applications/
install -m 644 "${DEST}/share/icons/hicolor/256x256/apps/sonar.png" /usr/share/icons/hicolor/256x256/apps/
install -m 644 "${DEST}/share/dbus-1/services/org.mpris.MediaPlayer2.sonar.service" /usr/share/dbus-1/services/
ln -sf "${DEST}/sonar" /usr/local/bin/sonar
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache /usr/share/icons/hicolor/ || true
command -v update-desktop-database >/dev/null && update-desktop-database /usr/share/applications/ || true
printf "\nSonar installed! Run: sonar\n"
INSTALL
chmod 755 "$PKG/install.sh"

# ── Uninstall script ──────────────────────────────────────────────
cat > "$PKG/uninstall.sh" << 'UNINSTALL'
#!/bin/sh
set -e
if [ "$(id -u)" -ne 0 ]; then printf "Must run as root.\n"; exit 1; fi
printf "Uninstalling Sonar...\n"
rm -f /usr/local/bin/sonar
rm -f /usr/share/applications/sonar.desktop
rm -f /usr/share/icons/hicolor/256x256/apps/sonar.png
rm -f /usr/share/dbus-1/services/org.mpris.MediaPlayer2.sonar.service
rm -rf /opt/sonar
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache /usr/share/icons/hicolor/ || true
command -v update-desktop-database >/dev/null && update-desktop-database /usr/share/applications/ || true
printf "Sonar uninstalled.\n"
UNINSTALL
chmod 755 "$PKG/uninstall.sh"

# ── README ─────────────────────────────────────────────────────────
cat > "$PKG/share/doc/sonar/README" << EOF
Sonar ${VERSION} — Self-contained music player (MPRIS-capable).
No Java required.

Install:   sudo ./install.sh
Run:       sonar
Uninstall: sudo ./uninstall.sh

MPRIS:
  Sonar registers org.mpris.MediaPlayer2.sonar on the session bus.
  Control it with playerctl, kdeconnect, or any MPRIS client:
    playerctl -p sonar play-pause
    playerctl -p sonar next
    playerctl -p sonar previous
EOF

# ── Step 5: Create tarball ────────────────────────────────────────
echo "=== Step 5: Create tarball ==="
cd target/package
tar czf "../sonar-${VERSION}-linux.tar.gz" "sonar-${VERSION}"
cd ../..

echo ""
echo "Done: ${OUTPUT}"
ls -lh "$OUTPUT"
