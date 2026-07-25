<p align="center">
  <img src="./assets/readme/hero.svg" width="100%"
       alt="Sonar — self-contained Linux music player with MPRIS integration">
</p>

Sonar is a desktop music player for Linux that ships as one self-contained package — no JRE, no mpv install, no extra dependencies. It plays 25+ audio formats through libmpv and exposes full MPRIS v2 control, so your desktop environment, `playerctl`, and KDE Connect can manage playback natively.

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
  <img src="./assets/readme/screenshot-mini.png" width="100%" alt="Sonar mini player — compact floating window">
</td>
</tr>
</table>

<p align="center">
  <strong>MPRIS control</strong><br>
  <img src="./assets/readme/screenshot-mpris.png" width="100%" alt="playerctl commands controlling Sonar via MPRIS D-Bus interface">
</p>

---

## What makes it different

Most Java media players rely on JavaFX MediaPlayer, which sits on GStreamer and registers as "GStreamer" in the system mixer. **Sonar embeds libmpv via JNA** — the audio pipeline runs inside the JVM and appears as "Sonar" in your system mixer and volume controls.

The MPRIS D-Bus service runs through a **separate out-of-process C daemon** rather than inside the JVM. This sidesteps a known GStreamer bug where the D-Bus socket gets closed mid-session, a common failure mode for in-process MPRIS libraries on Linux.

| Approach | Audio backend | Mixer label | MPRIS reliability |
|----------|--------------|-------------|-------------------|
| JavaFX MediaPlayer (typical) | GStreamer | GStreamer | Fragile — D-Bus socket can be closed |
| **Sonar** | **libmpv (JNA)** | **Sonar** | **Out-of-process C daemon — immune to JVM socket interference** |

---

## Features

- **Self-contained package** — `jlink` bundles a minimal JDK 21 runtime; install and run without Java
- **25+ audio formats** — MP3, FLAC, Ogg, Opus, AAC, WAV, WMA, APE, ALAC, MIDI, tracker modules, and more
- **Album art** — Extracts embedded cover art via ffmpeg with a cached fallback
- **MPRIS v2** — Control playback from GNOME Shell, KDE Connect, `playerctl`, or any MPRIS-compatible client
- **Playlist management** — Drag-and-drop, folder scanning, extended M3U save/load
- **Shuffle modes** — Shuffle all or shuffle-next (pick one random track without reordering)
- **Repeat modes** — Off, repeat all, repeat one
- **Mini player** — Shrink to a compact floating window
- **Dark / light theme** — Togglable from the menu bar
- **Custom window chrome** — Undecorated window with integrated drag region and minimize/close
- **Media keys** — System media keys (play, pause, next, previous) work out of the box
- **Settings persistence** — Volume and theme saved to `~/.config/sonar/`

---

## Install

Download the latest `sonar-1.0-linux.tar.gz` from the [releases page](../../releases), then:

```bash
tar xzf sonar-1.0-linux.tar.gz
cd sonar-1.0
sudo ./install.sh
sonar
```

To build from source:

```bash
git clone https://github.com/folltrace/Sonar.git
cd Sonar
./package.sh
cd target/package/sonar-1.0
sudo ./install.sh
```

### Prerequisites (build only)

- JDK 21+
- Maven (the included `mvnw` wrapper handles this)
- `gcc` (for the MPRIS C daemon)
- `libdbus-1-dev` (for the MPRIS C daemon)
- `ffmpeg` (runtime dependency for album art extraction)

---

## Usage

```bash
sonar                          # Launch the player
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

KDE Connect, GNOME Shell's media widget, and status-bar player applets all discover Sonar automatically.

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
│            MprisService (Java)                    │
│  sends state     │     receives D-Bus calls       │
└──────┬───────────┴──────────────┬────────────────┘
       │                          │
┌──────▼──────────────────────────▼────────────────┐
│        sonar_mpris_d (C daemon)                   │
│  D-Bus session bus   ←→   org.mpris.MediaPlayer2 │
└──────────────────────────────────────────────────┘
```

The C daemon holds the D-Bus connection in a process entirely separate from the JVM. The Java side sends playback state updates over a Unix-domain socket; the daemon translates incoming D-Bus method calls (Play, Pause, Seek, etc.) back over the same channel. This keeps D-Bus alive regardless of what GStreamer or any other native library does inside the JVM.

---

## Supported formats

`.mp3` `.wav` `.aac` `.m4a` `.flac` `.ogg` `.opus` `.aiff` `.aif` `.wma` `.wavpack` `.wv` `.ape` `.mka` `.ac3` `.dts` `.alac` `.tta` `.spx` `.ra` `.rm` `.mid` `.midi` `.mod` `.xm` `.s3m` `.it`

---

## Uninstall

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

---

## Tech stack

| Layer | Technology |
|-------|-----------|
| UI | JavaFX 27 (FXML) |
| Language | Java 21 |
| Audio | libmpv via JNA |
| Desktop integration | MPRIS v2 D-Bus spec |
| IPC | Unix-domain sockets |
| Packaging | `jlink` + shell launcher |
| Build | Maven |

---

## License

Licensed under the [BSD 3-Clause License](LICENSE). The FXML layout derives from the [Gluon Scene Builder samples](https://gluonhq.com/products/scene-builder/) and is used under the same terms.

---

<p align="center">
  <sub>A modern music player for the Linux desktop. Built to be self-contained, not self-important.</sub>
</p>
