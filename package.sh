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
JNA="5.14.0"
OUTPUT="target/sonar-${VERSION}-linux.tar.gz"
MODS="target/mods"
GEN="target/genmods"
JLINK="target/sonar-jlink"

# ── Step 0: Build the native MPRIS C daemon ───────────────────────
# Must run BEFORE mvnw clean (which wipes target/),
# or save the binary outside target/.  We use a temp location.
echo "=== Step 0: Build MPRIS C daemon ==="
# target/ may not exist yet on a clean checkout, and this step runs before
# Maven would create it.
mkdir -p target
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

# ── Step 2: Inject module-info into automatic modules ─────────────
echo "=== Step 2: Inject module-info into automatic modules ==="
rm -rf "$GEN" "$JLINK"
mkdir -p "$GEN"

# ── JNA ───────────────────────────────────────────────────────────
"${JDK_BIN}/jdeps" --generate-module-info "$GEN" "$MODS/jna-${JNA}.jar"
mkdir -p "$GEN/jna-classes"
"${JDK_BIN}/javac" --patch-module "com.sun.jna=$MODS/jna-${JNA}.jar" \
      -d "$GEN/jna-classes" "$GEN/com.sun.jna/module-info.java"
cp "$MODS/jna-${JNA}.jar" "$GEN/jna-${JNA}.jar"
"${JDK_BIN}/jar" --update --file="$GEN/jna-${JNA}.jar" \
    -C "$GEN/jna-classes" module-info.class

echo "=== Step 3: jlink ==="
MP="$GEN/jna-${JNA}.jar"
for m in base controls fxml graphics; do
    MP="$MP:$MODS/javafx-${m}-${JFX}.jar:$MODS/javafx-${m}-${JFX}-linux.jar"
done
MP="$MP:target/classes"

"${JDK_BIN}/jlink" --output "$JLINK" --module-path "$MP" \
      --add-modules java.base,java.desktop,java.logging,java.scripting,java.xml,java.datatransfer,jdk.unsupported,jdk.net,jdk.security.auth,javafx.base,javafx.controls,javafx.fxml,javafx.graphics,com.sun.jna,folltrace.sonar \
      --launcher "sonar=folltrace.sonar/folltrace.sonar.SonarMain" \
      --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
      --generate-cds-archive

# ── Step 4: Assemble the package directory ────────────────────────
echo "=== Step 4: Assemble package ==="
PKG="target/package/sonar-${VERSION}"
rm -rf "$PKG"
mkdir -p "$PKG"/{runtime,share/applications,share/icons/hicolor/256x256/apps,share/doc/sonar,share/dbus-1/services}
mkdir -p "$PKG/share/doc/sonar/licenses"/{openjfx,jna}
cp -r "$JLINK"/* "$PKG/runtime/"
chmod 755 "$PKG/runtime/bin/"*

# Install the MPRIS C daemon alongside the runtime lib
mkdir -p "$PKG/runtime/lib"
cp target/sonar_mpris_d "$PKG/runtime/lib/"
chmod 755 "$PKG/runtime/lib/sonar_mpris_d"

# ── Launcher script ───────────────────────────────────────────────
cat > "$PKG/sonar" << 'LAUNCHER'
#!/bin/sh
export JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=10 -Xms32m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=64m -XX:+UseStringDeduplication"
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

# ── License notices ───────────────────────────────────────────────
# The OpenJDK runtime's own notices already arrive via runtime/legal/
# (copied wholesale from the jlink image above).  Three things are NOT
# covered by that and must be added by hand:
#   1. Sonar's own BSD-3 notice, which clause 2 requires to accompany
#      binary redistribution.
#   2. OpenJFX, whose jars carry no license entries at all.
#   3. JNA, whose notices live in META-INF/ and are discarded by jlink.
echo "=== Step 4b: License notices ==="
LIC="$PKG/share/doc/sonar/licenses"

cp LICENSE "$LIC/LICENSE"

# OpenJFX is GPLv2 with the Classpath Exception, the same terms as the
# bundled OpenJDK runtime, so reuse that text rather than vendoring a
# second copy or fetching one at build time.
cp "$JLINK/legal/java.base/LICENSE"                 "$LIC/openjfx/LICENSE"
cp "$JLINK/legal/java.base/ADDITIONAL_LICENSE_INFO" "$LIC/openjfx/ADDITIONAL_LICENSE_INFO"
cat > "$LIC/openjfx/NOTICE" << EOF
OpenJFX ${JFX}

The javafx.base, javafx.controls, javafx.fxml and javafx.graphics modules
linked into runtime/ are licensed under the GNU General Public License,
version 2, with the Classpath Exception.  The GPLv2 text is in LICENSE
and the Classpath Exception clarification is in ADDITIONAL_LICENSE_INFO,
both in this directory.

Source: https://github.com/openjdk/jfx
EOF

# JNA is dual-licensed; both texts ship inside the jar's META-INF.
JNA_JAR_ABS="$(pwd)/$MODS/jna-${JNA}.jar"
(
  cd "$LIC/jna"
  "${JDK_BIN}/jar" --extract --file="$JNA_JAR_ABS" \
      META-INF/LICENSE META-INF/AL2.0 META-INF/LGPL2.1
  mv META-INF/LICENSE META-INF/AL2.0 META-INF/LGPL2.1 .
  rm -rf META-INF
)

# ── README ─────────────────────────────────────────────────────────
cat > "$PKG/share/doc/sonar/README" << EOF
Sonar ${VERSION}, a music player with MPRIS v2 support.
Bundles its own Java runtime, so no JRE installation is needed.

Install:   sudo ./install.sh
Run:       sonar
Uninstall: sudo ./uninstall.sh

MPRIS:
  Sonar registers org.mpris.MediaPlayer2.sonar on the session bus.
  Control it with playerctl, kdeconnect, or any MPRIS client:
    playerctl -p sonar play-pause
    playerctl -p sonar next
    playerctl -p sonar previous

Requires libmpv on the system (install mpv or libmpv).
ffmpeg is optional and used only for album art extraction.

Licensing:
  Sonar is BSD-3-Clause; see licenses/LICENSE.
  Bundled OpenJFX and the OpenJDK runtime are GPLv2 with the
  Classpath Exception; see licenses/openjfx/ and runtime/legal/.
  Bundled JNA is Apache-2.0 or LGPL-2.1+; see licenses/jna/.
EOF

# ── Step 5: Create tarball ────────────────────────────────────────
echo "=== Step 5: Create tarball ==="
cd target/package
tar czf "../sonar-${VERSION}-linux.tar.gz" "sonar-${VERSION}"
cd ../..

echo ""
echo "Done: ${OUTPUT}"
ls -lh "$OUTPUT"
