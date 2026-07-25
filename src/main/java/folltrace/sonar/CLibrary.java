package folltrace.sonar;

import com.sun.jna.Library;
import com.sun.jna.Native;

/**
 * JNA bindings for a handful of libc functions needed by the player.
 */
@SuppressWarnings("unused")
interface CLibrary extends Library {
    CLibrary INSTANCE = Native.load("c", CLibrary.class);

    int LC_NUMERIC = 1;

    /** Set the current locale. Returns the new locale string or null on failure. */
    String setlocale(int category, String locale);
}
