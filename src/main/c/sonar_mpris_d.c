/*
 * sonar_mpris_d — MPRIS v2 D-Bus daemon for Sonar music player.
 *
 * This lightweight C daemon holds a D-Bus session connection in a process
 * completely separate from the JVM.  JavaFX MediaPlayer's GStreamer backend
 * on Linux is known to close arbitrary file descriptors (including the D-Bus
 * socket), which breaks any in-process D-Bus library.  Running the D-Bus
 * connection out-of-process avoids this entirely.
 *
 * IPC between Java and this daemon uses a Unix-domain socket pair created
 * by the Java side and passed to this process as its only command-line argument.
 *
 * Build:  gcc -O2 -Wall -o sonar_mpris_d sonar_mpris_d.c $(pkg-config --cflags --libs dbus-1)
 */

#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/select.h>
#include <errno.h>
#include <stdarg.h>
#include <fcntl.h>
#include <time.h>
#include <dbus/dbus.h>

/* ──────────────────────────────────────────────
 *  MPRIS constants
 * ────────────────────────────────────────────── */
#define BUS_NAME        "org.mpris.MediaPlayer2.sonar"
#define OBJ_PATH        "/org/mpris/MediaPlayer2"
#define ROOT_IFACE      "org.mpris.MediaPlayer2"
#define PLAYER_IFACE    "org.mpris.MediaPlayer2.Player"
#define PROPS_IFACE     "org.freedesktop.DBus.Properties"

/* ──────────────────────────────────────────────
 *  Global state
 * ────────────────────────────────────────────── */
static DBusConnection *conn  = NULL;
static int             sock_fd = -1;   /* Unix socket to Java */

typedef struct {
    char *playback_status;   /* "Playing" | "Paused" | "Stopped" */
    char *title;
    char *artist;
    char *album;
    char *art_url;
    long long duration_us;   /* microseconds, mpris:length */
    long long position_us;   /* microseconds, current Position */
    double  volume;
    double  rate;
    char   *loop_status;     /* "None" | "Track" | "Playlist" */
    int     shuffle;
    int     can_go_next;
    int     can_go_previous;
    int     can_play;
    int     can_pause;
    int     can_seek;
    int     can_control;
} MprisState;

static MprisState state;

/* Monotonic timestamp (ms) of the last position update from Java,
 * used to extrapolate Position between updates while playing. */
static long long last_pos_update_ms = 0;

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* Current playback position, extrapolated while playing. */
static long long current_position_us(void) {
    if (last_pos_update_ms > 0 &&
        strcmp(state.playback_status, "Playing") == 0) {
        long long elapsed_ms = now_ms() - last_pos_update_ms;
        return state.position_us +
               (long long)((double)elapsed_ms * 1000.0 * state.rate);
    }
    return state.position_us;
}

/* ──────────────────────────────────────────────
 *  Forward declarations
 * ────────────────────────────────────────────── */
static void     init_state(void);
static void     free_state(void);
static int      setup_dbus(void);
static void     handle_dbus_message(DBusMessage *msg);
static void     send_reply(DBusMessage *msg);
static void     send_reply_variant(DBusMessage *msg, int type, const void *val);
static int      parse_signal(char *payload);
static int      read_from_java_once(void);
static void     send_metadata_reply(DBusMessage *msg);
static void     cleanup(void);

/* ──────────────────────────────────────────────
 *  State helpers
 * ────────────────────────────────────────────── */
static void init_state(void) {
    memset(&state, 0, sizeof(state));
    state.playback_status = strdup("Stopped");
    state.title           = strdup("");
    state.artist          = strdup("");
    state.album           = strdup("");
    state.art_url         = strdup("");
    state.duration_us     = 0;
    state.position_us     = 0;
    state.volume          = 1.0;
    state.rate            = 1.0;
    state.loop_status     = strdup("None");
    state.shuffle         = 0;
    state.can_go_next     = 1;
    state.can_go_previous = 1;
    state.can_play        = 1;
    state.can_pause       = 1;
    state.can_seek        = 1;
    state.can_control     = 1;
}

static void free_state(void) {
    free(state.playback_status);
    free(state.title);
    free(state.artist);
    free(state.album);
    free(state.art_url);
    free(state.loop_status);
}

/* ──────────────────────────────────────────────
 *  Send a string message to Java via the socket
 * ────────────────────────────────────────────── */
static void send_to_java(const char *msg) {
    if (sock_fd < 0) return;
    ssize_t len = (ssize_t)strlen(msg);
    ssize_t total = 0;
    while (total < len) {
        ssize_t written = send(sock_fd, msg + total, len - total, MSG_NOSIGNAL);
        if (written < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                /* Socket buffer full — wait for writability, then retry */
                fd_set wfds;
                FD_ZERO(&wfds);
                FD_SET(sock_fd, &wfds);
                struct timeval tv = { .tv_sec = 1, .tv_usec = 0 };
                if (select(sock_fd + 1, NULL, &wfds, NULL, &tv) > 0) continue;
            }
            fprintf(stderr, "[sonar_mpris_d] send() failed: %s\n", strerror(errno));
            break;
        }
        total += written;
    }
}

/* ──────────────────────────────────────────────
 *  D-Bus method call dispatch
 * ────────────────────────────────────────────── */
static void handle_method_call(DBusMessage *msg, const char *iface,
                                const char *method) {
    if (!strcmp(iface, ROOT_IFACE)) {
        if (!strcmp(method, "Raise")) {
            send_to_java("CALL Raise\n");
            send_reply(msg);
        } else if (!strcmp(method, "Quit")) {
            send_reply(msg);
        } else {
            send_reply(msg);
        }
        return;
    }

    if (!strcmp(iface, PLAYER_IFACE)) {
        if (!strcmp(method, "PlayPause")) {
            send_to_java("CALL PlayPause\n");
        } else if (!strcmp(method, "Play")) {
            send_to_java("CALL Play\n");
        } else if (!strcmp(method, "Pause")) {
            send_to_java("CALL Pause\n");
        } else if (!strcmp(method, "Stop")) {
            send_to_java("CALL Stop\n");
        } else if (!strcmp(method, "Next")) {
            send_to_java("CALL Next\n");
        } else if (!strcmp(method, "Previous")) {
            send_to_java("CALL Previous\n");
        } else if (!strcmp(method, "Seek")) {
            dbus_int64_t offset = 0;
            DBusMessageIter iter;
            dbus_message_iter_init(msg, &iter);
            if (dbus_message_iter_get_arg_type(&iter) == DBUS_TYPE_INT64)
                dbus_message_iter_get_basic(&iter, &offset);
            char buf[128];
            snprintf(buf, sizeof(buf), "CALL Seek %lld\n",
                     (long long)offset);
            send_to_java(buf);
        } else if (!strcmp(method, "SetPosition")) {
            DBusMessageIter iter;
            dbus_message_iter_init(msg, &iter);
            const char *track_id = NULL;
            dbus_int64_t pos = 0;
            if (dbus_message_iter_get_arg_type(&iter) == DBUS_TYPE_OBJECT_PATH)
                dbus_message_iter_get_basic(&iter, &track_id);
            dbus_message_iter_next(&iter);
            if (dbus_message_iter_get_arg_type(&iter) == DBUS_TYPE_INT64)
                dbus_message_iter_get_basic(&iter, &pos);
            char buf[512];
            snprintf(buf, sizeof(buf), "CALL SetPosition %s %lld\n",
                     track_id ? track_id : "", (long long)pos);
            send_to_java(buf);
        } else if (!strcmp(method, "OpenUri")) {
            DBusMessageIter iter;
            dbus_message_iter_init(msg, &iter);
            const char *uri = "";
            if (dbus_message_iter_get_arg_type(&iter) == DBUS_TYPE_STRING)
                dbus_message_iter_get_basic(&iter, &uri);
            char buf[2048];
            snprintf(buf, sizeof(buf), "CALL OpenUri %s\n", uri);
            send_to_java(buf);
        }
        send_reply(msg);
        return;
    }

    send_reply(msg);
}

/* ──────────────────────────────────────────────
 *  Properties.Get
 * ────────────────────────────────────────────── */
static void handle_properties_get(DBusMessage *msg, const char *iface,
                                   const char *prop) {
    if (!strcmp(iface, ROOT_IFACE)) {
        if (!strcmp(prop, "CanQuit")) {
            int v = 1; send_reply_variant(msg, DBUS_TYPE_BOOLEAN, &v);
        } else if (!strcmp(prop, "CanRaise")) {
            int v = 1; send_reply_variant(msg, DBUS_TYPE_BOOLEAN, &v);
        } else if (!strcmp(prop, "HasTrackList")) {
            int v = 0; send_reply_variant(msg, DBUS_TYPE_BOOLEAN, &v);
        } else if (!strcmp(prop, "Identity")) {
            const char *v = "Sonar";
            send_reply_variant(msg, DBUS_TYPE_STRING, &v);
        } else if (!strcmp(prop, "DesktopEntry")) {
            const char *v = "sonar";
            send_reply_variant(msg, DBUS_TYPE_STRING, &v);
        } else if (!strcmp(prop, "SupportedUriSchemes")) {
            DBusMessage *reply = dbus_message_new_method_return(msg);
            DBusMessageIter iter, sub, arr;
            dbus_message_iter_init_append(reply, &iter);
            dbus_message_iter_open_container(&iter, DBUS_TYPE_VARIANT,
                                              "as", &sub);
            dbus_message_iter_open_container(&sub, DBUS_TYPE_ARRAY,
                                              "s", &arr);
            const char *scheme = "file";
            dbus_message_iter_append_basic(&arr, DBUS_TYPE_STRING, &scheme);
            dbus_message_iter_close_container(&sub, &arr);
            dbus_message_iter_close_container(&iter, &sub);
            dbus_connection_send(conn, reply, NULL);
            dbus_message_unref(reply);
        } else if (!strcmp(prop, "SupportedMimeTypes")) {
            DBusMessage *reply = dbus_message_new_method_return(msg);
            DBusMessageIter iter, sub, arr;
            dbus_message_iter_init_append(reply, &iter);
            dbus_message_iter_open_container(&iter, DBUS_TYPE_VARIANT,
                                              "as", &sub);
            dbus_message_iter_open_container(&sub, DBUS_TYPE_ARRAY,
                                              "s", &arr);
            const char *mimes[] = {"audio/mpeg", "audio/x-wav",
                                   "audio/aac", "audio/x-m4a"};
            for (int i = 0; i < 4; i++)
                dbus_message_iter_append_basic(&arr, DBUS_TYPE_STRING,
                                                &mimes[i]);
            dbus_message_iter_close_container(&sub, &arr);
            dbus_message_iter_close_container(&iter, &sub);
            dbus_connection_send(conn, reply, NULL);
            dbus_message_unref(reply);
        }
        return;
    }

    if (!strcmp(iface, PLAYER_IFACE)) {
        if (!strcmp(prop, "PlaybackStatus")) {
            send_reply_variant(msg, DBUS_TYPE_STRING,
                               &state.playback_status);
        } else if (!strcmp(prop, "Rate")) {
            double v = state.rate;
            send_reply_variant(msg, DBUS_TYPE_DOUBLE, &v);
        } else if (!strcmp(prop, "MinimumRate")) {
            double v = 0.5;
            send_reply_variant(msg, DBUS_TYPE_DOUBLE, &v);
        } else if (!strcmp(prop, "MaximumRate")) {
            double v = 2.0;
            send_reply_variant(msg, DBUS_TYPE_DOUBLE, &v);
        } else if (!strcmp(prop, "Volume")) {
            double v = state.volume;
            send_reply_variant(msg, DBUS_TYPE_DOUBLE, &v);
        } else if (!strcmp(prop, "Position")) {
            dbus_int64_t v = current_position_us();
            send_reply_variant(msg, DBUS_TYPE_INT64, &v);
        } else if (!strcmp(prop, "Shuffle")) {
            send_reply_variant(msg, DBUS_TYPE_BOOLEAN, &state.shuffle);
        } else if (!strcmp(prop, "LoopStatus")) {
            send_reply_variant(msg, DBUS_TYPE_STRING,
                               &state.loop_status);
        } else if (!strcmp(prop, "CanGoNext")) {
            send_reply_variant(msg, DBUS_TYPE_BOOLEAN,
                               &state.can_go_next);
        } else if (!strcmp(prop, "CanGoPrevious")) {
            send_reply_variant(msg, DBUS_TYPE_BOOLEAN,
                               &state.can_go_previous);
        } else if (!strcmp(prop, "CanPlay")) {
            send_reply_variant(msg, DBUS_TYPE_BOOLEAN, &state.can_play);
        } else if (!strcmp(prop, "CanPause")) {
            send_reply_variant(msg, DBUS_TYPE_BOOLEAN, &state.can_pause);
        } else if (!strcmp(prop, "CanSeek")) {
            send_reply_variant(msg, DBUS_TYPE_BOOLEAN, &state.can_seek);
        } else if (!strcmp(prop, "CanControl")) {
            send_reply_variant(msg, DBUS_TYPE_BOOLEAN, &state.can_control);
        } else if (!strcmp(prop, "Metadata")) {
            send_metadata_reply(msg);
        } else {
            /* Unknown property — still MUST reply or callers time out */
            const char *v = "";
            send_reply_variant(msg, DBUS_TYPE_STRING, &v);
        }
        return;
    }

    /* Unknown property — return empty string */
    {
        const char *v = "";
        send_reply_variant(msg, DBUS_TYPE_STRING, &v);
    }
}

/* ──────────────────────────────────────────────
 *  Properties.GetAll
 * ────────────────────────────────────────────── */
/* Helper to append a {sv} entry for a boolean */
static void append_dict_bool(DBusMessageIter *dict, const char *key, int val) {
    DBusMessageIter entry, var;
    dbus_message_iter_open_container(dict, DBUS_TYPE_DICT_ENTRY,
                                      NULL, &entry);
    dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key);
    dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "b", &var);
    dbus_message_iter_append_basic(&var, DBUS_TYPE_BOOLEAN, &val);
    dbus_message_iter_close_container(&entry, &var);
    dbus_message_iter_close_container(dict, &entry);
}

/* Helper to append a {sv} entry for a string */
static void append_dict_str(DBusMessageIter *dict, const char *key,
                             const char *val) {
    DBusMessageIter entry, var;
    dbus_message_iter_open_container(dict, DBUS_TYPE_DICT_ENTRY,
                                      NULL, &entry);
    dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key);
    dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "s", &var);
    dbus_message_iter_append_basic(&var, DBUS_TYPE_STRING, &val);
    dbus_message_iter_close_container(&entry, &var);
    dbus_message_iter_close_container(dict, &entry);
}

/* Helper to append a {sv} entry for a double */
static void append_dict_double(DBusMessageIter *dict, const char *key,
                                double val) {
    DBusMessageIter entry, var;
    dbus_message_iter_open_container(dict, DBUS_TYPE_DICT_ENTRY,
                                      NULL, &entry);
    dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key);
    dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "d", &var);
    dbus_message_iter_append_basic(&var, DBUS_TYPE_DOUBLE, &val);
    dbus_message_iter_close_container(&entry, &var);
    dbus_message_iter_close_container(dict, &entry);
}

/* Helper to append a {sv} entry for an int64 */
static void append_dict_int64(DBusMessageIter *dict, const char *key,
                               dbus_int64_t val) {
    DBusMessageIter entry, var;
    dbus_message_iter_open_container(dict, DBUS_TYPE_DICT_ENTRY,
                                      NULL, &entry);
    dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key);
    dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "x", &var);
    dbus_message_iter_append_basic(&var, DBUS_TYPE_INT64, &val);
    dbus_message_iter_close_container(&entry, &var);
    dbus_message_iter_close_container(dict, &entry);
}

/* Helper to append a {sv} entry for an array of strings */
static void append_dict_str_array(DBusMessageIter *dict, const char *key,
                                   const char **vals, int count) {
    DBusMessageIter entry, var, arr;
    dbus_message_iter_open_container(dict, DBUS_TYPE_DICT_ENTRY,
                                      NULL, &entry);
    dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key);
    dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "as", &var);
    dbus_message_iter_open_container(&var, DBUS_TYPE_ARRAY, "s", &arr);
    for (int i = 0; i < count; i++)
        dbus_message_iter_append_basic(&arr, DBUS_TYPE_STRING, &vals[i]);
    dbus_message_iter_close_container(&var, &arr);
    dbus_message_iter_close_container(&entry, &var);
    dbus_message_iter_close_container(dict, &entry);
}

/* Fill an already-open a{sv} container with the current track metadata. */
static void append_metadata_contents(DBusMessageIter *meta) {
    /* mpris:trackid */
    { DBusMessageIter me, mv;
      dbus_message_iter_open_container(meta, DBUS_TYPE_DICT_ENTRY,
                                        NULL, &me);
      const char *mk = "mpris:trackid";
      dbus_message_iter_append_basic(&me, DBUS_TYPE_STRING, &mk);
      dbus_message_iter_open_container(&me, DBUS_TYPE_VARIANT, "o", &mv);
      const char *tid = OBJ_PATH "/Track/0";
      dbus_message_iter_append_basic(&mv, DBUS_TYPE_OBJECT_PATH, &tid);
      dbus_message_iter_close_container(&me, &mv);
      dbus_message_iter_close_container(meta, &me); }

    /* mpris:length */
    { DBusMessageIter me, mv;
      dbus_message_iter_open_container(meta, DBUS_TYPE_DICT_ENTRY,
                                        NULL, &me);
      const char *mk = "mpris:length";
      dbus_message_iter_append_basic(&me, DBUS_TYPE_STRING, &mk);
      dbus_message_iter_open_container(&me, DBUS_TYPE_VARIANT, "x", &mv);
      dbus_message_iter_append_basic(&mv, DBUS_TYPE_INT64,
                                      &state.duration_us);
      dbus_message_iter_close_container(&me, &mv);
      dbus_message_iter_close_container(meta, &me); }

    /* xesam:title */
    { DBusMessageIter me, mv;
      dbus_message_iter_open_container(meta, DBUS_TYPE_DICT_ENTRY,
                                        NULL, &me);
      const char *mk = "xesam:title";
      dbus_message_iter_append_basic(&me, DBUS_TYPE_STRING, &mk);
      dbus_message_iter_open_container(&me, DBUS_TYPE_VARIANT, "s", &mv);
      dbus_message_iter_append_basic(&mv, DBUS_TYPE_STRING, &state.title);
      dbus_message_iter_close_container(&me, &mv);
      dbus_message_iter_close_container(meta, &me); }

    /* xesam:artist (as) */
    { DBusMessageIter me, mv, arr;
      dbus_message_iter_open_container(meta, DBUS_TYPE_DICT_ENTRY,
                                        NULL, &me);
      const char *mk = "xesam:artist";
      dbus_message_iter_append_basic(&me, DBUS_TYPE_STRING, &mk);
      dbus_message_iter_open_container(&me, DBUS_TYPE_VARIANT, "as", &mv);
      dbus_message_iter_open_container(&mv, DBUS_TYPE_ARRAY, "s", &arr);
      const char *artist = state.artist;
      dbus_message_iter_append_basic(&arr, DBUS_TYPE_STRING, &artist);
      dbus_message_iter_close_container(&mv, &arr);
      dbus_message_iter_close_container(&me, &mv);
      dbus_message_iter_close_container(meta, &me); }

    /* xesam:album */
    { DBusMessageIter me, mv;
      dbus_message_iter_open_container(meta, DBUS_TYPE_DICT_ENTRY,
                                        NULL, &me);
      const char *mk = "xesam:album";
      dbus_message_iter_append_basic(&me, DBUS_TYPE_STRING, &mk);
      dbus_message_iter_open_container(&me, DBUS_TYPE_VARIANT, "s", &mv);
      dbus_message_iter_append_basic(&mv, DBUS_TYPE_STRING, &state.album);
      dbus_message_iter_close_container(&me, &mv);
      dbus_message_iter_close_container(meta, &me); }

    /* mpris:artUrl (if present) */
    if (state.art_url[0]) {
      DBusMessageIter me, mv;
      dbus_message_iter_open_container(meta, DBUS_TYPE_DICT_ENTRY,
                                        NULL, &me);
      const char *mk = "mpris:artUrl";
      dbus_message_iter_append_basic(&me, DBUS_TYPE_STRING, &mk);
      dbus_message_iter_open_container(&me, DBUS_TYPE_VARIANT, "s", &mv);
      const char *au = state.art_url;
      dbus_message_iter_append_basic(&mv, DBUS_TYPE_STRING, &au);
      dbus_message_iter_close_container(&me, &mv);
      dbus_message_iter_close_container(meta, &me);
    }
}

static void append_metadata(DBusMessageIter *dict) {
    DBusMessageIter entry, var, meta;
    dbus_message_iter_open_container(dict, DBUS_TYPE_DICT_ENTRY,
                                      NULL, &entry);
    const char *k = "Metadata";
    dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &k);
    dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT,
                                      "a{sv}", &var);
    dbus_message_iter_open_container(&var, DBUS_TYPE_ARRAY, "{sv}", &meta);
    append_metadata_contents(&meta);
    dbus_message_iter_close_container(&var, &meta);
    dbus_message_iter_close_container(&entry, &var);
    dbus_message_iter_close_container(dict, &entry);
}

/* Reply to Properties.Get("...Player", "Metadata") with v(a{sv}). */
static void send_metadata_reply(DBusMessage *msg) {
    DBusMessage *reply = dbus_message_new_method_return(msg);
    DBusMessageIter iter, var, meta;
    dbus_message_iter_init_append(reply, &iter);
    dbus_message_iter_open_container(&iter, DBUS_TYPE_VARIANT, "a{sv}", &var);
    dbus_message_iter_open_container(&var, DBUS_TYPE_ARRAY, "{sv}", &meta);
    append_metadata_contents(&meta);
    dbus_message_iter_close_container(&var, &meta);
    dbus_message_iter_close_container(&iter, &var);
    dbus_connection_send(conn, reply, NULL);
    dbus_message_unref(reply);
}

static void handle_properties_getall(DBusMessage *msg, const char *iface) {
    DBusMessage *reply = dbus_message_new_method_return(msg);
    DBusMessageIter iter, dict;

    dbus_message_iter_init_append(reply, &iter);
    dbus_message_iter_open_container(&iter, DBUS_TYPE_ARRAY, "{sv}", &dict);

    if (!strcmp(iface, ROOT_IFACE)) {
        append_dict_bool(&dict, "CanQuit", 1);
        append_dict_bool(&dict, "CanRaise", 1);
        append_dict_bool(&dict, "HasTrackList", 0);
        append_dict_str(&dict, "Identity", "Sonar");
        append_dict_str(&dict, "DesktopEntry", "sonar");
        {
            const char *schemes[] = {"file"};
            append_dict_str_array(&dict, "SupportedUriSchemes", schemes, 1);
        }
        {
            const char *mimes[] = {"audio/mpeg", "audio/x-wav",
                                   "audio/aac", "audio/x-m4a"};
            append_dict_str_array(&dict, "SupportedMimeTypes", mimes, 4);
        }
    } else if (!strcmp(iface, PLAYER_IFACE)) {
        append_dict_str(&dict, "PlaybackStatus", state.playback_status);
        append_dict_str(&dict, "LoopStatus", state.loop_status);
        append_dict_double(&dict, "Rate", state.rate);
        append_dict_double(&dict, "MinimumRate", 0.5);
        append_dict_double(&dict, "MaximumRate", 2.0);
        append_dict_double(&dict, "Volume", state.volume);
        append_dict_int64(&dict, "Position", current_position_us());
        append_dict_bool(&dict, "Shuffle", state.shuffle);
        append_dict_bool(&dict, "CanGoNext", state.can_go_next);
        append_dict_bool(&dict, "CanGoPrevious", state.can_go_previous);
        append_dict_bool(&dict, "CanPlay", state.can_play);
        append_dict_bool(&dict, "CanPause", state.can_pause);
        append_dict_bool(&dict, "CanSeek", state.can_seek);
        append_dict_bool(&dict, "CanControl", state.can_control);
        append_metadata(&dict);
    }

    dbus_message_iter_close_container(&iter, &dict);
    dbus_connection_send(conn, reply, NULL);
    dbus_message_unref(reply);
}

/* ──────────────────────────────────────────────
 *  Properties.Set
 * ────────────────────────────────────────────── */
static void handle_properties_set(DBusMessage *msg, const char *iface,
                                   const char *prop) {
    DBusMessageIter iter;
    dbus_message_iter_init(msg, &iter);
    dbus_message_iter_next(&iter);  /* skip interface */
    dbus_message_iter_next(&iter);  /* skip property name */

    if (!strcmp(iface, PLAYER_IFACE)) {
        if (!strcmp(prop, "Volume")) {
            double v = 1.0;
            if (dbus_message_iter_get_arg_type(&iter) == DBUS_TYPE_VARIANT) {
                DBusMessageIter sub;
                dbus_message_iter_recurse(&iter, &sub);
                dbus_message_iter_get_basic(&sub, &v);
            }
            state.volume = v;
            char buf[64];
            snprintf(buf, sizeof(buf), "CALL setVolume %f\n", v);
            send_to_java(buf);
        } else if (!strcmp(prop, "LoopStatus")) {
            const char *s = "None";
            if (dbus_message_iter_get_arg_type(&iter) == DBUS_TYPE_VARIANT) {
                DBusMessageIter sub;
                dbus_message_iter_recurse(&iter, &sub);
                dbus_message_iter_get_basic(&sub, &s);
            }
            free(state.loop_status);
            state.loop_status = strdup(s);
            char buf[128];
            snprintf(buf, sizeof(buf), "CALL setLoopStatus %s\n", s);
            send_to_java(buf);
        } else if (!strcmp(prop, "Shuffle")) {
            dbus_bool_t s = 0;
            if (dbus_message_iter_get_arg_type(&iter) == DBUS_TYPE_VARIANT) {
                DBusMessageIter sub;
                dbus_message_iter_recurse(&iter, &sub);
                dbus_message_iter_get_basic(&sub, &s);
            }
            state.shuffle = s;
            char buf[32];
            snprintf(buf, sizeof(buf), "CALL setShuffle %d\n", (int)s);
            send_to_java(buf);
        } else if (!strcmp(prop, "Rate")) {
            double r = 1.0;
            if (dbus_message_iter_get_arg_type(&iter) == DBUS_TYPE_VARIANT) {
                DBusMessageIter sub;
                dbus_message_iter_recurse(&iter, &sub);
                dbus_message_iter_get_basic(&sub, &r);
            }
            state.rate = r;
            char buf[64];
            snprintf(buf, sizeof(buf), "CALL setRate %f\n", r);
            send_to_java(buf);
        }
    }

    DBusMessage *reply = dbus_message_new_method_return(msg);
    dbus_connection_send(conn, reply, NULL);
    dbus_message_unref(reply);
}

/* ──────────────────────────────────────────────
 *  Top-level D-Bus message dispatch
 * ────────────────────────────────────────────── */
static void handle_dbus_message(DBusMessage *msg) {
    const char *iface  = dbus_message_get_interface(msg);
    const char *member = dbus_message_get_member(msg);
    const char *path   = dbus_message_get_path(msg);
    int type = dbus_message_get_type(msg);

    if (type != DBUS_MESSAGE_TYPE_METHOD_CALL) return;

    /* Handle Introspectable on any path */
    if (iface && !strcmp(iface, "org.freedesktop.DBus.Introspectable") &&
        member && !strcmp(member, "Introspect")) {
        const char *xml =
            "<!DOCTYPE node PUBLIC \"-//freedesktop//DTD D-BUS Object Introspection 1.0//EN\"\n"
            "\"http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd\">\n"
            "<node>\n"
            "  <interface name=\"org.freedesktop.DBus.Introspectable\">\n"
            "    <method name=\"Introspect\">\n"
            "      <arg name=\"data\" direction=\"out\" type=\"s\"/>\n"
            "    </method>\n"
            "  </interface>\n"
            "  <interface name=\"org.freedesktop.DBus.Properties\">\n"
            "    <method name=\"Get\">\n"
            "      <arg name=\"interface_name\" direction=\"in\" type=\"s\"/>\n"
            "      <arg name=\"property_name\" direction=\"in\" type=\"s\"/>\n"
            "      <arg name=\"value\" direction=\"out\" type=\"v\"/>\n"
            "    </method>\n"
            "    <method name=\"GetAll\">\n"
            "      <arg name=\"interface_name\" direction=\"in\" type=\"s\"/>\n"
            "      <arg name=\"properties\" direction=\"out\" type=\"a{sv}\"/>\n"
            "    </method>\n"
            "    <method name=\"Set\">\n"
            "      <arg name=\"interface_name\" direction=\"in\" type=\"s\"/>\n"
            "      <arg name=\"property_name\" direction=\"in\" type=\"s\"/>\n"
            "      <arg name=\"value\" direction=\"in\" type=\"v\"/>\n"
            "    </method>\n"
            "    <signal name=\"PropertiesChanged\">\n"
            "      <arg name=\"interface_name\" type=\"s\"/>\n"
            "      <arg name=\"changed_properties\" type=\"a{sv}\"/>\n"
            "      <arg name=\"invalidated_properties\" type=\"as\"/>\n"
            "    </signal>\n"
            "  </interface>\n"
            "  <interface name=\"org.mpris.MediaPlayer2\">\n"
            "    <method name=\"Raise\"/>\n"
            "    <method name=\"Quit\"/>\n"
            "    <property name=\"CanQuit\" type=\"b\" access=\"read\"/>\n"
            "    <property name=\"CanRaise\" type=\"b\" access=\"read\"/>\n"
            "    <property name=\"HasTrackList\" type=\"b\" access=\"read\"/>\n"
            "    <property name=\"Identity\" type=\"s\" access=\"read\"/>\n"
            "    <property name=\"DesktopEntry\" type=\"s\" access=\"read\"/>\n"
            "    <property name=\"SupportedUriSchemes\" type=\"as\" access=\"read\"/>\n"
            "    <property name=\"SupportedMimeTypes\" type=\"as\" access=\"read\"/>\n"
            "  </interface>\n"
            "  <interface name=\"org.mpris.MediaPlayer2.Player\">\n"
            "    <method name=\"Next\"/>\n"
            "    <method name=\"Previous\"/>\n"
            "    <method name=\"Pause\"/>\n"
            "    <method name=\"PlayPause\"/>\n"
            "    <method name=\"Stop\"/>\n"
            "    <method name=\"Play\"/>\n"
            "    <method name=\"Seek\">\n"
            "      <arg name=\"Offset\" direction=\"in\" type=\"x\"/>\n"
            "    </method>\n"
            "    <method name=\"SetPosition\">\n"
            "      <arg name=\"TrackId\" direction=\"in\" type=\"o\"/>\n"
            "      <arg name=\"Position\" direction=\"in\" type=\"x\"/>\n"
            "    </method>\n"
            "    <method name=\"OpenUri\">\n"
            "      <arg name=\"Uri\" direction=\"in\" type=\"s\"/>\n"
            "    </method>\n"
            "    <signal name=\"Seeked\">\n"
            "      <arg name=\"Position\" type=\"x\"/>\n"
            "    </signal>\n"
            "    <property name=\"PlaybackStatus\" type=\"s\" access=\"read\"/>\n"
            "    <property name=\"LoopStatus\" type=\"s\" access=\"readwrite\"/>\n"
            "    <property name=\"Rate\" type=\"d\" access=\"readwrite\"/>\n"
            "    <property name=\"Shuffle\" type=\"b\" access=\"readwrite\"/>\n"
            "    <property name=\"Metadata\" type=\"a{sv}\" access=\"read\"/>\n"
            "    <property name=\"Volume\" type=\"d\" access=\"readwrite\"/>\n"
            "    <property name=\"Position\" type=\"x\" access=\"read\"/>\n"
            "    <property name=\"MinimumRate\" type=\"d\" access=\"read\"/>\n"
            "    <property name=\"MaximumRate\" type=\"d\" access=\"read\"/>\n"
            "    <property name=\"CanGoNext\" type=\"b\" access=\"read\"/>\n"
            "    <property name=\"CanGoPrevious\" type=\"b\" access=\"read\"/>\n"
            "    <property name=\"CanPlay\" type=\"b\" access=\"read\"/>\n"
            "    <property name=\"CanPause\" type=\"b\" access=\"read\"/>\n"
            "    <property name=\"CanSeek\" type=\"b\" access=\"read\"/>\n"
            "    <property name=\"CanControl\" type=\"b\" access=\"read\"/>\n"
            "  </interface>\n"
            "</node>\n";
        DBusMessage *reply = dbus_message_new_method_return(msg);
        dbus_message_append_args(reply, DBUS_TYPE_STRING, &xml, DBUS_TYPE_INVALID);
        dbus_connection_send(conn, reply, NULL);
        dbus_message_unref(reply);
        return;
    }

    if (!path || strcmp(path, OBJ_PATH) != 0) {
        send_reply(msg);
        return;
    }

    if (iface && !strcmp(iface, PROPS_IFACE)) {
        if (!strcmp(member, "Get")) {
            const char *target_iface = NULL, *prop = NULL;
            dbus_message_get_args(msg, NULL,
                DBUS_TYPE_STRING, &target_iface,
                DBUS_TYPE_STRING, &prop,
                DBUS_TYPE_INVALID);
            if (target_iface && prop)
                handle_properties_get(msg, target_iface, prop);
        } else if (!strcmp(member, "GetAll")) {
            const char *target_iface = NULL;
            dbus_message_get_args(msg, NULL,
                DBUS_TYPE_STRING, &target_iface,
                DBUS_TYPE_INVALID);
            if (target_iface)
                handle_properties_getall(msg, target_iface);
        } else if (!strcmp(member, "Set")) {
            DBusMessageIter iter;
            dbus_message_iter_init(msg, &iter);
            const char *target_iface = NULL, *prop = NULL;
            if (dbus_message_iter_get_arg_type(&iter) == DBUS_TYPE_STRING) {
                dbus_message_iter_get_basic(&iter, &target_iface);
                dbus_message_iter_next(&iter);
                if (dbus_message_iter_get_arg_type(&iter) == DBUS_TYPE_STRING) {
                    dbus_message_iter_get_basic(&iter, &prop);
                }
            }
            if (target_iface && prop)
                handle_properties_set(msg, target_iface, prop);
            else
                send_reply(msg);
        } else {
            send_reply(msg);
        }
    } else {
        handle_method_call(msg, iface ? iface : "", member ? member : "");
    }
}

/* ──────────────────────────────────────────────
 *  Emit PropertiesChanged signal
 * ────────────────────────────────────────────── */
static void emit_properties_changed(void) {
    DBusMessage *sig = dbus_message_new_signal(OBJ_PATH, PROPS_IFACE,
                                                "PropertiesChanged");
    DBusMessageIter iter, args, changed_keys;
    dbus_message_iter_init_append(sig, &iter);

    /* First arg: interface name */
    const char *iface = PLAYER_IFACE;
    dbus_message_iter_append_basic(&iter, DBUS_TYPE_STRING, &iface);

    /* Second arg: changed properties dict {sv} */
    dbus_message_iter_open_container(&iter, DBUS_TYPE_ARRAY, "{sv}", &args);

    append_dict_str(&args, "PlaybackStatus", state.playback_status);
    append_dict_str(&args, "LoopStatus", state.loop_status);
    append_dict_double(&args, "Rate", state.rate);
    append_dict_double(&args, "Volume", state.volume);
    /* NOTE: Position is deliberately NOT included — per MPRIS spec it
     * does not emit change notifications; clients extrapolate it and
     * rely on the Seeked signal for jumps.  Broadcasting it every few
     * seconds made client sliders snap back. */
    append_dict_bool(&args, "Shuffle", state.shuffle);
    append_dict_bool(&args, "CanSeek", state.can_seek);
    append_dict_bool(&args, "CanGoNext", state.can_go_next);
    append_dict_bool(&args, "CanGoPrevious", state.can_go_previous);
    append_dict_bool(&args, "CanPlay", state.can_play);
    append_dict_bool(&args, "CanPause", state.can_pause);
    append_dict_bool(&args, "CanControl", state.can_control);
    append_metadata(&args);

    dbus_message_iter_close_container(&iter, &args);

    /* Third arg: invalidated properties (empty) */
    dbus_message_iter_open_container(&iter, DBUS_TYPE_ARRAY, "s",
                                      &changed_keys);
    dbus_message_iter_close_container(&iter, &changed_keys);

    dbus_connection_send(conn, sig, NULL);
    dbus_message_unref(sig);
    dbus_connection_flush(conn);
}

/* ──────────────────────────────────────────────
 *  Parse SIGNAL command from Java and update state
 * ──────────────────────────────────────────────
 *
 * Format (pipe-delimited, 11 fields):
 *   SIGNAL status|title|artist|album|duration_us|art_url|
 *          position_us|volume|loop_status|shuffle|rate
 *
 * Pipe and backslash are escaped: \| and \\ in data.
 * CanPlay/Pause/Seek/GoNext/GoPrevious/Control are NOT
 * updated from SIGNALs — the C daemon always reports them as
 * true (the player supports all features).
 */
static int parse_signal(char *payload) {
    /*
     * Split on unescaped '|' — must preserve EMPTY fields and must NOT
     * split on escaped pipes (\|).  strtok_r() does neither: it collapses
     * consecutive delimiters (shifting every field after an empty one)
     * and splits on escaped pipes inside titles.  Manual scan instead.
     * Unescaping is done in place (output never outruns input).
     */
    char *fields[11];
    int   nf = 0;
    char *rd = payload, *wr = payload, *start = payload;

    while (nf < 11) {
        if (rd[0] == '\\' && (rd[1] == '|' || rd[1] == '\\')) {
            *wr++ = rd[1];
            rd += 2;
        } else if (rd[0] == '|' || rd[0] == '\0') {
            int at_end = (rd[0] == '\0');
            *wr = '\0';
            fields[nf++] = start;
            if (at_end) break;
            rd++;
            wr = start = rd;
        } else {
            *wr++ = *rd++;
        }
    }

    if (nf < 11) {
        fprintf(stderr, "[sonar_mpris_d] malformed SIGNAL (%d fields)\n", nf);
        return 0;
    }

    /* Detect seeks: if the new position differs from our extrapolation
     * by more than 2 s, the user (or an MPRIS client) jumped — emit the
     * Seeked signal, otherwise clients snap back to their own clock. */
    long long expected_us = current_position_us();
    long long new_pos_us  = atoll(fields[6]);
    int seeked = (last_pos_update_ms > 0) &&
                 (llabs(new_pos_us - expected_us) > 2000000LL);

    free(state.playback_status); state.playback_status = strdup(fields[0]);
    free(state.title);           state.title           = strdup(fields[1]);
    free(state.artist);          state.artist          = strdup(fields[2]);
    free(state.album);           state.album           = strdup(fields[3]);
    state.duration_us = atoll(fields[4]);
    free(state.art_url);         state.art_url         = strdup(fields[5]);
    state.position_us = new_pos_us;
    state.volume      = atof(fields[7]);
    free(state.loop_status);     state.loop_status     = strdup(fields[8]);
    state.shuffle     = atoi(fields[9]);
    state.rate        = atof(fields[10]);
    last_pos_update_ms = now_ms();

    emit_properties_changed();

    if (seeked) {
        DBusMessage *sig = dbus_message_new_signal(OBJ_PATH, PLAYER_IFACE,
                                                    "Seeked");
        dbus_int64_t pos = state.position_us;
        dbus_message_append_args(sig, DBUS_TYPE_INT64, &pos,
                                 DBUS_TYPE_INVALID);
        dbus_connection_send(conn, sig, NULL);
        dbus_message_unref(sig);
        dbus_connection_flush(conn);
    }
    return 1;
}

/* ──────────────────────────────────────────────
 *  Read commands from Java via Unix socket.
 *
 *  Called ONLY when select() reports the socket readable, and performs
 *  exactly ONE recv() so it can never block the event loop.  (The old
 *  version looped on a blocking recv(): after the first SIGNAL from
 *  Java it hung inside recv() forever, leaving the daemon deaf to all
 *  D-Bus traffic — method calls and Get/GetAll timed out, which is why
 *  desktop controls went dead as soon as playback started.)
 *
 *  A static accumulator carries partial lines across calls.
 *
 *  Returns 1 when QUIT was received or the peer closed the socket.
 * ────────────────────────────────────────────── */
static int read_from_java_once(void) {
    static char   acc[32768];
    static size_t acc_len = 0;

    ssize_t n = recv(sock_fd, acc + acc_len, sizeof(acc) - acc_len - 1, 0);
    if (n == 0) return 1;                       /* peer closed */
    if (n < 0)  return (errno == EINTR || errno == EAGAIN ||
                        errno == EWOULDBLOCK) ? 0 : 1;

    /* Strip NUL bytes from the received chunk: embedded NULs (e.g. from
     * NUL-padded ID3 tags) would make strchr() miss the newline and
     * permanently wedge the line accumulator.  The Java side sanitizes
     * too; this is defence in depth. */
    {
        size_t w = acc_len;
        for (size_t r = acc_len; r < acc_len + (size_t)n; r++)
            if (acc[r] != '\0') acc[w++] = acc[r];
        acc_len = w;
    }
    acc[acc_len] = '\0';

    int   quit = 0;
    char *line = acc;
    char *nl;
    while ((nl = strchr(line, '\n')) != NULL) {
        *nl = '\0';
        if (strncmp(line, "SIGNAL ", 7) == 0) {
            parse_signal(line + 7);
        } else if (strcmp(line, "QUIT") == 0) {
            quit = 1;
        }
        line = nl + 1;
    }

    /* Keep any trailing partial line for the next call */
    size_t rem = acc_len - (size_t)(line - acc);
    memmove(acc, line, rem);
    acc_len = rem;

    /* Full buffer without a newline: broken peer, drop it */
    if (acc_len >= sizeof(acc) - 1) acc_len = 0;

    return quit;
}

/* ──────────────────────────────────────────────
 *  D-Bus connection setup
 * ────────────────────────────────────────────── */
static int setup_dbus(void) {
    DBusError err;
    dbus_error_init(&err);

    conn = dbus_bus_get(DBUS_BUS_SESSION, &err);
    if (dbus_error_is_set(&err)) {
        fprintf(stderr, "[sonar_mpris_d] D-Bus connection error: %s\n",
                err.message);
        dbus_error_free(&err);
        return -1;
    }

    /* Request bus name */
    int ret = dbus_bus_request_name(conn, BUS_NAME,
        DBUS_NAME_FLAG_DO_NOT_QUEUE, &err);
    if (dbus_error_is_set(&err)) {
        fprintf(stderr, "[sonar_mpris_d] D-Bus name error: %s\n",
                err.message);
        dbus_error_free(&err);
        return -1;
    }
    if (ret != DBUS_REQUEST_NAME_REPLY_PRIMARY_OWNER) {
        fprintf(stderr, "[sonar_mpris_d] D-Bus name already owned\n");
        return -1;
    }

    return 0;
}

/* ──────────────────────────────────────────────
 *  Reply helpers
 * ────────────────────────────────────────────── */
static void send_reply(DBusMessage *msg) {
    DBusMessage *reply = dbus_message_new_method_return(msg);
    dbus_connection_send(conn, reply, NULL);
    dbus_message_unref(reply);
}

static void send_reply_variant(DBusMessage *msg, int type, const void *val) {
    DBusMessage *reply = dbus_message_new_method_return(msg);
    DBusMessageIter iter, sub;
    const char *sig =
        type == DBUS_TYPE_BOOLEAN ? "b" :
        type == DBUS_TYPE_STRING  ? "s" :
        type == DBUS_TYPE_DOUBLE  ? "d" :
        type == DBUS_TYPE_INT64   ? "x" : "s";
    dbus_message_iter_init_append(reply, &iter);
    dbus_message_iter_open_container(&iter, DBUS_TYPE_VARIANT, sig, &sub);
    dbus_message_iter_append_basic(&sub, type, val);
    dbus_message_iter_close_container(&iter, &sub);
    dbus_connection_send(conn, reply, NULL);
    dbus_message_unref(reply);
}

/* ──────────────────────────────────────────────
 *  Cleanup
 * ────────────────────────────────────────────── */
static void cleanup(void) {
    if (conn) {
        dbus_connection_close(conn);
        dbus_connection_unref(conn);
        conn = NULL;
    }
    if (sock_fd >= 0) {
        close(sock_fd);
        sock_fd = -1;
    }
    free_state();
}

/* ──────────────────────────────────────────────
 *  Main
 * ────────────────────────────────────────────── */
int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "Usage: sonar_mpris_d <socket-path>\n");
        return 1;
    }

    /* Ignore SIGPIPE */
    signal(SIGPIPE, SIG_IGN);

    /* Connect to the Unix socket that Java created */
    const char *socket_path = argv[1];

    sock_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sock_fd < 0) {
        perror("[sonar_mpris_d] socket");
        return 1;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, socket_path, sizeof(addr.sun_path) - 1);

    if (connect(sock_fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("[sonar_mpris_d] connect");
        close(sock_fd);
        return 1;
    }

    /* Non-blocking: reads happen only via select(), and even a spurious
     * wakeup must never be able to stall the event loop in recv(). */
    {
        int fl = fcntl(sock_fd, F_GETFL, 0);
        if (fl >= 0) fcntl(sock_fd, F_SETFL, fl | O_NONBLOCK);
    }

    init_state();

    if (setup_dbus() != 0) {
        cleanup();
        return 1;
    }

    /* Tell Java we're ready */
    send_to_java("READY\n");

    /* Emit initial PropertiesChanged for root interface so DEs discover us */
    {
        DBusMessage *sig = dbus_message_new_signal(OBJ_PATH, PROPS_IFACE,
                                                    "PropertiesChanged");
        DBusMessageIter iter, args, changed_keys;
        dbus_message_iter_init_append(sig, &iter);
        const char *iface = ROOT_IFACE;
        dbus_message_iter_append_basic(&iter, DBUS_TYPE_STRING, &iface);
        dbus_message_iter_open_container(&iter, DBUS_TYPE_ARRAY, "{sv}", &args);
        append_dict_str(&args, "Identity", "Sonar");
        append_dict_str(&args, "DesktopEntry", "sonar");
        append_dict_bool(&args, "CanQuit", 1);
        append_dict_bool(&args, "CanRaise", 1);
        append_dict_bool(&args, "HasTrackList", 0);
        {
            const char *schemes[] = {"file"};
            append_dict_str_array(&args, "SupportedUriSchemes", schemes, 1);
        }
        {
            const char *mimes[] = {"audio/mpeg", "audio/x-wav",
                                   "audio/aac", "audio/x-m4a"};
            append_dict_str_array(&args, "SupportedMimeTypes", mimes, 4);
        }
        dbus_message_iter_close_container(&iter, &args);
        dbus_message_iter_open_container(&iter, DBUS_TYPE_ARRAY, "s",
                                          &changed_keys);
        dbus_message_iter_close_container(&iter, &changed_keys);
        dbus_connection_send(conn, sig, NULL);
        dbus_message_unref(sig);
        dbus_connection_flush(conn);
    }

    fprintf(stderr, "[sonar_mpris_d] started, fd=%d\n", sock_fd);

    /* Main event loop: poll D-Bus and Java socket.
     * The loop must NEVER block on the Java socket — D-Bus method calls
     * have to keep being answered while playback updates stream in. */
    while (1) {
        dbus_connection_read_write(conn, 0);
        DBusMessage *msg;
        while ((msg = dbus_connection_pop_message(conn)) != NULL) {
            handle_dbus_message(msg);
            dbus_message_unref(msg);
        }
        dbus_connection_flush(conn);  /* push replies out promptly */

        fd_set fds;
        FD_ZERO(&fds);
        FD_SET(sock_fd, &fds);

        struct timeval tv = { .tv_sec = 0, .tv_usec = 50000 };  /* 50 ms */
        int sr = select(sock_fd + 1, &fds, NULL, NULL, &tv);
        if (sr > 0 && FD_ISSET(sock_fd, &fds)) {
            if (read_from_java_once())
                break;  /* QUIT received or Java side closed the socket */
        }
        if (sr < 0 && errno != EINTR) {
            break;
        }
    }

    cleanup();
    return 0;
}
