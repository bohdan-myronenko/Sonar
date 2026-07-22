package folltrace.sonar;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Persists user settings to ~/.config/sonar/settings.properties.
 * Thread-safe via synchronized access.
 */
public final class Settings {

    private static final Path CONFIG_DIR  = Path.of(System.getProperty("user.home"), ".config", "sonar");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("settings.properties");

    private final Properties props = new Properties();
    private static final Settings INSTANCE = new Settings();

    private Settings() {
        try {
            Files.createDirectories(CONFIG_DIR);
        } catch (IOException ignored) {}
        load();
    }

    public static Settings get() {
        return INSTANCE;
    }

    // ── Getters / Setters ──────────────────────────────────────────────

    public synchronized double getVolume() {
        String v = props.getProperty("volume", "0.5");
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return 0.5; }
    }

    public synchronized void setVolume(double volume) {
        props.setProperty("volume", String.valueOf(volume));
        save();
    }

    public synchronized boolean getDarkTheme() {
        return Boolean.parseBoolean(props.getProperty("darkTheme", "false"));
    }

    public synchronized void setDarkTheme(boolean enabled) {
        props.setProperty("darkTheme", String.valueOf(enabled));
        save();
    }

    // ── Persistence ────────────────────────────────────────────────────

    private synchronized void load() {
        if (Files.exists(CONFIG_FILE)) {
            try (var in = new FileReader(CONFIG_FILE.toFile())) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("[Settings] Failed to load: " + e.getMessage());
            }
        }
    }

    private synchronized void save() {
        try (var out = new FileWriter(CONFIG_FILE.toFile())) {
            props.store(out, "Sonar settings");
        } catch (IOException e) {
            System.err.println("[Settings] Failed to save: " + e.getMessage());
        }
    }
}
