package folltrace.sonar;

import com.sun.jna.Platform;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the {@code ffmpeg} and {@code ffprobe} executables.
 *
 * <p>The two platforms resolve these very differently:
 *
 * <ul>
 *   <li><b>Linux</b> treats them as optional system tools. Distributions ship
 *       them, so the plain command name is used and the OS resolves it on
 *       {@code PATH}.</li>
 *   <li><b>Windows</b> has no system ffmpeg, so the packaged application
 *       bundles both executables under {@code app\ffmpeg\}. The launcher
 *       passes {@code -Dsonar.app.dir=$APPDIR}, which is how we find them
 *       without depending on the user's {@code PATH} or the process working
 *       directory.</li>
 * </ul>
 *
 * <p>Resolution falls back to the bare command name in every failure case, so
 * a development run, or a Windows user who installed ffmpeg themselves, still
 * works. Callers already treat a failed launch as "metadata unavailable", so a
 * name that resolves to nothing degrades rather than breaks.
 */
final class MediaTools {

    /** Set by the packaged launcher to the directory holding bundled resources. */
    private static final String APP_DIR_PROPERTY = "sonar.app.dir";

    /** Subdirectory of the app directory holding the bundled ffmpeg build. */
    private static final String BUNDLE_SUBDIR = "ffmpeg";

    private static final String FFMPEG  = resolve("ffmpeg");
    private static final String FFPROBE = resolve("ffprobe");

    private MediaTools() {}

    /** Command or absolute path to invoke ffmpeg with. */
    static String ffmpeg() { return FFMPEG; }

    /** Command or absolute path to invoke ffprobe with. */
    static String ffprobe() { return FFPROBE; }

    private static String resolve(String tool) {
        String fileName = Platform.isWindows() ? tool + ".exe" : tool;

        String appDir = System.getProperty(APP_DIR_PROPERTY);
        if (appDir != null && !appDir.isBlank()) {
            try {
                Path bundled = Path.of(appDir, BUNDLE_SUBDIR, fileName);
                if (Files.isRegularFile(bundled)) {
                    String path = bundled.toAbsolutePath().toString();
                    System.err.println("[Sonar] Using bundled " + tool + ": " + path);
                    return path;
                }
            } catch (Exception e) {
                // A malformed sonar.app.dir must not take the whole app down.
                System.err.println("[Sonar] Ignoring bad " + APP_DIR_PROPERTY + ": " + e.getMessage());
            }
        }
        return tool;   // resolved from PATH, or absent entirely
    }
}
