<#
.SYNOPSIS
    Build a self-contained Sonar Windows package using jlink + jpackage.

.DESCRIPTION
    Windows counterpart to package.sh. It mirrors that script through the
    jlink step, then hands the runtime image to jpackage to produce a real
    Sonar.exe launcher instead of jlink's sonar.bat (no stray console window,
    proper icon, and an app-image that can later be wrapped in an MSI).

    Unlike Linux, Windows has neither a system libmpv nor a system ffmpeg, so
    both are downloaded from PINNED upstream releases, verified by SHA-256,
    and bundled. LGPL builds are used deliberately for both: the widely
    mirrored GPL builds would force the whole distribution under GPL terms.

.PARAMETER Version
    Version stamped onto the app and the output filename.

.PARAMETER MpvUrl
    Pinned URL of the mpv "dev" archive containing libmpv-2.dll. Never point
    this at a floating "latest" URL: release contents must be reproducible.

.PARAMETER MpvSha256
    Expected SHA-256 of the archive above. The build fails loudly on mismatch.

.PARAMETER FfmpegUrl
    Pinned URL of the ffmpeg shared LGPL build supplying ffmpeg.exe/ffprobe.exe.

.PARAMETER PfxBase64
    Base64-encoded Authenticode PFX/PKCS#12 certificate. When empty the build
    still succeeds but produces an unsigned package. Normally supplied via the
    SONAR_SIGN_PFX_BASE64 environment variable rather than on the command line.

.PARAMETER PfxPassword
    Password for the PFX above, normally via SONAR_SIGN_PFX_PASSWORD.

.EXAMPLE
    .\package.ps1 -Version 1.0
#>
#Requires -Version 5.1
[CmdletBinding()]
param(
    [string]$Version   = "1.0",

    # mpv LGPL build, pinned. To update: pick a release from
    # https://github.com/zhongfly/mpv-winbuild/releases, take the
    # "mpv-dev-lgpl-x86_64-*.7z" asset, and copy its sha256 from sha256.txt.
    [string]$MpvUrl    = "https://github.com/zhongfly/mpv-winbuild/releases/download/2026-07-26-b27573a239/mpv-dev-lgpl-x86_64-20260726-git-b27573a239.7z",
    [string]$MpvSha256 = "62e93a092e04786bbb1321d47bcadd57d6746b0579110ea04bfd87523a3d0d21",

    # mpv git commit the pinned build was made from, recorded in the LGPL
    # notice so users can obtain the exact corresponding source.
    [string]$MpvCommit = "b27573a239b4da8fd8cf2bbc59d74a1a9b56a32b",

    # ffmpeg LGPL *shared* build, pinned to a MONTH-END autobuild.
    # BtbN prunes daily builds after roughly two weeks but retains the
    # month-end ones long-term (verified back to 2026-03-31), so pinning to a
    # daily tag would 404 within a fortnight and break the build.
    # The shared variant is chosen over the static one on size: static
    # ffmpeg.exe and ffprobe.exe are ~75 MB *each*, whereas the shared pair
    # plus their av* DLLs come to roughly a third of that.
    # To update: pick a month-end tag from
    # https://github.com/BtbN/FFmpeg-Builds/releases, take the
    # "*-win64-lgpl-shared-*.zip" asset, and copy its sha256.
    [string]$FfmpegUrl    = "https://github.com/BtbN/FFmpeg-Builds/releases/download/autobuild-2026-06-30-13-34/ffmpeg-n8.1.2-21-gce3c09c101-win64-lgpl-shared-8.1.zip",
    [string]$FfmpegSha256 = "27bcaf58b5140171dfe838a0b365d12c60607d71fc168424456410bad6a834da",

    # ── Authenticode signing (optional) ───────────────────────────
    [string]$PfxBase64    = $env:SONAR_SIGN_PFX_BASE64,
    [string]$PfxPassword  = $env:SONAR_SIGN_PFX_PASSWORD,
    [string]$TimestampUrl = "http://timestamp.digicert.com"
)

$ErrorActionPreference = "Stop"
$ProgressPreference    = "SilentlyContinue"   # Invoke-WebRequest is glacial otherwise

# Native tools here write harmless noise to stderr (Maven's JVM warnings, for
# one). Keep exit codes as the single source of truth via Invoke-Checked below
# rather than letting PowerShell 7.4+ turn them into terminating errors.
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

# jpackage requires a strictly numeric --app-version on Windows, so a tag like
# v1.2.0-rc1 has to be trimmed for the app image even though the full string
# still names the zip.
$AppVersion = ($Version -replace '[-+].*$', '')
if ($AppVersion -notmatch '^\d+(\.\d+){0,2}$') {
    throw "Version '$Version' yields app-version '$AppVersion', which jpackage will reject. Use major[.minor[.patch]]."
}

# ── Locate the JDK ────────────────────────────────────────────────
if (-not $env:JAVA_HOME -or -not (Test-Path $env:JAVA_HOME)) {
    throw "JAVA_HOME is not set or does not exist. Set it to a JDK 21+ installation."
}
$JdkBin = Join-Path $env:JAVA_HOME "bin"
foreach ($tool in 'jlink', 'jpackage', 'jdeps', 'javac', 'jar') {
    if (-not (Test-Path (Join-Path $JdkBin "$tool.exe"))) {
        throw "$tool not found in $JdkBin. A full JDK (not a JRE) is required."
    }
}
if (-not (Get-Command 7z -ErrorAction SilentlyContinue)) {
    throw "7z not found on PATH. Install 7-Zip (it is preinstalled on GitHub windows runners)."
}

$Mods   = "target\mods"
$Gen    = "target\genmods"
$Jlink  = "target\sonar-jlink"
$Native = "target\native"
$Stage  = "target\jpackage"
$Output = "target\sonar-$Version-windows-x64.zip"
# Deliberately outside target\: `mvn clean` in step 1 wipes that directory, and
# re-downloading 30 MB on every build would be wasteful.
$Dl     = Join-Path ([System.IO.Path]::GetTempPath()) "sonar-mpv-cache"

function Invoke-Checked {
    param([string]$What, [scriptblock]$Body)
    & $Body
    if ($LASTEXITCODE -ne 0) { throw "$What failed with exit code $LASTEXITCODE" }
}

<#
 Download an archive into the cache and verify it against an expected digest.
 Pinning without verifying would leave the build trusting whatever bytes the
 network returned, so a mismatch deletes the file and aborts.
#>
function Get-VerifiedArchive {
    param([string]$Url, [string]$Sha256, [string]$FileName)

    $path = Join-Path $Dl $FileName
    if (-not (Test-Path $path)) {
        Write-Host "    downloading $Url"
        try {
            Invoke-WebRequest -Uri $Url -OutFile $path -UseBasicParsing
        } catch {
            throw "Download failed for $Url`n" +
                  "If this is a 404, the pinned upstream release was probably pruned. " +
                  "Re-pin to a current release and update the matching SHA-256.`n$_"
        }
    }
    $actual = (Get-FileHash $path -Algorithm SHA256).Hash.ToLower()
    if ($actual -ne $Sha256.ToLower()) {
        Remove-Item $path -Force
        throw "Checksum mismatch for $FileName!`n  expected $($Sha256.ToLower())`n  actual   $actual"
    }
    Write-Host "    sha256 ok ($FileName)"
    return $path
}

<# Locate signtool.exe, which the Windows SDK does not put on PATH. #>
function Resolve-SignTool {
    $cmd = Get-Command signtool.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($root in @("${env:ProgramFiles(x86)}\Windows Kits\10\bin",
                        "${env:ProgramFiles}\Windows Kits\10\bin")) {
        if (-not (Test-Path $root)) { continue }
        $found = Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
                 Sort-Object Name -Descending |
                 ForEach-Object { Join-Path $_.FullName 'x64\signtool.exe' } |
                 Where-Object { Test-Path $_ } |
                 Select-Object -First 1
        if ($found) { return $found }
    }
    return $null
}

<#
 Authenticode-sign the given files, if a certificate was supplied.
 Signing is optional on purpose: forks and dispatch builds have no secrets and
 must still produce a working (if unsigned) package.
#>
function Invoke-CodeSign {
    param([string[]]$Files)

    if ([string]::IsNullOrWhiteSpace($PfxBase64)) {
        Write-Warning ("No signing certificate supplied: producing an UNSIGNED build. " +
                       "Windows SmartScreen will warn users on first run.")
        return
    }
    $signtool = Resolve-SignTool
    if (-not $signtool) {
        throw "A signing certificate was supplied but signtool.exe was not found. Install the Windows SDK."
    }
    Write-Host "    signtool: $signtool"

    # Written to disk because signtool has no way to take a certificate on
    # stdin. Kept out of the repo and the build tree, and removed in finally.
    $pfx = Join-Path ([System.IO.Path]::GetTempPath()) ("sonar-sign-" + [guid]::NewGuid().ToString('N') + ".pfx")
    try {
        try {
            [IO.File]::WriteAllBytes($pfx, [Convert]::FromBase64String($PfxBase64))
        } catch {
            throw "Could not decode the signing certificate. Is SONAR_SIGN_PFX_BASE64 valid base64 of a .pfx file?`n$_"
        }
        foreach ($file in $Files) {
            Write-Host "    signing $file"
            $signArgs = @('sign', '/fd', 'SHA256', '/f', $pfx)
            if ($PfxPassword) { $signArgs += @('/p', $PfxPassword) }
            # /tr (RFC3161) rather than the obsolete /t: without a timestamp the
            # signature stops validating the day the certificate expires.
            $signArgs += @('/tr', $TimestampUrl, '/td', 'SHA256', '/d', 'Sonar', $file)
            Invoke-Checked "signtool sign ($file)" { & $signtool @signArgs }
        }

        # Chain validation is deliberately NOT fatal. A self-signed or internal
        # test certificate signs perfectly well but can never chain to a
        # trusted root on the build agent, and failing the build for that would
        # make the certificate impossible to test. What must hold is that a
        # signature is genuinely present on every file.
        & $signtool verify /pa /q @Files
        if ($LASTEXITCODE -ne 0) {
            Write-Warning ("signtool could not validate the certificate chain. This is expected " +
                           "for self-signed or test certificates; for a production certificate " +
                           "it means the chain is incomplete and users will see warnings.")
        }
        foreach ($file in $Files) {
            $sig = Get-AuthenticodeSignature $file
            if ($sig.Status -eq 'NotSigned') {
                throw "signtool reported success but $file carries no signature"
            }
            Write-Host "    $file -> $($sig.Status) [$($sig.SignerCertificate.Subject)]"
        }
    } finally {
        if (Test-Path $pfx) { Remove-Item $pfx -Force }
    }
}

# ── Step 0: fetch and verify native dependencies ──────────────────
# Done first so a bad checksum or a pruned upstream release fails before
# spending minutes on jlink.
Write-Host "=== Step 0: Fetch libmpv + ffmpeg (LGPL, pinned) ==="
New-Item -ItemType Directory -Force -Path $Dl | Out-Null

$mpvArchive = Get-VerifiedArchive -Url $MpvUrl -Sha256 $MpvSha256 -FileName "mpv-dev-$MpvSha256.7z"
$mpvExtract = Join-Path $Dl "mpv-$MpvSha256"
if (-not (Test-Path $mpvExtract)) {
    Invoke-Checked "7z extract (mpv)" { & 7z x $mpvArchive "-o$mpvExtract" -y | Out-Null }
}

# Upstream has renamed this file before (mpv-1.dll -> libmpv-2.dll), so search
# rather than assume, and normalise to the name MpvNative expects.
$dll = Get-ChildItem $mpvExtract -Recurse -File -Include libmpv-2.dll, mpv-2.dll, libmpv.dll |
       Select-Object -First 1
if (-not $dll) { throw "No libmpv DLL found inside $mpvArchive" }
$DllSource = $dll.FullName
Write-Host "    found $($dll.Name)"

$ffArchive = Get-VerifiedArchive -Url $FfmpegUrl -Sha256 $FfmpegSha256 -FileName "ffmpeg-$FfmpegSha256.zip"
$ffExtract = Join-Path $Dl "ffmpeg-$FfmpegSha256"
if (-not (Test-Path $ffExtract)) {
    Invoke-Checked "7z extract (ffmpeg)" { & 7z x $ffArchive "-o$ffExtract" -y | Out-Null }
}

# The zip wraps everything in a versioned top-level folder whose name changes
# on every upstream build, so locate bin/ rather than hardcoding the path.
$ffBin = Get-ChildItem $ffExtract -Recurse -Directory -Filter 'bin' | Select-Object -First 1
if (-not $ffBin) { throw "No bin/ directory found inside $ffArchive" }
foreach ($required in 'ffmpeg.exe', 'ffprobe.exe') {
    if (-not (Test-Path (Join-Path $ffBin.FullName $required))) {
        throw "$required missing from $($ffBin.FullName)"
    }
}
$FfBinSource = $ffBin.FullName
$FfLicense = Get-ChildItem $ffExtract -Recurse -File -Filter 'LICENSE.txt' | Select-Object -First 1
Write-Host "    found ffmpeg.exe and ffprobe.exe"

# ── Step 1: Compile Java & copy dependencies ──────────────────────
# No mpv headers or import library are needed: JNA binds at runtime.
Write-Host "=== Step 1: Compile & copy deps ==="
Invoke-Checked "maven build" {
    & .\mvnw.cmd -q clean compile dependency:copy-dependencies `
        "-DincludeScope=runtime" "-DoutputDirectory=$Mods"
}

# Stage the native payload only now: `mvn clean` above deletes all of target\.
# Layout below becomes the app\ directory of the jpackage image:
#   app\libmpv-2.dll   (renamed to the exact name MpvNative expects)
#   app\ffmpeg\        (isolated in its own folder so its bundled av*.dll set
#                       can never be picked up in place of libmpv's own)
$FfmpegStage = Join-Path $Native "ffmpeg"
New-Item -ItemType Directory -Force -Path $Native, $FfmpegStage | Out-Null
Copy-Item $DllSource (Join-Path $Native "libmpv-2.dll") -Force

# ffplay.exe is deliberately excluded: Sonar never invokes it and it would drag
# in an SDL dependency for nothing.
Copy-Item (Join-Path $FfBinSource 'ffmpeg.exe')  $FfmpegStage -Force
Copy-Item (Join-Path $FfBinSource 'ffprobe.exe') $FfmpegStage -Force
Get-ChildItem $FfBinSource -File -Filter '*.dll' | ForEach-Object {
    Copy-Item $_.FullName $FfmpegStage -Force
}
$ffSize = (Get-ChildItem $FfmpegStage -File | Measure-Object -Property Length -Sum).Sum
Write-Host ("    staged ffmpeg ({0:N1} MB)" -f ($ffSize / 1MB))

# Read coordinates from the POM so they cannot drift from the module path.
function Get-PomProperty {
    param([string]$Name)
    $raw = & .\mvnw.cmd -q help:evaluate "-Dexpression=$Name" -DforceStdout
    if ($LASTEXITCODE -ne 0) { throw "help:evaluate failed for '$Name' with exit code $LASTEXITCODE" }

    # $raw is an array of lines when mvnw prints more than one, but collapses
    # to a bare scalar string when it prints exactly one -- which is the
    # normal case here. Select-Object -Last 1 handles both shapes uniformly.
    # Do NOT index with $raw[-1]: PowerShell also allows [-1] on a plain
    # string, silently returning its last CHARACTER instead of erroring, which
    # is exactly what previously turned "27-ea+25" into "5", "5.14.0" into
    # "0", and "win" into "n".
    $last = $raw | Where-Object { $_ -and "$_".Trim() } | Select-Object -Last 1
    $v = "$last".Trim()
    if (-not $v -or $v -eq 'null object or invalid expression') {
        throw "Could not read '$Name' from pom.xml (raw output: '$raw')"
    }
    return $v
}
$Jfx     = Get-PomProperty 'javafx.version'
$Jna     = Get-PomProperty 'jna.version'
$JfxPlat = Get-PomProperty 'javafx.platform'
Write-Host "    javafx $Jfx ($JfxPlat), jna $Jna, sonar $Version"
if ($JfxPlat -ne 'win') {
    throw "Expected javafx.platform 'win' but got '$JfxPlat'. Is the windows profile in pom.xml active?"
}

# ── Step 2: Inject module-info into automatic modules ─────────────
Write-Host "=== Step 2: Inject module-info into automatic modules ==="
Remove-Item $Gen, $Jlink -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$Gen\jna-classes" | Out-Null

Invoke-Checked "jdeps" {
    & "$JdkBin\jdeps" --generate-module-info $Gen "$Mods\jna-$Jna.jar"
}
Invoke-Checked "javac (jna module-info)" {
    & "$JdkBin\javac" --patch-module "com.sun.jna=$Mods\jna-$Jna.jar" `
        -d "$Gen\jna-classes" "$Gen\com.sun.jna\module-info.java"
}
Copy-Item "$Mods\jna-$Jna.jar" "$Gen\jna-$Jna.jar" -Force
Invoke-Checked "jar update" {
    & "$JdkBin\jar" --update --file="$Gen\jna-$Jna.jar" -C "$Gen\jna-classes" module-info.class
}

# ── Step 3: jlink ─────────────────────────────────────────────────
Write-Host "=== Step 3: jlink ==="
$mp = @("$Gen\jna-$Jna.jar")
foreach ($m in 'base', 'controls', 'fxml', 'graphics') {
    $mp += "$Mods\javafx-$m-$Jfx.jar"
    $mp += "$Mods\javafx-$m-$Jfx-$JfxPlat.jar"
}
$mp += "target\classes"
foreach ($entry in $mp) {
    if (-not (Test-Path $entry)) { throw "Module path entry missing: $entry" }
}

# Same module set as package.sh. Note: no --generate-cds-archive here, because
# jpackage re-materialises the image and the CDS archive is path-sensitive.
$addModules = 'java.base,java.desktop,java.logging,java.scripting,java.xml,' +
              'java.datatransfer,jdk.unsupported,jdk.net,jdk.security.auth,' +
              'javafx.base,javafx.controls,javafx.fxml,javafx.graphics,' +
              'com.sun.jna,folltrace.sonar'

# --add-options bakes the flag into the image, so it applies to the jpackage
# launcher too. JNA reaches libmpv via System.load, which JEP 472 restricts
# from JDK 24 onward; the flag suppresses that warning and pre-empts the later
# switch to a hard failure. On JDK 21..23 it is recognised but inert.
Invoke-Checked "jlink" {
    & "$JdkBin\jlink" --output $Jlink --module-path ($mp -join ';') `
        --add-modules $addModules `
        '--add-options=--enable-native-access=com.sun.jna' `
        --strip-debug --no-header-files --no-man-pages --compress=zip-6
}

# ── Step 4: jpackage app-image ────────────────────────────────────
Write-Host "=== Step 4: jpackage ==="
Remove-Item $Stage -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Stage | Out-Null

$jpArgs = @(
    '--type', 'app-image',
    '--name', 'Sonar',
    '--app-version', $AppVersion,
    '--dest', $Stage,
    '--runtime-image', $Jlink,
    '--module', 'folltrace.sonar/folltrace.sonar.SonarMain',
    '--input', $Native,
    '--vendor', 'folltrace',
    '--description', 'A modern music player',
    '--copyright', 'BSD-3-Clause',
    # CAUTION: $APPDIR is a jpackage substitution token, NOT a PowerShell
    # variable. Both of these must stay single-quoted or PowerShell expands
    # them to empty, and then JNA cannot find the bundled libmpv-2.dll and
    # MediaTools cannot find the bundled ffmpeg.
    '--java-options', '-Djna.library.path=$APPDIR',
    '--java-options', '-Dsonar.app.dir=$APPDIR',
    '--java-options', '-XX:MaxRAMPercentage=10',
    '--java-options', '-Xms32m',
    '--java-options', '-XX:MaxMetaspaceSize=128m',
    '--java-options', '-XX:ReservedCodeCacheSize=64m',
    '--java-options', '-XX:+UseStringDeduplication'
)
if (Test-Path 'assets\sonar.ico') { $jpArgs += @('--icon', 'assets\sonar.ico') }

Invoke-Checked "jpackage" { & "$JdkBin\jpackage" @jpArgs }

$App = Join-Path $Stage "Sonar"
foreach ($required in "Sonar.exe", "app\libmpv-2.dll", "app\ffmpeg\ffprobe.exe", "app\ffmpeg\ffmpeg.exe") {
    if (-not (Test-Path (Join-Path $App $required))) { throw "$required missing from app image" }
}

# ── Step 5: License notices ───────────────────────────────────────
# The OpenJDK runtime's notices ride along in runtime\legal\. Not covered by
# that, and added by hand: Sonar's own BSD-3 notice, OpenJFX (no license
# entries in its jars), JNA (notices live in META-INF and jlink drops them),
# and libmpv, which unlike the Linux build is redistributed here.
Write-Host "=== Step 5: License notices ==="
$Lic = Join-Path $App "licenses"
New-Item -ItemType Directory -Force -Path "$Lic\openjfx", "$Lic\jna", "$Lic\mpv", "$Lic\ffmpeg" | Out-Null

Copy-Item 'LICENSE' "$Lic\LICENSE" -Force

# OpenJFX is GPLv2+CE, the same terms as the bundled OpenJDK runtime, so reuse
# that text rather than vendoring a second copy. Sourced from the jlink image
# rather than the app image, since jpackage is free to prune what it copies.
Copy-Item "$Jlink\legal\java.base\LICENSE" "$Lic\openjfx\LICENSE" -Force
Copy-Item "$Jlink\legal\java.base\ADDITIONAL_LICENSE_INFO" "$Lic\openjfx\ADDITIONAL_LICENSE_INFO" -Force
@"
OpenJFX $Jfx

The javafx.base, javafx.controls, javafx.fxml and javafx.graphics modules
linked into runtime\ are licensed under the GNU General Public License,
version 2, with the Classpath Exception.  The GPLv2 text is in LICENSE
and the Classpath Exception clarification is in ADDITIONAL_LICENSE_INFO,
both in this directory.

Source: https://github.com/openjdk/jfx
"@ | Set-Content "$Lic\openjfx\NOTICE" -Encoding UTF8

# JNA is dual-licensed; both texts ship inside the jar's META-INF.
$jnaJarAbs = (Resolve-Path "$Mods\jna-$Jna.jar").Path
Push-Location "$Lic\jna"
try {
    Invoke-Checked "jar extract (jna licenses)" {
        & "$JdkBin\jar" --extract --file="$jnaJarAbs" META-INF/LICENSE META-INF/AL2.0 META-INF/LGPL2.1
    }
    Move-Item 'META-INF\LICENSE', 'META-INF\AL2.0', 'META-INF\LGPL2.1' . -Force
    Remove-Item 'META-INF' -Recurse -Force
} finally { Pop-Location }

# libmpv: redistributed on Windows, so LGPL-2.1 compliance is on us.
Copy-Item 'assets\licenses\LGPL-2.1.txt' "$Lic\mpv\LGPL-2.1.txt" -Force
@"
libmpv (app\libmpv-2.dll)

This is an unmodified, prebuilt LGPL-2.1-or-later build of mpv's client
library.  Sonar itself is BSD-3-Clause and loads this library dynamically
at runtime via JNA; it is not statically linked and contains no mpv code.

  Upstream archive : $MpvUrl
  SHA-256          : $MpvSha256
  mpv source commit: https://github.com/mpv-player/mpv/commit/$MpvCommit
  mpv project      : https://github.com/mpv-player/mpv
  Build recipe     : https://github.com/zhongfly/mpv-winbuild

As permitted by the LGPL, you may replace app\libmpv-2.dll with your own
compatible build of libmpv; Sonar will load whatever it finds there.

The full LGPL-2.1 text is in LGPL-2.1.txt in this directory.
"@ | Set-Content "$Lic\mpv\NOTICE" -Encoding UTF8

# ffmpeg: also redistributed on Windows only, also an LGPL build.
Copy-Item 'assets\licenses\LGPL-2.1.txt' "$Lic\ffmpeg\LGPL-2.1.txt" -Force
if ($FfLicense) { Copy-Item $FfLicense.FullName "$Lic\ffmpeg\LICENSE.txt" -Force }
@"
ffmpeg (app\ffmpeg\)

Unmodified, prebuilt LGPL-2.1-or-later binaries of ffmpeg and ffprobe, plus
the shared libraries they need.  Sonar invokes them as external processes to
read track durations and extract embedded cover art; no ffmpeg code is linked
into Sonar.  An LGPL build is used deliberately, in preference to the more
common GPL builds, so that this package need not be GPL-licensed.

  Upstream archive : $FfmpegUrl
  SHA-256          : $FfmpegSha256
  ffmpeg source    : https://git.ffmpeg.org/ffmpeg.git
  Build recipe     : https://github.com/BtbN/FFmpeg-Builds

The exact source revision is encoded in the archive filename above and is
reported by `ffmpeg.exe -version`.  LICENSE.txt in this directory is the
notice shipped by the upstream build; LGPL-2.1.txt is the full licence text.
"@ | Set-Content "$Lic\ffmpeg\NOTICE" -Encoding UTF8

# ── README ─────────────────────────────────────────────────────────
@"
Sonar $Version, a music player.
Bundles its own Java runtime and libmpv, so nothing needs to be installed.

Run:  Sonar.exe

Sonar bundles its own Java runtime, libmpv and ffmpeg, so nothing has to be
installed alongside it and nothing is written outside this folder except the
settings file.

Windows notes:
  * MPRIS is a freedesktop.org D-Bus specification and does not exist on
    Windows, so there is no desktop media-key or KDE Connect integration
    in this build.  Playback itself is unaffected.

Licensing:
  Sonar is BSD-3-Clause; see licenses\LICENSE.
  Bundled OpenJFX and the OpenJDK runtime are GPLv2 with the Classpath
  Exception; see licenses\openjfx\ and runtime\legal\.
  Bundled JNA is Apache-2.0 or LGPL-2.1+; see licenses\jna\.
  Bundled libmpv is LGPL-2.1+; see licenses\mpv\.
  Bundled ffmpeg is LGPL-2.1+; see licenses\ffmpeg\.
"@ | Set-Content (Join-Path $App "README.txt") -Encoding UTF8

# ── Step 6: Authenticode signing ──────────────────────────────
# Only our own launcher is signed. The bundled libmpv and ffmpeg binaries are
# third-party and are left with whatever signature (or none) upstream gave
# them; re-signing someone else's binaries would assert an authorship we do
# not have. SmartScreen only evaluates the executable the user launches.
Write-Host "=== Step 6: Code signing ==="
Invoke-CodeSign -Files @((Join-Path $App "Sonar.exe"))

# ── Step 7: Create zip ──────────────────────────────────────
Write-Host "=== Step 7: Create zip ==="
Remove-Item $Output -Force -ErrorAction SilentlyContinue
Push-Location $Stage
try {
    Invoke-Checked "7z zip" { & 7z a -tzip -mx=7 (Join-Path '..\..' $Output) 'Sonar' | Out-Null }
} finally { Pop-Location }

Write-Host ""
Write-Host "Done: $Output"
Get-Item $Output | Select-Object Name, @{n = 'Size'; e = { '{0:N1} MB' -f ($_.Length / 1MB) } }
