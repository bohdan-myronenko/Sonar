package folltrace.sonar;

import java.io.*;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * Audio player backed by mpv via JSON IPC over a Unix domain socket.
 * mpv supports virtually every audio format through ffmpeg.
 */
public class Player implements AutoCloseable {

    private Process mpvProcess;
    private SocketChannel socket;
    private BufferedWriter writer;
    private BufferedReader reader;
    private final Path socketPath;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "mpv-ipc");
        t.setDaemon(true);
        return t;
    });

    // Cached state updated by the reader thread
    private volatile double position;       // seconds
    private volatile double duration;       // seconds
    private volatile double volume = 1.0;   // 0.0–1.0
    private volatile double speed = 1.0;    // playback rate
    private volatile boolean paused;
    private volatile boolean eof;
    private volatile Map<String, String> metadata = Map.of();
    private volatile String sourceUri = "";

    private static final double SEEK_THRESHOLD = 0.1;
    private double pendingSeekTarget = -1;

    private final PlayerCallback callback;

    public Player(PlayerCallback callback) throws IOException {
        this.callback = callback;

        // Create unique socket path in /tmp
        socketPath = Files.createTempFile("sonar-mpv-", ".sock");
        Files.delete(socketPath);

        System.err.println("[mpv] Starting mpv with socket: " + socketPath);

        var pb = new ProcessBuilder(
                "mpv",
                "--no-video",
                "--no-terminal",
                "--idle=yes",
                "--audio-display=no",
                "--volume=100",
                "--volume-max=100",
                "--input-ipc-server=" + socketPath.toAbsolutePath()
        );
        // Inherit stderr so mpv errors show up in the terminal
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        mpvProcess = pb.start();

        if (!mpvProcess.isAlive()) {
            throw new IOException("mpv process exited immediately. Is mpv installed?");
        }

        // Wait for mpv to create the socket (give it up to 5 seconds)
        for (int i = 0; i < 50; i++) {
            if (Files.exists(socketPath)) break;
            if (!mpvProcess.isAlive()) {
                throw new IOException("mpv process died before creating socket (exit code: "
                        + mpvProcess.exitValue() + ")");
            }
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        if (!Files.exists(socketPath)) {
            mpvProcess.destroyForcibly();
            throw new IOException("mpv did not create IPC socket at " + socketPath);
        }

        System.err.println("[mpv] Socket created, connecting...");

        // Connect
        socket = SocketChannel.open(UnixDomainSocketAddress.of(socketPath));
        writer = new BufferedWriter(new OutputStreamWriter(
                Channels.newOutputStream(socket), StandardCharsets.UTF_8));
        reader = new BufferedReader(new InputStreamReader(
                Channels.newInputStream(socket), StandardCharsets.UTF_8));

        // Observe properties for live updates
        observeProperty(1, "time-pos");
        observeProperty(2, "duration");
        observeProperty(3, "pause");
        observeProperty(4, "volume");
        observeProperty(6, "eof-reached");
        observeProperty(7, "metadata");
        observeProperty(8, "path");

        // Start the reader loop
        executor.submit(this::readLoop);

        System.err.println("[mpv] Player ready");
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /** True while we're programmatically changing tracks — suppresses
     *  spurious end-file → onNextTrack callbacks from the old track. */
    private volatile boolean changingTrack;

    /** Load a file and start playback. */
    public void playMedia(String filePath) {
        System.err.println("[mpv] Loading: " + filePath);
        changingTrack = true;  // suppress end-file → next-track callback
        readyFired = false;
        pendingSeekTarget = -1;
        eof = false;
        duration = 0;
        position = 0;
        sourceUri = new File(filePath).toURI().toString();
        sendCommand("loadfile", jsonString(filePath), "\"replace\"");
    }

    /** Resume playback. */
    public void play() {
        paused = false;
        sendCommand("set_property", jsonString("pause"), "false");
    }

    /** Pause playback. */
    public void pause() {
        paused = true;
        sendCommand("set_property", jsonString("pause"), "true");
    }

    /** Stop playback and unload the current file. */
    public void stop() {
        changingTrack = true;
        sendCommand("stop");
        eof = true;
        position = 0;
        duration = 0;
    }

    /** Seek to an absolute position in seconds. */
    public void seek(double seconds) {
        pendingSeekTarget = Math.max(0, seconds);
        sendCommand("seek", String.valueOf(pendingSeekTarget), jsonString("absolute"));
    }

    /** Set volume (0.0 – 1.0). mpv uses 0–100 internally. */
    public void setVolume(double v) {
        volume = Math.min(1.0, Math.max(0.0, v));
        sendCommand("set_property", jsonString("volume"), String.valueOf(volume * 100));
    }

    /** Get cached volume. */
    public double getVolume() {
        return volume;
    }

    /** Get cached playback position in seconds. */
    public double getPosition() {
        return position;
    }

    /** Get cached track duration in seconds. */
    public double getDuration() {
        return duration;
    }

    /** Whether playback is currently active (not paused/eof). */
    public boolean isPlaying() {
        return !paused && !eof && duration > 0;
    }

    /** Set playback speed/rate. */
    public void setRate(double rate) {
        speed = rate;
        sendCommand("set_property", jsonString("speed"), String.valueOf(rate));
    }

    /** Get cached playback speed. */
    public double getRate() {
        return speed;
    }

    /** Get track metadata (title, artist, album, etc.). */
    public Map<String, String> getMetadata() {
        return metadata;
    }

    /** Get the URI of the currently loaded media. */
    public String getSourceUri() {
        return sourceUri;
    }

    // ── IPC internals ───────────────────────────────────────────────────────

    /** Send a raw JSON line to mpv. */
    private synchronized void sendRaw(String json) {
        if (writer == null) return;
        try {
            writer.write(json);
            writer.flush();
        } catch (IOException e) {
            System.err.println("[mpv] write error: " + e.getMessage());
        }
    }

    /**
     * Send a JSON command. Each arg is a raw JSON value fragment:
     * use {@link #jsonString} for strings, bare numbers for numerics,
     * {@code "true"}/{""false"} for booleans.
     */
    private void sendCommand(String cmd, String... jsonArgs) {
        var sb = new StringBuilder();
        sb.append("{\"command\":[\"").append(cmd).append('"');
        for (var a : jsonArgs) {
            sb.append(',').append(a);
        }
        sb.append("]}\n");
        sendRaw(sb.toString());
    }

    /** Wrap a string in JSON quotes with proper escaping. */
    private static String jsonString(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** Register a property observer. */
    private void observeProperty(int id, String name) {
        sendRaw(String.format("{\"command\":[\"observe_property\",%d,\"%s\"]}\n", id, name));
    }

    // ── Reader loop (runs on dedicated daemon thread) ───────────────────────

    private void readLoop() {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                System.err.println("[mpv] <<< " + line);
                processMpvLine(line);
            }
            System.err.println("[mpv] Connection closed (mpv exited)");
        } catch (IOException e) {
            if (!Thread.currentThread().isInterrupted()) {
                System.err.println("[mpv] read error: " + e.getMessage());
            }
        }
    }

    /** Parse a JSON line from mpv and update cached state. */
    private void processMpvLine(String line) {
        // mpv's JSON encoder includes spaces after colons.
        // Check for "event" key presence, then the event type.
        if (line.contains("\"event\"")) {
            if (line.contains("property-change")) {
                handlePropertyChange(line);
            } else if (line.contains("end-file")) {
                handleEndFile();
            }
        }
    }

    /** Extract a JSON string value for a given key. */
    private static String extractString(String json, String key) {
        int i = json.indexOf('"' + key + '"');
        if (i < 0) return null;
        i = json.indexOf(':', i);
        if (i < 0) return null;
        i++; // skip colon
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length()) return null;

        if (json.charAt(i) == '"') {
            int start = i + 1;
            int end = json.indexOf('"', start);
            if (end < 0) return null;
            while (end > start && json.charAt(end - 1) == '\\') {
                end = json.indexOf('"', end + 1);
                if (end < 0) return null;
            }
            return json.substring(start, end);
        } else if (json.charAt(i) == 'n') {
            return null; // null
        }
        // For numbers, booleans, objects — just return the token
        int end = i;
        while (end < json.length() && !Character.isWhitespace(json.charAt(end))
                && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return json.substring(i, end);
    }

    private void handlePropertyChange(String line) {
        String propId = extractString(line, "id");
        if (propId == null) return;

        switch (propId) {
            case "1" -> { // time-pos
                String val = extractString(line, "data");
                if (val != null) {
                    try {
                        double newPos = Double.parseDouble(val);
                        if (pendingSeekTarget >= 0) {
                            if (Math.abs(newPos - pendingSeekTarget) < SEEK_THRESHOLD
                                    || Math.abs(newPos - position) < SEEK_THRESHOLD) {
                                pendingSeekTarget = -1;
                            }
                            position = newPos;
                        } else {
                            position = newPos;
                        }
                        if (duration > 0 && newPos >= 0) {
                            notifyReady();
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
            case "2" -> { // duration
                String val = extractString(line, "data");
                if (val != null) {
                    try {
                        duration = Double.parseDouble(val);
                        System.err.println("[mpv] duration = " + duration + "s");
                        if (duration > 0 && position >= 0) {
                            notifyReady();
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
            case "3" -> { // pause
                String val = extractString(line, "data");
                paused = "true".equals(val);
            }
            case "4" -> { // volume
                String val = extractString(line, "data");
                if (val != null) {
                    try {
                        volume = Double.parseDouble(val) / 100.0;
                    } catch (NumberFormatException ignored) {}
                }
            }
            case "5" -> { // speed
                String val = extractString(line, "data");
                if (val != null) {
                    try {
                        speed = Double.parseDouble(val);
                    } catch (NumberFormatException ignored) {}
                }
            }
            case "6" -> { // eof-reached
                String val = extractString(line, "data");
                if ("true".equals(val) && !eof) {
                    System.err.println("[mpv] EOF reached");
                    handleEndFile();
                }
            }
            case "7" -> { // metadata
                parseMetadata(line);
            }
            case "8" -> { // path
                String val = extractString(line, "data");
                if (val != null && !val.isEmpty()) {
                    sourceUri = new File(val).toURI().toString();
                }
            }
        }
    }

    private void parseMetadata(String line) {
        var map = new HashMap<String, String>();
        int dataIdx = line.indexOf("\"data\":");
        if (dataIdx < 0) return;
        int objStart = line.indexOf('{', dataIdx);
        if (objStart < 0) return;

        int pos = objStart + 1;
        while (pos < line.length()) {
            char c = line.charAt(pos);
            if (c == '}') break;
            if (c == '"') {
                int keyEnd = line.indexOf('"', pos + 1);
                if (keyEnd < 0) break;
                String key = line.substring(pos + 1, keyEnd);
                int colon = line.indexOf(':', keyEnd);
                if (colon < 0) break;
                int valStart = colon + 1;
                while (valStart < line.length() && Character.isWhitespace(line.charAt(valStart))) valStart++;
                if (valStart < line.length() && line.charAt(valStart) == '"') {
                    int valEnd = line.indexOf('"', valStart + 1);
                    if (valEnd < 0) break;
                    map.put(key, line.substring(valStart + 1, valEnd));
                    pos = valEnd + 1;
                } else {
                    int skip = valStart;
                    while (skip < line.length() && line.charAt(skip) != ','
                            && line.charAt(skip) != '}') skip++;
                    pos = skip;
                }
            } else {
                pos++;
            }
        }
        this.metadata = Collections.unmodifiableMap(map);
    }

    private volatile boolean readyFired;

    private void notifyReady() {
        if (!readyFired && duration > 0) {
            readyFired = true;
            changingTrack = false;  // new track confirmed playing, clear the flag
            System.err.println("[mpv] Firing onMediaReady, duration=" + duration);
            javafx.application.Platform.runLater(() -> callback.onMediaReady(duration));
        }
    }

    private void handleEndFile() {
        eof = true;
        readyFired = false;
        // If we programmed a track change ourselves (loadfile/stop),
        // the end-file is from the OLD track being evicted — ignore it.
        if (changingTrack) {
            return;
        }
        javafx.application.Platform.runLater(() -> {
            if (callback.getRepeatState() != RepeatState.REPEAT_ONE) {
                callback.onNextTrack();
            } else {
                seek(0);
                eof = false;
            }
        });
    }

    @Override
    public void close() {
        executor.shutdownNow();
        try {
            if (mpvProcess != null && mpvProcess.isAlive()) {
                sendRaw("{\"command\":[\"quit\"]}\n");
                mpvProcess.waitFor(2, TimeUnit.SECONDS);
                mpvProcess.destroyForcibly();
            }
        } catch (InterruptedException e) {
            if (mpvProcess != null) mpvProcess.destroyForcibly();
        }
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        try { Files.deleteIfExists(socketPath); } catch (IOException ignored) {}
    }
}
