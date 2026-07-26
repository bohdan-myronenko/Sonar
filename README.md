<p align="center">
  <img src="./assets/readme/hero.svg" width="100%"
       alt="Sonar, a Linux music player with MPRIS integration">
</p>

Sonar is a desktop music player for Linux. It bundles its own Java runtime, so you don't need a JRE installed, and it plays 27 audio formats through libmpv. It implements MPRIS v2, so your desktop environment, `playerctl`, and KDE Connect can control playback natively.

---

<p align="center">
  <img src="./assets/readme/section-showcase.svg" width="100%" alt="Showcase">
</p>

<table>
<tr>
<td align="center" width="50%">
  <p><strong>Main window</strong></p>
  <img src="./assets/readme/screenshot-main.png" width="100%" alt="Sonar main player window with album art, playlist, and controls">
</td>
<td align="center" width="50%">
  <p><strong>Mini player</strong></p>
  <img src="./assets/readme/screenshot-mini.png" width="100%" alt="Sonar mini player, a compact floating window">
</td>
</tr>
</table>

<p align="center">
  <strong>MPRIS control</strong><br>
  <img src="./assets/readme/screenshot-mpris.png" width="100%" alt="playerctl commands controlling Sonar via MPRIS D-Bus interface">
</p>

---

## Design notes

Most Java media players use JavaFX MediaPlayer, which sits on GStreamer and registers as "GStreamer" in the system mixer. Sonar calls libmpv directly through JNA instead, so the audio pipeline runs inside the JVM and shows up as "Sonar" in your mixer and volume controls.

The MPRIS D-Bus service runs in a separate C daemon rather than inside the JVM. GStreamer on Linux is known to close arbitrary file descriptors, including the D-Bus socket, which breaks in-process D-Bus libraries. Keeping the connection in its own process avoids that class of problem regardless of what native code the JVM loads.

| Approach | Audio backend | Mixer label | MPRIS |
|----------|--------------|-------------|-------|
| JavaFX MediaPlayer (typical) | GStreamer | GStreamer | In-process, D-Bus socket can be closed |
| Sonar | libmpv (JNA) | Sonar | Out-of-process C daemon, isolated from JVM file descriptors |

---

## Features

- **Bundled runtime.** `jlink` ships a minimal JDK 21 runtime, so no Java installation is required.
- **27 audio formats.** MP3, FLAC, Ogg, Opus, AAC, WAV, WMA, APE, ALAC, MIDI, tracker modules, and more.
- **MPRIS v2.** Control playback from GNOME Shell, KDE Connect, `playerctl`, or any MPRIS client. Media keys work on desktops that route them through MPRIS.
- **Album art.** Extracts embedded cover art with ffmpeg, falling back to a placeholder when none is found.
- **Playlist management.** Drag-and-drop, folder scanning, extended M3U save and load.
- **Shuffle modes.** Shuffle all, or shuffle-next to pick one random track without reordering the playlist.
- **Repeat modes.** Off, repeat all, repeat one.
- **Mini player.** Shrink to a compact floating window.
- **Dark and light themes.** Toggled from the menu bar.
- **Custom window chrome.** Undecorated window with an integrated drag region and minimize/close controls.
- **Settings persistence.** Volume and theme are saved to `~/.config/sonar/settings.properties`.

---

## Install

Download the latest `sonar-1.0-linux.tar.gz` from the [releases page](../../releases), then:

```bash
tar xzf sonar-1.0-linux.tar.gz
cd sonar-1.0
sudo ./install.sh
sonar
```

This installs system-wide to `/opt/sonar` and needs root. For a build from source you can install without root instead; see below.

### Runtime requirements

The package bundles a Java runtime but links against two system libraries:

- **`libmpv`** (required). Sonar loads it at runtime and will not play audio without it. Install `mpv` or `libmpv` from your distribution's repositories.
- **`ffmpeg`** (optional). Used only to extract embedded album art. Without it, tracks show the placeholder cover.

### Building from source

Build prerequisites:

- JDK 21+
- `gcc` and `libdbus-1-dev`, for the MPRIS C daemon
- Maven, handled by the bundled `mvnw` wrapper

**Per-user install (no root).** `update.sh` builds the package and installs it under `$HOME` using XDG locations:

```bash
git clone https://github.com/bohdan-myronenko/Sonar.git
cd Sonar
./update.sh
```

It refuses to run as root, so the build artifacts in `target/` stay owned by you. Files land in:

| Path | Contents |
|------|----------|
| `~/.local/share/sonar/` | Runtime, launcher, license notices |
| `~/.local/bin/sonar` | Symlink to the launcher |
| `~/.local/share/applications/` | Desktop entry |
| `~/.local/share/icons/hicolor/` | Icon |
| `~/.local/share/dbus-1/services/` | MPRIS service file |

Re-run `./update.sh` to rebuild and reinstall over a previous per-user install. If `~/.local/bin` is not on your `PATH`, the script says so.

**System-wide install.** Build the tarball and use the generated installer:

```bash
git clone https://github.com/bohdan-myronenko/Sonar.git
cd Sonar
./package.sh
cd target/package/sonar-1.0
sudo ./install.sh
```

Don't run `./package.sh` itself under `sudo`. Only `install.sh` needs root, and building as root leaves `target/` owned by `root`, which breaks later builds.

---

## Usage

```bash
sonar
```

### MPRIS control

Once Sonar is running, any MPRIS client can control it:

```bash
playerctl -p sonar play-pause
playerctl -p sonar next
playerctl -p sonar previous
playerctl -p sonar volume 0.8
playerctl -p sonar metadata
```

KDE Connect, GNOME Shell's media widget, and status-bar player applets discover Sonar automatically.

---

## Architecture

```
┌──────────────────────────────────────┐
│            JavaFX UI                 │
│  SonarController ── MiniController   │
└──────────────┬───────────────────────┘
               │
┌──────────────▼───────────────────────┐
│         Player (libmpv via JNA)      │
│  audio decode  →  system mixer       │
│  event loop    →  property observe   │
└──────────────┬───────────────────────┘
               │           IPC (Unix socket)
┌──────────────▼───────────────────────────────────┐
│            MprisService (Java)                   │
│  sends state     │     receives D-Bus calls      │
└──────┬───────────┴──────────────┬────────────────┘
       │                          │
┌──────▼──────────────────────────▼────────────────┐
│        sonar_mpris_d (C daemon)                  │
│  D-Bus session bus   ←→   org.mpris.MediaPlayer2 │
└──────────────────────────────────────────────────┘
```

The C daemon holds the D-Bus connection in a process separate from the JVM. The Java side sends playback state over a Unix-domain socket, and the daemon translates incoming D-Bus method calls (Play, Pause, Seek, and so on) back over the same channel. This keeps the D-Bus connection isolated from any file-descriptor handling inside the JVM.

---

## Supported formats

`.mp3` `.wav` `.aac` `.m4a` `.flac` `.ogg` `.opus` `.aiff` `.aif` `.wma` `.wavpack` `.wv` `.ape` `.mka` `.ac3` `.dts` `.alac` `.tta` `.spx` `.ra` `.rm` `.mid` `.midi` `.mod` `.xm` `.s3m` `.it`

---

## Uninstall

For a per-user install, from the project root:

```bash
./update.sh --uninstall
```

For a system-wide install:

```bash
sudo /opt/sonar/uninstall.sh
```

Or manually:

```bash
sudo rm -rf /opt/sonar
sudo rm -f /usr/local/bin/sonar
sudo rm -f /usr/share/applications/sonar.desktop
sudo rm -f /usr/share/icons/hicolor/256x256/apps/sonar.png
sudo rm -f /usr/share/dbus-1/services/org.mpris.MediaPlayer2.sonar.service
```

If both are installed, whichever of `~/.local/bin` and `/usr/local/bin` comes first on your `PATH` wins.

---

## Tech stack

| Layer | Technology |
|-------|-----------|
| UI | JavaFX 27 (FXML) |
| Language | Java 21 |
| Audio | libmpv via JNA |
| Desktop integration | MPRIS v2 D-Bus spec |
| IPC | Unix-domain sockets |
| Packaging | `jlink` plus a shell launcher |
| Build | Maven |

---

## License

Sonar is licensed under the [BSD 3-Clause License](LICENSE).

`src/main/resources/folltrace/sonar/player.fxml` derives from the [Gluon Scene Builder samples](https://gluonhq.com/products/scene-builder/) and retains its original BSD 3-Clause notice from Oracle and Gluon.

Third-party components in the distributed package:

| Component | License | Distribution |
|-----------|---------|--------------|
| OpenJDK runtime (`jlink`) | GPLv2 with Classpath Exception | Bundled |
| OpenJFX | GPLv2 with Classpath Exception | Bundled |
| JNA | Apache-2.0 or LGPL-2.1+ | Bundled |
| libmpv | LGPL-2.1+ | System library, not bundled |
| libdbus | AFL-2.1 or GPL-2.0+ | System library, not bundled |
