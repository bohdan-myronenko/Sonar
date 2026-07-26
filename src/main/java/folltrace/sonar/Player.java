package folltrace.sonar;

import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Audio player backed by libmpv via JNA.
 * mpv runs in-process so it appears as "Sonar" in the system mixer.
 */
public class Player implements AutoCloseable {

    private final Pointer handle;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "mpv-event-loop");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(true);

    // Cached state updated by the event thread
    private volatile double position;       // seconds
    private volatile double duration;       // seconds
    private volatile double volume = 1.0;   // 0.0-1.0
    private volatile double speed = 1.0;    // playback rate
    private volatile boolean paused;
    private volatile boolean eof;
    private volatile Map<String, String> metadata = Map.of();
    private volatile String sourceUri = "";

    private static final double SEEK_THRESHOLD = 0.1;
    private double pendingSeekTarget = -1;

    private final PlayerCallback callback;
    private volatile boolean changingTrack;
    private volatile boolean readyFired;

    /** Observer IDs for {@link #observeProperty}. */
    private static final long OBS_TIME_POS    = 1;
    private static final long OBS_DURATION    = 2;
    private static final long OBS_PAUSE       = 3;
    private static final long OBS_VOLUME      = 4;
    private static final long OBS_EOF_REACHED = 6;
    private static final long OBS_METADATA    = 7;
    private static final long OBS_PATH        = 8;

    public Player(PlayerCallback callback) throws IOException {
        this.callback = callback;

        // mpv requires the C numeric locale (otherwise mpv_create returns NULL)
        CLibrary.INSTANCE.setlocale(CLibrary.LC_NUMERIC, "C");

        var m = MpvNative.INSTANCE;
        handle = m.mpv_create();
        if (handle == null) {
            throw new IOException("mpv_create() returned NULL. Is libmpv installed?");
        }

        // Configure mpv before initialization
        m.mpv_set_option_string(handle, "video", "no");
        m.mpv_set_option_string(handle, "idle", "yes");
        m.mpv_set_option_string(handle, "audio-display", "no");
        m.mpv_set_option_string(handle, "volume", "100");
        m.mpv_set_option_string(handle, "volume-max", "100");
        if (com.sun.jna.Platform.isWindows()) {
            m.mpv_set_option_string(handle, "terminal", "yes");
            m.mpv_set_option_string(handle, "msg-level", "all=status");
        } else {
            m.mpv_set_option_string(handle, "terminal", "no");
        }

        int rc = m.mpv_initialize(handle);
        if (rc < 0) {
            m.mpv_terminate_destroy(handle);
            throw new IOException("mpv_initialize() failed: " + rc);
        }

        // Observe properties for live updates
        observeProperty(OBS_TIME_POS,    "time-pos",    MpvNative.MPV_FORMAT_DOUBLE);
        observeProperty(OBS_DURATION,    "duration",    MpvNative.MPV_FORMAT_DOUBLE);
        observeProperty(OBS_PAUSE,       "pause",       MpvNative.MPV_FORMAT_FLAG);
        observeProperty(OBS_VOLUME,      "volume",      MpvNative.MPV_FORMAT_DOUBLE);
        observeProperty(OBS_EOF_REACHED, "eof-reached", MpvNative.MPV_FORMAT_FLAG);
        observeProperty(OBS_METADATA,    "metadata",    MpvNative.MPV_FORMAT_NODE);
        observeProperty(OBS_PATH,        "path",        MpvNative.MPV_FORMAT_STRING);

        executor.submit(this::eventLoop);

        System.err.println("[mpv] Player ready (libmpv)");
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /** Load a file and start playback. */
    public void playMedia(String filePath) {
        System.err.println("[mpv] Loading: " + filePath);
        changingTrack = true;
        readyFired = false;
        pendingSeekTarget = -1;
        eof = false;
        duration = 0;
        position = 0;
        sourceUri = new File(filePath).toURI().toString();
        // Escape special characters for mpv_command_string shell-like parsing
        sendCommand("loadfile", quoteArg(filePath), "replace");
    }

    /** Resume playback. */
    public void play() {
        paused = false;
        MpvNative.INSTANCE.mpv_set_property_string(handle, "pause", "no");
    }

    /** Pause playback. */
    public void pause() {
        paused = true;
        MpvNative.INSTANCE.mpv_set_property_string(handle, "pause", "yes");
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
        sendCommand("seek", String.valueOf(pendingSeekTarget), "absolute");
    }

    /** Set volume (0.0 to 1.0). mpv uses 0-100 internally. */
    public void setVolume(double v) {
        volume = Math.min(1.0, Math.max(0.0, v));
        MpvNative.INSTANCE.mpv_set_property_string(handle, "volume",
                String.valueOf((int) Math.round(volume * 100)));
    }

    public double getVolume()   { return volume; }
    public double getPosition() { return position; }
    public double getDuration() { return duration; }

    public boolean isPlaying() {
        return !paused && !eof && duration > 0;
    }

    public void setRate(double rate) {
        speed = rate;
        MpvNative.INSTANCE.mpv_set_property_string(handle, "speed", String.valueOf(rate));
    }

    public double getRate() { return speed; }
    public Map<String, String> getMetadata() { return metadata; }
    public String getSourceUri() { return sourceUri; }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Send a command via mpv_command_string. */
    private void sendCommand(String cmd, String... args) {
        var sb = new StringBuilder(cmd);
        for (var a : args) {
            sb.append(' ').append(a);
        }
        int rc = MpvNative.INSTANCE.mpv_command_string(handle, sb.toString());
        if (rc < 0) {
            System.err.println("[mpv] command error " + rc + ": " + sb);
        }
    }

    /** Shell-quote a path for mpv_command_string. */
    private static String quoteArg(String s) {
        // Wrap in double quotes if the path contains spaces or special chars
        if (s.contains(" ") || s.contains("\"") || s.contains("\\") || s.contains("'")) {
            return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        }
        return s;
    }

    /** Register a property observer. */
    private void observeProperty(long id, String name, int format) {
        MpvNative.INSTANCE.mpv_observe_property(handle, id, name, format);
    }

    // ── Event loop (runs on dedicated daemon thread) ────────────────────────

    private void eventLoop() {
        var m = MpvNative.INSTANCE;
        while (running.get()) {
            Pointer evPtr = m.mpv_wait_event(handle, 0.5);
            if (evPtr == null) continue;

            int eventId = evPtr.getInt(MpvNative.OFF_EVENT_ID);
            switch (eventId) {
                case MpvNative.MPV_EVENT_NONE:
                    break;

                case MpvNative.MPV_EVENT_SHUTDOWN:
                    System.err.println("[mpv] Shutdown event received");
                    running.set(false);
                    break;

                case MpvNative.MPV_EVENT_PROPERTY_CHANGE:
                    handlePropertyChange(evPtr);
                    break;

                case MpvNative.MPV_EVENT_END_FILE:
                    handleEndFile(evPtr);
                    break;

                case MpvNative.MPV_EVENT_FILE_LOADED:
                    System.err.println("[mpv] File loaded event");
                    break;

                default:
                    // log_message, seek, playback-restart, etc. are ignored
                    break;
            }
        }
        System.err.println("[mpv] Event loop exited");
    }

    // ── Property change dispatch ────────────────────────────────────────────

    private void handlePropertyChange(Pointer evPtr) {
        long userdata = evPtr.getLong(MpvNative.OFF_EVENT_USERDATA);
        Pointer propPtr = evPtr.getPointer(MpvNative.OFF_EVENT_DATA);
        if (propPtr == null) return;

        int format = propPtr.getInt(MpvNative.OFF_PROP_FORMAT);

        if (userdata == OBS_TIME_POS && format == MpvNative.MPV_FORMAT_DOUBLE) {
            double newPos = propPtr.getPointer(MpvNative.OFF_PROP_DATA).getDouble(0);
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
        } else if (userdata == OBS_DURATION && format == MpvNative.MPV_FORMAT_DOUBLE) {
            double d = propPtr.getPointer(MpvNative.OFF_PROP_DATA).getDouble(0);
            if (d > 0 && d != duration) {
                duration = d;
                System.err.println("[mpv] duration = " + duration + "s");
                notifyReady();
            }
        } else if (userdata == OBS_PAUSE && format == MpvNative.MPV_FORMAT_FLAG) {
            int flag = propPtr.getPointer(MpvNative.OFF_PROP_DATA).getInt(0);
            paused = (flag != 0);
        } else if (userdata == OBS_VOLUME && format == MpvNative.MPV_FORMAT_DOUBLE) {
            double v = propPtr.getPointer(MpvNative.OFF_PROP_DATA).getDouble(0);
            volume = v / 100.0;
        } else if (userdata == OBS_EOF_REACHED && format == MpvNative.MPV_FORMAT_FLAG) {
            int flag = propPtr.getPointer(MpvNative.OFF_PROP_DATA).getInt(0);
            if (flag != 0 && !eof) {
                System.err.println("[mpv] EOF reached");
                handleEof();
            }
        } else if (userdata == OBS_METADATA && format == MpvNative.MPV_FORMAT_NODE) {
            parseMetadataNode(propPtr.getPointer(MpvNative.OFF_PROP_DATA));
        } else if (userdata == OBS_PATH && format == MpvNative.MPV_FORMAT_STRING) {
            // For MPV_FORMAT_STRING, prop.data is char**; dereference to get the actual char*
            Pointer dataField = propPtr.getPointer(MpvNative.OFF_PROP_DATA);
            if (dataField != null) {
                Pointer strPtr = dataField.getPointer(0);
                if (strPtr != null) {
                    String val = strPtr.getString(0);
                    if (val != null && !val.isEmpty()) {
                        sourceUri = new File(val).toURI().toString();
                    }
                }
            }
        }
    }

    // ── Metadata parsing from mpv_node ──────────────────────────────────────

    /**
     * Parse an {@code mpv_node} of type {@code MPV_FORMAT_NODE_MAP}
     * containing string key/value pairs and store them in {@link #metadata}.
     * <p>
     * Node layout: {@code { u(8B union, off 0), format(i32, off 8) }}, 16 bytes total.
     * Node-list layout: {@code { num(i32,0) [pad4] values(ptr,8) keys(ptr,16) }}, 24 bytes total.
     */
    private void parseMetadataNode(Pointer nodePtr) {
        if (nodePtr == null) return;

        int nodeFmt = nodePtr.getInt(MpvNative.OFF_NODE_FORMAT);
        if (nodeFmt != MpvNative.MPV_FORMAT_NODE_MAP) return;

        Pointer listPtr = nodePtr.getPointer(MpvNative.OFF_NODE_UNION);
        if (listPtr == null) return;

        int num = listPtr.getInt(MpvNative.OFF_LIST_NUM);
        Pointer valuesPtr = listPtr.getPointer(MpvNative.OFF_LIST_VALUES);
        Pointer keysPtr   = listPtr.getPointer(MpvNative.OFF_LIST_KEYS);
        if (num <= 0 || valuesPtr == null || keysPtr == null) return;

        var map = new HashMap<String, String>(num);
        for (int i = 0; i < num; i++) {
            // keys[i]: pointer to C string
            Pointer keyPtr = keysPtr.getPointer((long) i * Native.POINTER_SIZE);
            if (keyPtr == null) continue;
            String key = keyPtr.getString(0);
            if (key == null) continue;

            // values[i]: pointer to mpv_node (16 bytes each)
            Pointer valNodePtr = valuesPtr.share((long) i * 16);
            int valFmt = valNodePtr.getInt(MpvNative.OFF_NODE_FORMAT);
            if (valFmt == MpvNative.MPV_FORMAT_STRING) {
                Pointer strPtr = valNodePtr.getPointer(MpvNative.OFF_NODE_UNION);
                if (strPtr != null) {
                    String val = strPtr.getString(0);
                    if (val != null) {
                        map.put(key, val);
                    }
                }
            }
        }
        this.metadata = Collections.unmodifiableMap(map);
    }

    // ── Track lifecycle callbacks ───────────────────────────────────────────

    private void notifyReady() {
        if (!readyFired && duration > 0) {
            readyFired = true;
            changingTrack = false;
            System.err.println("[mpv] Firing onMediaReady, duration=" + duration);
            javafx.application.Platform.runLater(() -> callback.onMediaReady(duration));
        }
    }

    /** Called when mpv sends the end-file event. */
    private void handleEndFile(Pointer evPtr) {
        Pointer dataPtr = evPtr.getPointer(MpvNative.OFF_EVENT_DATA);
        if (dataPtr == null) return;
        int reason = dataPtr.getInt(MpvNative.OFF_END_REASON);
        int error  = dataPtr.getInt(MpvNative.OFF_END_ERROR);
        System.err.println("[mpv] end-file event reason=" + reason + " error=" + error);

        // EOF and ERROR are the cases that mean "track actually ended"
        if (reason != MpvNative.MPV_END_FILE_REASON_EOF
                && reason != MpvNative.MPV_END_FILE_REASON_ERROR) {
            return; // stop / quit / redirect: handled elsewhere or irrelevant
        }
        handleEof();
    }

    /** Common end-of-track handling (used by both eof-reached property and end-file event). */
    private void handleEof() {
        eof = true;
        readyFired = false;
        System.err.println("[mpv] handleEof changingTrack=" + changingTrack);
        if (changingTrack) return;

        javafx.application.Platform.runLater(() -> {
            if (callback.getRepeatState() != RepeatState.REPEAT_ONE) {
                callback.onNextTrack();
            } else {
                seek(0);
                eof = false;
            }
        });
    }

    // ── Cleanup ─────────────────────────────────────────────────────────────

    @Override
    public void close() {
        System.err.println("[mpv] Shutting down...");
        running.set(false);
        MpvNative.INSTANCE.mpv_terminate_destroy(handle);
        executor.shutdownNow();
        try { executor.awaitTermination(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        System.err.println("[mpv] Shutdown complete");
    }
}
