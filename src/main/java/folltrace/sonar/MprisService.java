package folltrace.sonar;

import javafx.application.Platform;

import java.io.*;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * MPRIS v2 integration using a small native C daemon ({@code sonar_mpris_d}).
 *
 * The C daemon holds a D-Bus session connection in a process completely
 * separate from the JVM.  JavaFX MediaPlayer's GStreamer backend on Linux
 * is known to close arbitrary file descriptors (including the D-Bus socket),
 * which would break any in-process D-Bus library.  Running the D-Bus
 * connection out-of-process avoids this entirely.
 *
 * IPC between Java and the daemon uses a Unix-domain socket.
 * Java side sends SIGNAL commands (pipe-delimited state updates) and
 * receives CALL commands (method invocations from D-Bus clients).
 */
public final class MprisService {

    private final MprisPlayer player;
    private Process helperProcess;
    private SocketChannel socketChannel;
    private Thread readerThread;
    private volatile boolean running;

    public MprisService(MprisPlayer p) { this.player = p; }

    // ── start / shutdown ──────────────────────────────────────────

    public synchronized void start() {
        if (running) return;
        try {
            String helperPath = locateOrExtractHelper();

            // Create a Unix domain socket server; daemon connects as client
            Path socketPath = Files.createTempFile("sonar_mpris_", ".sock");
            Files.delete(socketPath);  // delete the file so we can bind

            UnixDomainSocketAddress addr = UnixDomainSocketAddress.of(socketPath);
            ServerSocketChannel server = ServerSocketChannel.open(
                    StandardProtocolFamily.UNIX);
            server.bind(addr);

            // Spawn C daemon; pass the socket path via argument
            var pb = new ProcessBuilder(helperPath, socketPath.toString());
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            helperProcess = pb.start();

            // Accept connection from daemon (daemon connects as client)
            server.configureBlocking(true);
            socketChannel = server.accept();
            server.close();
            socketChannel.configureBlocking(false);

            // Wait for READY signal
            ByteBuffer buf = ByteBuffer.allocate(256);
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                int n = socketChannel.read(buf);
                if (n > 0) {
                    buf.flip();
                    byte[] data = new byte[buf.remaining()];
                    buf.get(data);
                    String line = new String(data, StandardCharsets.UTF_8).trim();
                    if (line.startsWith("READY")) {
                        running = true;
                        System.err.println("[Sonar] MPRIS C daemon started");
                        break;
                    }
                }
                try { Thread.sleep(50); } catch (InterruptedException e) { break; }
                buf.clear();
            }

            if (!running) {
                System.err.println("[Sonar] MPRIS daemon did not send READY");
                shutdown();
                return;
            }

            // Start background thread to read CALL commands from daemon
            var ch = socketChannel;
            var p = player;
            readerThread = new Thread(() -> readFromDaemon(ch, p), "MPRIS-reader");
            readerThread.setDaemon(true);
            readerThread.start();

        } catch (Exception e) {
            System.err.println("[Sonar] MPRIS not available: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public synchronized void shutdown() {
        running = false;
        if (socketChannel != null && socketChannel.isOpen()) {
            try { sendLine("QUIT"); } catch (Exception ignored) {}
            try { socketChannel.close(); } catch (Exception ignored) {}
            socketChannel = null;
        }
        if (helperProcess != null) {
            try { helperProcess.waitFor(2, TimeUnit.SECONDS); } catch (Exception ignored) {}
            helperProcess.destroyForcibly();
            helperProcess = null;
        }
    }

    // ── notify state change (Java → daemon) ─────────────────────

    public void notifyStateChanged() {
        if (!running) return;
        try {
            var status = player.getPlaybackStatus().name();
            var title  = player.getTrackTitle();
            var artist = player.getTrackArtist();
            var album  = player.getTrackAlbum();
            var artUrl = player.getTrackArtUrl();
            var durUs  = player.getTrackDurationMicros();
            var posUs  = player.getPositionMicros();
            var vol    = player.getVolume();
            var loop   = player.getLoopStatus();
            var shuf   = player.getShuffle() ? 1 : 0;
            var rate   = player.getRate();

            // 11 fields, pipe-delimited, \\ and \| escaped
            String msg = String.format(
                "SIGNAL %s|%s|%s|%s|%d|%s|%d|%f|%s|%d|%f\n",
                escape(status),
                escape(title),
                escape(artist),
                escape(album),
                durUs,
                escape(artUrl),
                posUs,
                vol,
                escape(loop),
                shuf,
                rate);

            System.err.println("[Sonar] MPRIS notify: status=" + status +
                               " title=" + title);
            sendLine(msg);
        } catch (Exception e) {
            System.err.println("[Sonar] MPRIS notify FAILED: " + e);
        }
    }

    // ── internal helpers ────────────────────────────────────────

    private void sendLine(String line) throws IOException {
        if (socketChannel == null) return;
        byte[] data = line.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.wrap(data);
        while (buf.hasRemaining()) {
            int n = socketChannel.write(buf);
            if (n == 0) {
                try { Thread.sleep(1); } catch (InterruptedException e) { break; }
            }
        }
    }

    private static void readFromDaemon(SocketChannel ch, MprisPlayer player) {
        ByteBuffer buf = ByteBuffer.allocate(8192);
        StringBuilder sb = new StringBuilder();
        try {
            while (ch.isOpen()) {
                int n = ch.read(buf);
                if (n < 0) break;
                if (n == 0) {
                    try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                    continue;
                }
                buf.flip();
                byte[] data = new byte[buf.remaining()];
                buf.get(data);
                buf.clear();
                sb.append(new String(data, StandardCharsets.UTF_8));

                // Process complete lines
                int idx;
                while ((idx = sb.indexOf("\n")) >= 0) {
                    String line = sb.substring(0, idx).trim();
                    sb.delete(0, idx + 1);
                    if (line.startsWith("CALL ")) {
                        String cmd = line.substring(5);
                        int sp = cmd.indexOf(' ');
                        String method = sp >= 0 ? cmd.substring(0, sp) : cmd;
                        String arg = sp >= 0 ? cmd.substring(sp + 1) : null;
                        Platform.runLater(() -> dispatchCall(player, method, arg));
                    }
                }
            }
        } catch (IOException ignored) {}
    }

    private static void dispatchCall(MprisPlayer p, String method, String arg) {
        switch (method) {
            case "PlayPause"   -> p.playPause();
            case "Play"        -> p.play();
            case "Pause"       -> p.pause();
            case "Stop"        -> p.stop();
            case "Next"        -> p.next();
            case "Previous"    -> p.previous();
            case "Seek"        -> { if (arg != null) p.seek(Long.parseLong(arg)); }
            case "SetPosition" -> {
                if (arg != null) {
                    int sp = arg.indexOf(' ');
                    if (sp > 0) p.setPosition(arg.substring(0, sp),
                        Long.parseLong(arg.substring(sp + 1)));
                }
            }
            case "OpenUri"     -> { if (arg != null) p.openUri(arg); }
            case "setVolume"   -> { if (arg != null) p.setVolume(Double.parseDouble(arg)); }
            case "setLoopStatus" -> { if (arg != null) p.setLoopStatus(arg); }
            case "setShuffle"  -> {
                if (arg != null) p.setShuffle(arg.equals("1") || arg.equalsIgnoreCase("true"));
            }
            case "setRate"     -> { if (arg != null) p.setRate(Double.parseDouble(arg)); }
            case "Raise"       -> p.raise();
        }
    }

    private static String escape(String s) {
        if (s == null || s.isEmpty()) return "";
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\')      sb.append("\\\\");
            else if (c == '|')  sb.append("\\|");
            else if (c == '\n' || c == '\r') sb.append(' ');
            else if (c >= 0x20 || c == '\t') sb.append(c);
            // else: drop control chars — ID3 tags are NUL-padded and
            // embedded \u0000 broke both the line protocol (C strchr
            // stops at NUL) and D-Bus strings (NUL is illegal in them).
        }
        return sb.toString();
    }

    // ── locate the C daemon binary ──────────────────────────────

    private static String locateOrExtractHelper() throws IOException {
        // 1. jlink layout: runtime/bin/java → runtime/lib/sonar_mpris_d
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            Path candidate = Path.of(javaHome)
                                  .resolve("lib")
                                  .resolve("sonar_mpris_d");
            if (Files.isExecutable(candidate)) return candidate.toString();
        }

        // 2. Alongside the current working directory (dev)
        Path cwd = Path.of("target").resolve("sonar_mpris_d");
        if (Files.isExecutable(cwd)) return cwd.toString();

        // 3. Compile on the fly (dev convenience)
        Path src = Path.of("src/main/c/sonar_mpris_d.c");
        if (Files.exists(src)) {
            System.err.println("[Sonar] Compiling MPRIS daemon on the fly...");
            var pb = new ProcessBuilder(
                "gcc", "-O2", "-Wall", "-o", cwd.toAbsolutePath().toString(),
                src.toAbsolutePath().toString(),
                "-I/usr/include/dbus-1.0",
                "-I/usr/lib/dbus-1.0/include",
                "-ldbus-1");
            pb.inheritIO();
            Process p = pb.start();
            try {
                int rc = p.waitFor();
                if (rc == 0 && Files.isExecutable(cwd)) return cwd.toAbsolutePath().toString();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        throw new IOException("MPRIS daemon (sonar_mpris_d) not found");
    }
}
