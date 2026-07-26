#!/bin/bash
# =====================================================================
# Build a self-contained Sonar Windows package from Linux.
#
# Design notes (why this differs from the previous version):
#
#   1. The runtime is a stock Adoptium Windows JRE, used byte-for-byte
#      as downloaded. No jlink, no DLL allow-list, no stitching a
#      Linux-built jimage into a Windows bin/ directory. A runtime
#      assembled from two different OS builds is not guaranteed to
#      start, and when it fails launch4j reports it generically as
#      "no JRE found", which is what was happening.
#
#   2. launch4j runs in custom-classpath mode (dontWrapJar=true, empty
#      <jar>, explicit <classPath>). The previous config wrapped
#      sonar.jar into the exe while ALSO declaring a <classPath>; those
#      two modes are mutually exclusive, and the wrapped jar was built
#      with plain "jar cf" so it had no Main-Class manifest entry
#      either. The exe had nothing to run.
#
#   3. JavaFX is resolved from the module path via --module-path /
#      --add-modules rather than being thrown on the classpath. The
#      -win classifier jars carry their own native DLLs.
#
# Requires on the build host: curl, unzip, tar, 7z, sha256sum, a JDK,
# and ./mvnw. No Wine, no PowerShell, no Windows VM (except to test).
# =====================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

VERSION="${VERSION:-1.0}"
MAIN_CLASS="${MAIN_CLASS:-folltrace.sonar.SonarMain}"
JRE_MAJOR="${JRE_MAJOR:-25}"

# =====================================================================
# Preflight
# =====================================================================
echo "=== Preflight ==="

for tool in curl unzip tar 7z sha256sum awk find; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "ERROR: required tool '$tool' not found on PATH." >&2
        exit 1
    fi
done

if [ ! -x "./mvnw" ]; then
    echo "ERROR: ./mvnw not found or not executable in $SCRIPT_DIR." >&2
    exit 1
fi

# Locate a JDK for javac/jar. Only used to build the app jar.
if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME="$(./mvnw -v 2>/dev/null | awk '/Java home:|runtime:/ {print $NF}')"
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/jar" ]; then
    echo "ERROR: cannot find a usable JDK. Set JAVA_HOME." >&2
    exit 1
fi
export JAVA_HOME
echo "    JAVA_HOME: $JAVA_HOME"

pom_prop() {
    local v
    v="$(./mvnw -q help:evaluate -Dexpression="$1" -DforceStdout 2>/dev/null)"
    if [ -z "$v" ] || [ "$v" = "null object or invalid expression" ]; then
        echo "ERROR: could not read '$1' from pom.xml" >&2
        exit 1
    fi
    printf '%s' "$v"
}

echo "=== Reading versions from pom.xml ==="
JFX="$(pom_prop javafx.version)"
JNA="$(pom_prop jna.version)"
echo "    javafx ${JFX}, jna ${JNA}, sonar ${VERSION}"

# =====================================================================
# Paths and pinned sources
# =====================================================================
CACHE="$SCRIPT_DIR/.cache"
MODS="$SCRIPT_DIR/target/deps"
PKG="$SCRIPT_DIR/target/package-win/Sonar-${VERSION}"
OUTPUT="$SCRIPT_DIR/target/sonar-${VERSION}-windows-x64.zip"
LAUNCH4J_DIR="$SCRIPT_DIR/target/launch4j"
LAUNCH4J_TGZ="$CACHE/launch4j-3.50-linux-x64.tgz"
LAUNCH4J_JAR="$LAUNCH4J_DIR/launch4j.jar"
LAUNCH4J_BIN="$LAUNCH4J_DIR/launch4j"

# Stock Windows JRE image. Note this is .../jre/... not .../jdk/... --
# we want the prebuilt runtime, not a JDK we then have to strip.
JRE_API="https://api.adoptium.net/v3/binary/latest/${JRE_MAJOR}/ga/windows/x64/jre/hotspot/normal/eclipse"

MPV_URL="https://github.com/zhongfly/mpv-winbuild/releases/download/2026-07-26-b27573a239/mpv-dev-lgpl-x86_64-20260726-git-b27573a239.7z"
MPV_SHA256="62e93a092e04786bbb1321d47bcadd57d6746b0579110ea04bfd87523a3d0d21"
MPV_COMMIT="b27573a239b4da8fd8cf2bbc59d74a1a9b56a32b"

FFMPEG_URL="https://github.com/BtbN/FFmpeg-Builds/releases/download/autobuild-2026-06-30-13-34/ffmpeg-n8.1.2-21-gce3c09c101-win64-lgpl-shared-8.1.zip"
FFMPEG_SHA256="27bcaf58b5140171dfe838a0b365d12c60607d71fc168424456410bad6a834da"

MAVEN_BASE="https://repo.maven.apache.org/maven2/org/openjfx"
FX_MODULES="base graphics controls fxml"

mkdir -p "$CACHE" "$MODS"

verify_sha256() {
    local file="$1" expected="$2" actual
    actual="$(sha256sum "$file" | awk '{print $1}')"
    if [ "$actual" != "$expected" ]; then
        echo "ERROR: checksum mismatch for $file" >&2
        echo "  expected $expected" >&2
        echo "  actual   $actual" >&2
        rm -f "$file"
        exit 1
    fi
}

cached_download() {
    local url="$1" file="$2" sha256="$3"
    if [ ! -f "$file" ]; then
        echo "    downloading $(basename "$file")"
        curl -fsSL -o "$file" "$url" || {
            echo "ERROR: download failed for $url" >&2
            echo "       If this is a 404, the pinned release was pruned. Re-pin." >&2
            exit 1
        }
    fi
    verify_sha256 "$file" "$sha256"
    echo "    sha256 ok ($(basename "$file"))"
}

# =====================================================================
# Step 0: Fetch dependencies
# =====================================================================
echo "=== Step 0: Fetch dependencies ==="

# --- Windows JRE (stock, unmodified) ---
# The Adoptium API redirects to a GitHub asset whose URL changes each
# build, so there is no stable digest to pin here. The download is
# TLS-authenticated and the archive is structurally validated below.
echo "  Windows JRE ${JRE_MAJOR}..."
JRE_ZIP="$CACHE/windows-jre-${JRE_MAJOR}.zip"
JRE_ROOT="$CACHE/windows-jre-${JRE_MAJOR}"
if [ ! -d "$JRE_ROOT" ]; then
    if [ ! -f "$JRE_ZIP" ]; then
        echo "    downloading Temurin Windows JRE ${JRE_MAJOR} (~50 MB)"
        curl -fsSL -o "$JRE_ZIP" "$JRE_API"
    fi
    echo "    extracting"
    rm -rf "$JRE_ROOT"; mkdir -p "$JRE_ROOT"
    unzip -q "$JRE_ZIP" -d "$JRE_ROOT"
fi
WIN_JRE="$(find "$JRE_ROOT" -maxdepth 1 -mindepth 1 -type d | head -1)"
if [ -z "$WIN_JRE" ] || [ ! -d "$WIN_JRE" ]; then
    echo "ERROR: could not find extracted JRE under $JRE_ROOT" >&2
    exit 1
fi

# Validate the runtime BEFORE we build anything around it. If these
# files are missing, launch4j will fail with a misleading "no JRE"
# message at run time on the user's machine instead of here.
for required in bin/javaw.exe bin/java.exe bin/server/jvm.dll lib/modules release; do
    if [ ! -f "$WIN_JRE/$required" ]; then
        echo "ERROR: bundled JRE is incomplete, missing $required" >&2
        echo "       Delete $JRE_ROOT and re-run to re-download." >&2
        exit 1
    fi
done
if ! grep -qi 'OS_NAME="Windows' "$WIN_JRE/release"; then
    echo "ERROR: $WIN_JRE/release does not identify a Windows build." >&2
    echo "       You have downloaded the wrong platform image." >&2
    exit 1
fi
echo "    JRE ok: $(basename "$WIN_JRE") ($(du -sh "$WIN_JRE" | cut -f1))"

# --- launch4j (tgz now, extract after mvn clean wipes target/) ---
echo "  launch4j..."
if [ ! -f "$LAUNCH4J_TGZ" ]; then
    echo "    downloading launch4j 3.50"
    curl -fsSL -o "$LAUNCH4J_TGZ" \
        'https://sourceforge.net/projects/launch4j/files/launch4j-3/3.50/launch4j-3.50-linux-x64.tgz/download'
fi
echo "    cached"

# --- JavaFX Windows SDK jars ---
# Fetched with curl rather than maven-dependency-plugin because that
# plugin chokes on the '+' in OpenJFX ea version strings.
echo "  JavaFX ${JFX} (win)..."
FX_DIR="$CACHE/javafx-win-${JFX}"
mkdir -p "$FX_DIR"
for mod in $FX_MODULES; do
    JAR="$FX_DIR/javafx-${mod}-${JFX}-win.jar"
    if [ ! -f "$JAR" ]; then
        echo "    downloading javafx-${mod}"
        curl -fsSL -o "$JAR" \
            "${MAVEN_BASE}/javafx-${mod}/${JFX}/javafx-${mod}-${JFX}-win.jar" || {
            echo "ERROR: could not fetch javafx-${mod}-${JFX}-win.jar" >&2
            exit 1
        }
    fi
done
echo "    ready"

# --- libmpv ---
echo "  libmpv..."
MPV_ARCHIVE="$CACHE/mpv-dev-lgpl.7z"
MPV_EXTRACT="$CACHE/mpv-extracted"
cached_download "$MPV_URL" "$MPV_ARCHIVE" "$MPV_SHA256"
if [ ! -d "$MPV_EXTRACT" ]; then
    echo "    extracting"
    rm -rf "$MPV_EXTRACT"; mkdir -p "$MPV_EXTRACT"
    7z x "$MPV_ARCHIVE" -o"$MPV_EXTRACT" -y >/dev/null
fi
MPV_DLL="$(find "$MPV_EXTRACT" \( -iname 'libmpv-2.dll' -o -iname 'mpv-2.dll' -o -iname 'libmpv.dll' \) | head -1)"
if [ -z "$MPV_DLL" ]; then
    echo "ERROR: no libmpv DLL inside $MPV_ARCHIVE" >&2
    exit 1
fi
echo "    $(basename "$MPV_DLL")"

# --- ffmpeg ---
echo "  ffmpeg..."
FFMPEG_ARCHIVE="$CACHE/ffmpeg-lgpl-shared.zip"
FFMPEG_EXTRACT="$CACHE/ffmpeg-extracted"
cached_download "$FFMPEG_URL" "$FFMPEG_ARCHIVE" "$FFMPEG_SHA256"
if [ ! -d "$FFMPEG_EXTRACT" ]; then
    echo "    extracting"
    rm -rf "$FFMPEG_EXTRACT"; mkdir -p "$FFMPEG_EXTRACT"
    unzip -q "$FFMPEG_ARCHIVE" -d "$FFMPEG_EXTRACT"
fi
FFMPEG_BIN="$(find "$FFMPEG_EXTRACT" -type d -name bin | head -1)"
for tool in ffmpeg.exe ffprobe.exe; do
    if [ ! -f "$FFMPEG_BIN/$tool" ]; then
        echo "ERROR: $tool missing from ffmpeg archive" >&2
        exit 1
    fi
done
FFMPEG_LICENSE="$(find "$FFMPEG_EXTRACT" -iname 'LICENSE.txt' | head -1)"
echo "    ready"

# =====================================================================
# Step 1: Compile and collect runtime dependencies
# =====================================================================
echo "=== Step 1: Compile ==="
./mvnw -q clean compile dependency:copy-dependencies \
    -DincludeScope=runtime -DoutputDirectory="$MODS"

# The app itself runs from the classpath (unnamed module). JavaFX is
# resolved from the module path instead, so a stray module-info would
# only create conflicting requirements.
rm -f target/classes/module-info.class

# Extract launch4j now that mvn clean has finished wiping target/.
if [ ! -f "$LAUNCH4J_JAR" ]; then
    echo "    extracting launch4j"
    mkdir -p "$SCRIPT_DIR/target"
    tar xzf "$LAUNCH4J_TGZ" -C "$SCRIPT_DIR/target"
    sed -i 's/\r$//' "$LAUNCH4J_BIN" 2>/dev/null || true
    chmod +x "$LAUNCH4J_BIN" 2>/dev/null || true
fi
if [ ! -f "$LAUNCH4J_JAR" ]; then
    echo "ERROR: launch4j.jar not found after extraction." >&2
    exit 1
fi

# =====================================================================
# Step 2: Assemble the package tree
# =====================================================================
echo "=== Step 2: Assemble package tree ==="
rm -rf "$PKG"
mkdir -p "$PKG/app/lib" "$PKG/app/javafx" "$PKG/app/native" "$PKG/app/ffmpeg"

# Copy the JRE verbatim.
echo "    copying JRE"
cp -r "$WIN_JRE" "$PKG/jre"

# Build the application jar WITH a Main-Class manifest entry. The old
# script used "jar cf", producing a manifest-less jar that nothing
# could launch.
echo "    building sonar.jar (main: $MAIN_CLASS)"
MAIN_CLASS_FILE="target/classes/$(echo "$MAIN_CLASS" | tr '.' '/').class"
if [ ! -f "$MAIN_CLASS_FILE" ]; then
    echo "ERROR: main class $MAIN_CLASS not found in target/classes." >&2
    echo "       Override with MAIN_CLASS=your.Main $0" >&2
    exit 1
fi

# Bytecode version guard. A class file compiled by a newer JDK than the
# bundled runtime throws UnsupportedClassVersionError at startup, and
# under javaw.exe that failure is completely silent. Class file major
# version is always the Java feature release plus 44.
CLASS_MAJOR="$(od -An -tu1 -j6 -N2 "$MAIN_CLASS_FILE" | awk '{print $1*256+$2}')"
CLASS_JAVA=$((CLASS_MAJOR - 44))
if [ "$CLASS_JAVA" -gt "$JRE_MAJOR" ]; then
    echo "ERROR: classes are compiled for Java ${CLASS_JAVA} but the bundled" >&2
    echo "       runtime is Java ${JRE_MAJOR}. The app would fail to start." >&2
    echo "       Fix either side:" >&2
    echo "         - set <maven.compiler.release>${JRE_MAJOR}</maven.compiler.release> in pom.xml, or" >&2
    echo "         - rebuild with JRE_MAJOR=${CLASS_JAVA} $0" >&2
    exit 1
fi
echo "    bytecode Java ${CLASS_JAVA} <= runtime Java ${JRE_MAJOR}, ok"

# Stage the classes so a hand-written src/main/resources/META-INF/
# MANIFEST.MF cannot collide with the manifest jar generates for us.
JAR_STAGE="$SCRIPT_DIR/target/jar-stage"
rm -rf "$JAR_STAGE"
mkdir -p "$JAR_STAGE"
cp -r target/classes/. "$JAR_STAGE/"
rm -f "$JAR_STAGE/META-INF/MANIFEST.MF"
rmdir "$JAR_STAGE/META-INF" 2>/dev/null || true

"$JAVA_HOME/bin/jar" --create \
    --file "$PKG/app/sonar.jar" \
    --main-class "$MAIN_CLASS" \
    -C "$JAR_STAGE" .
rm -rf "$JAR_STAGE"

# JavaFX goes on the module path, everything else on the classpath.
echo "    copying JavaFX modules"
for mod in $FX_MODULES; do
    cp "$FX_DIR/javafx-${mod}-${JFX}-win.jar" "$PKG/app/javafx/"
done

echo "    copying runtime dependencies"
shopt -s nullglob
for jar in "$MODS"/*.jar; do
    case "$(basename "$jar")" in
        javafx-*) : ;;  # win-classifier copies already staged above
        *) cp "$jar" "$PKG/app/lib/" ;;
    esac
done
shopt -u nullglob
if [ ! -f "$PKG/app/lib/jna-${JNA}.jar" ]; then
    echo "ERROR: jna-${JNA}.jar was not copied. Check the runtime scope." >&2
    exit 1
fi

echo "    copying natives"
cp "$MPV_DLL" "$PKG/app/native/libmpv-2.dll"
cp "$FFMPEG_BIN"/ffmpeg.exe "$PKG/app/ffmpeg/"
cp "$FFMPEG_BIN"/ffprobe.exe "$PKG/app/ffmpeg/"
shopt -s nullglob
for dll in "$FFMPEG_BIN"/*.dll; do cp "$dll" "$PKG/app/ffmpeg/"; done
shopt -u nullglob

# =====================================================================
# Step 3: Produce Sonar.exe
# =====================================================================
echo "=== Step 3: launch4j ==="

# Enumerate classpath entries at build time rather than relying on
# wildcard expansion inside the exe.
CP_ENTRIES="    <cp>app/sonar.jar</cp>"
for jar in "$PKG"/app/lib/*.jar; do
    CP_ENTRIES="${CP_ENTRIES}
    <cp>app/lib/$(basename "$jar")</cp>"
done

FX_ADD_MODULES="$(echo $FX_MODULES | sed 's/\([a-z]*\)/javafx.\1/g; s/ /,/g')"

LAUNCH4J_XML="$PKG/launch4j.xml"
cat > "$LAUNCH4J_XML" << L4JEOF
<?xml version="1.0" encoding="UTF-8"?>
<launch4jConfig>
  <!-- Custom classpath mode: the jar is NOT wrapped into the exe, and
       <jar> stays empty so launch4j uses <classPath> below. Setting
       both a wrapped jar and a classPath is contradictory. -->
  <dontWrapJar>true</dontWrapJar>
  <headerType>gui</headerType>
  <jar></jar>
  <outfile>Sonar.exe</outfile>
  <errTitle>Sonar</errTitle>
  <chdir>.</chdir>
  <priority>normal</priority>
  <stayAlive>false</stayAlive>
  <restartOnCrash>false</restartOnCrash>
  <classPath>
    <mainClass>${MAIN_CLASS}</mainClass>
${CP_ENTRIES}
  </classPath>
  <jre>
    <!-- Relative to the exe. bundledJreAsFallback=false means "use
         this runtime", not "use it only if the system has none". -->
    <path>jre</path>
    <bundledJre64Bit>true</bundledJre64Bit>
    <bundledJreAsFallback>false</bundledJreAsFallback>
    <requiresJdk>false</requiresJdk>
    <requires64Bit>true</requires64Bit>
    <opt>--module-path "%EXEDIR%\app\javafx"</opt>
    <opt>--add-modules ${FX_ADD_MODULES}</opt>
    <opt>-Djna.library.path="%EXEDIR%\app\native"</opt>
    <opt>-Dsonar.app.dir="%EXEDIR%\app"</opt>
    <opt>-Dsonar.ffmpeg.dir="%EXEDIR%\app\ffmpeg"</opt>
    <opt>-Xms32m</opt>
    <opt>-XX:MaxMetaspaceSize=128m</opt>
  </jre>
</launch4jConfig>
L4JEOF

echo "    running launch4j"
( cd "$PKG" && java -jar "$LAUNCH4J_JAR" launch4j.xml )
rm -f "$LAUNCH4J_XML"

if [ ! -f "$PKG/Sonar.exe" ]; then
    echo "ERROR: launch4j did not produce Sonar.exe" >&2
    exit 1
fi
echo "    Sonar.exe: $(du -h "$PKG/Sonar.exe" | cut -f1)"

# --- Console launcher for diagnosis ---
# Uses java.exe, not javaw.exe, so stack traces are actually visible.
# If Sonar.exe misbehaves, run this and read the output.
cat > "$PKG/Sonar-debug.bat" << 'BATEOF'
@echo off
setlocal
set DIR=%~dp0
echo Launching Sonar with console output...
echo.
"%DIR%jre\bin\java.exe" ^
  --module-path "%DIR%app\javafx" ^
  --add-modules javafx.base,javafx.graphics,javafx.controls,javafx.fxml ^
  -Djna.library.path="%DIR%app\native" ^
  -Dsonar.app.dir="%DIR%app" ^
  -Dsonar.ffmpeg.dir="%DIR%app\ffmpeg" ^
  -cp "%DIR%app\sonar.jar;%DIR%app\lib\*" ^
  MAIN_CLASS_PLACEHOLDER %*
echo.
echo Exit code: %ERRORLEVEL%
pause
BATEOF
sed -i "s/MAIN_CLASS_PLACEHOLDER/${MAIN_CLASS}/" "$PKG/Sonar-debug.bat"
# CRLF so Windows cmd.exe parses the caret continuations correctly.
sed -i 's/$/\r/' "$PKG/Sonar-debug.bat"

# =====================================================================
# Step 4: License notices
# =====================================================================
echo "=== Step 4: License notices ==="
LIC="$PKG/licenses"
mkdir -p "$LIC"/{openjdk,openjfx,jna,mpv,ffmpeg}

cp LICENSE "$LIC/LICENSE"

if [ -d "$PKG/jre/legal" ]; then
    cp -r "$PKG/jre/legal/." "$LIC/openjdk/" 2>/dev/null || true
fi
cat > "$LIC/openjdk/NOTICE" << EOF
Eclipse Temurin JRE ${JRE_MAJOR} (jre/)

Redistributed unmodified. Licensed under the GNU General Public
License, version 2, with the Classpath Exception. Per-module legal
texts are in this directory and in jre/legal/.

Source: https://github.com/adoptium/temurin${JRE_MAJOR}-binaries
EOF

for mod in $FX_MODULES; do
    # System unzip. The JDK does not ship an unzip binary.
    unzip -o -q "$PKG/app/javafx/javafx-${mod}-${JFX}-win.jar" \
        'META-INF/LICENSE*' -d "$LIC/openjfx" 2>/dev/null || true
done
cat > "$LIC/openjfx/NOTICE" << EOF
OpenJFX ${JFX} (app/javafx/)

The javafx.base, javafx.graphics, javafx.controls and javafx.fxml
modules are licensed under the GNU General Public License, version 2,
with the Classpath Exception. See licenses/openjdk/ for the GPLv2 text
and the Classpath Exception clarification.

Source: https://github.com/openjdk/jfx
EOF

JNA_JAR="$PKG/app/lib/jna-${JNA}.jar"
if [ -f "$JNA_JAR" ]; then
    ( cd "$LIC/jna" \
      && "$JAVA_HOME/bin/jar" --extract --file="$JNA_JAR" \
           META-INF/LICENSE META-INF/AL2.0 META-INF/LGPL2.1 2>/dev/null || true
      if [ -d META-INF ]; then
          mv META-INF/* . 2>/dev/null || true
          rm -rf META-INF
      fi )
fi

cp assets/licenses/LGPL-2.1.txt "$LIC/mpv/LGPL-2.1.txt"
cat > "$LIC/mpv/NOTICE" << EOF
libmpv (app/native/libmpv-2.dll)

Unmodified LGPL-2.1-or-later build of mpv's client library. Sonar is
BSD-3-Clause and loads this library dynamically via JNA.

  Upstream archive:  $MPV_URL
  SHA-256:           $MPV_SHA256
  mpv source commit: https://github.com/mpv-player/mpv/commit/$MPV_COMMIT
  Build recipe:      https://github.com/zhongfly/mpv-winbuild

Full LGPL-2.1 text in LGPL-2.1.txt.
EOF

cp assets/licenses/LGPL-2.1.txt "$LIC/ffmpeg/LGPL-2.1.txt"
if [ -n "$FFMPEG_LICENSE" ] && [ -f "$FFMPEG_LICENSE" ]; then
    cp "$FFMPEG_LICENSE" "$LIC/ffmpeg/LICENSE.txt"
fi
cat > "$LIC/ffmpeg/NOTICE" << EOF
ffmpeg (app/ffmpeg/)

Unmodified LGPL-2.1-or-later binaries of ffmpeg and ffprobe. Sonar
invokes them as external processes; no ffmpeg code is linked in. An
LGPL build is used deliberately to avoid GPL restrictions.

  Upstream archive: $FFMPEG_URL
  SHA-256:          $FFMPEG_SHA256
  ffmpeg source:    https://git.ffmpeg.org/ffmpeg.git
  Build recipe:     https://github.com/BtbN/FFmpeg-Builds

LICENSE.txt is from the upstream build; LGPL-2.1.txt is the full licence.
EOF

cat > "$PKG/README.txt" << EOF
Sonar ${VERSION} for Windows

Run Sonar.exe. Nothing to install, no system Java required: a complete
Java runtime ships in jre/.

If Sonar.exe does not start, run Sonar-debug.bat instead. It launches
the same application through a console window so the error is visible.

Bundles a Java runtime, JavaFX, libmpv and ffmpeg.
Settings are saved to %APPDATA%\\sonar\\.

MPRIS (desktop media keys) is Linux-only and does not work on Windows.
Playback and album art are unaffected.

Licensing:
  Sonar:    BSD-3-Clause            -> licenses/LICENSE
  OpenJDK:  GPLv2 + Classpath Exc.  -> licenses/openjdk/
  OpenJFX:  GPLv2 + Classpath Exc.  -> licenses/openjfx/
  JNA:      Apache-2.0 or LGPL-2.1+ -> licenses/jna/
  libmpv:   LGPL-2.1+               -> licenses/mpv/
  ffmpeg:   LGPL-2.1+               -> licenses/ffmpeg/
EOF

# =====================================================================
# Step 5: Verify the layout before shipping
# =====================================================================
echo "=== Step 5: Verify ==="
FAIL=0
check() {
    if [ ! -e "$PKG/$1" ]; then
        echo "    MISSING: $1" >&2
        FAIL=1
    else
        echo "    ok: $1"
    fi
}
check Sonar.exe
check Sonar-debug.bat
check jre/bin/javaw.exe
check jre/bin/java.exe
check jre/bin/server/jvm.dll
check jre/lib/modules
check app/sonar.jar
check app/native/libmpv-2.dll
check app/ffmpeg/ffmpeg.exe
for mod in $FX_MODULES; do
    check "app/javafx/javafx-${mod}-${JFX}-win.jar"
done

# Confirm the jar really carries a Main-Class entry.
# Note: system unzip. There is no unzip binary inside a JDK.
if ! unzip -p "$PKG/app/sonar.jar" META-INF/MANIFEST.MF 2>/dev/null \
     | grep -qi '^Main-Class:'; then
    echo "    MISSING: Main-Class in app/sonar.jar manifest" >&2
    FAIL=1
else
    echo "    ok: sonar.jar Main-Class ($MAIN_CLASS)"
fi

if [ "$FAIL" -ne 0 ]; then
    echo "ERROR: package layout is incomplete, refusing to zip." >&2
    exit 1
fi

# =====================================================================
# Step 6: Zip
# =====================================================================
echo "=== Step 6: Create zip ==="
rm -f "$OUTPUT"
( cd "$(dirname "$PKG")" && 7z a -tzip -mx=7 "$OUTPUT" "$(basename "$PKG")" >/dev/null )

echo ""
echo "Done: $OUTPUT"
ls -lh "$OUTPUT"
echo ""
echo "Test on Windows: unzip, run Sonar.exe."
echo "If it fails, run Sonar-debug.bat and read the console output."
