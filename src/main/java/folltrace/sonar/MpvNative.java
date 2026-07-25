package folltrace.sonar;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

/**
 * JNA bindings for libmpv. Maps the subset of the mpv client API that Sonar uses.
 *
 * <p>Struct layouts (x86_64 Linux ABI):
 * <pre>
 *   mpv_event          = { event_id(i32,0), error(i32,4), reply_userdata(u64,8), data(ptr,16) }  // 24 B
 *   mpv_event_property = { name(ptr,0), format(i32,8), data(ptr,16) }                            // 24 B
 *   mpv_event_end_file = { reason(i32,0), error(i32,4) }                                         //  8 B
 *   mpv_node           = { u(union,8B,0), format(i32,8) }                                  // 16 B
 *   mpv_node_list      = { num(i32,0), [pad 4], values(ptr,8), keys(ptr,16) }                    // 24 B
 * </pre>
 */
@SuppressWarnings("unused")
public interface MpvNative extends Library {

    MpvNative INSTANCE = Native.load("mpv", MpvNative.class);

    // ── Formats (mpv_format) ────────────────────────────────────────────────
    int MPV_FORMAT_NONE   = 0;
    int MPV_FORMAT_STRING = 1;
    int MPV_FORMAT_FLAG   = 3;
    int MPV_FORMAT_INT64  = 4;
    int MPV_FORMAT_DOUBLE = 5;
    int MPV_FORMAT_NODE   = 6;
    int MPV_FORMAT_NODE_MAP = 8;

    // ── Event IDs (mpv_event_id) ────────────────────────────────────────────
    int MPV_EVENT_NONE            = 0;
    int MPV_EVENT_SHUTDOWN        = 1;
    int MPV_EVENT_PROPERTY_CHANGE = 22;
    int MPV_EVENT_END_FILE        = 7;
    int MPV_EVENT_FILE_LOADED     = 8;

    // ── End-file reasons (mpv_end_file_reason) ──────────────────────────────
    int MPV_END_FILE_REASON_EOF   = 0;
    int MPV_END_FILE_REASON_STOP  = 2;
    int MPV_END_FILE_REASON_QUIT  = 3;
    int MPV_END_FILE_REASON_ERROR = 4;

    // ── Event struct field offsets (bytes) ──────────────────────────────────
    /** mpv_event.event_id */
    int OFF_EVENT_ID            = 0;
    /** mpv_event.error */
    int OFF_EVENT_ERROR         = 4;
    /** mpv_event.reply_userdata */
    int OFF_EVENT_USERDATA      = 8;
    /** mpv_event.data (void*) */
    int OFF_EVENT_DATA          = 16;

    /** mpv_event_property.name (const char*) */
    int OFF_PROP_NAME           = 0;
    /** mpv_event_property.format (mpv_format) */
    int OFF_PROP_FORMAT         = 8;
    /** mpv_event_property.data (void*) */
    int OFF_PROP_DATA           = 16;

    /** mpv_event_end_file.reason */
    int OFF_END_REASON          = 0;
    /** mpv_event_end_file.error */
    int OFF_END_ERROR           = 4;

    /** mpv_node.format (at offset 8 — after the 8-byte union) */
    int OFF_NODE_FORMAT         = 8;
    /** mpv_node union (flag/int64/double/string/list — at offset 0) */
    int OFF_NODE_UNION          = 0;

    /** mpv_node_list.num */
    int OFF_LIST_NUM            = 0;
    /** mpv_node_list.values (mpv_node*) */
    int OFF_LIST_VALUES         = 8;
    /** mpv_node_list.keys (char**) */
    int OFF_LIST_KEYS           = 16;

    // ── API ─────────────────────────────────────────────────────────────────

    /** Create an uninitialized mpv handle. */
    Pointer mpv_create();

    /** Initialize the handle. Must be called after setting options. */
    int mpv_initialize(Pointer handle);

    /** Set an option before initialization. */
    int mpv_set_option_string(Pointer handle, String name, String data);

    /**
     * Send a command string (e.g. {@code "loadfile /path replace"}).
     * Arguments are parsed with shell-like quoting rules.
     */
    int mpv_command_string(Pointer handle, String args);

    /** Set a property from a string value (convenience). */
    int mpv_set_property_string(Pointer handle, String name, String data);

    /** Observe a property for change notifications. */
    int mpv_observe_property(Pointer handle, long replyUserdata, String name, int format);

    /**
     * Block until the next event or timeout.
     * @param timeout seconds; negative = infinite
     * @return pointer into mpv's internal buffer; valid until the next call to this function
     */
    Pointer mpv_wait_event(Pointer handle, double timeout);

    /**
     * Read a property value. For {@code MPV_FORMAT_STRING}, the result is
     * written into {@code *data} and must be freed with {@link #mpv_free}.
     */
    int mpv_get_property(Pointer handle, String name, int format, PointerByReference data);

    /** Free memory allocated by mpv. */
    void mpv_free(Pointer data);

    /** Gracefully shut down and destroy the handle. */
    void mpv_terminate_destroy(Pointer handle);
}
