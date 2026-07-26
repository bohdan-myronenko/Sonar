package folltrace.sonar;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;

/**
 * JNA bindings for a handful of C runtime functions needed by the player.
 *
 * <p>The C runtime lives in a different library on each platform, and the
 * {@code LC_*} category constants are <em>not</em> portable: glibc and the
 * Microsoft CRT number them differently.  Getting {@link #LC_NUMERIC} wrong
 * is silent and nasty, since {@code setlocale} would happily change some
 * unrelated category and leave the numeric locale alone, which makes
 * {@code mpv_create()} return NULL on comma-decimal locales.
 *
 * <pre>
 *   category      glibc   MSVC CRT
 *   LC_ALL          6        0
 *   LC_COLLATE      3        1
 *   LC_CTYPE        0        2
 *   LC_MONETARY     4        3
 *   LC_NUMERIC      1        4
 *   LC_TIME         2        5
 * </pre>
 */
@SuppressWarnings("unused")
interface CLibrary extends Library {

    /**
     * On Windows the CRT is {@code msvcrt.dll}; everywhere else it is libc.
     * ({@code msvcrt.dll} is the legacy CRT forwarder, but {@code setlocale}
     * is present and stable there, which is all we need.)
     */
    CLibrary INSTANCE = Native.load(Platform.isWindows() ? "msvcrt" : "c", CLibrary.class);

    int LC_NUMERIC = Platform.isWindows() ? 4 : 1;

    /** Set the current locale. Returns the new locale string or null on failure. */
    String setlocale(int category, String locale);
}
