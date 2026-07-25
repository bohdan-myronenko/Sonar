package folltrace.sonar;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

public final class UIManager {

    private static final String DARK_CSS = Objects.requireNonNull(
            UIManager.class.getResource("/dark.css")).toExternalForm();

    /** Effect that turns black silhouettes white — applied to button icons in dark mode. */
    private static final ColorAdjust INVERT_EFFECT = new ColorAdjust();
    static { INVERT_EFFECT.setBrightness(1.0); }

    /** Tracks whether dark theme is active so icon effects stay correct across hover swaps. */
    private static boolean darkThemeActive = false;

    /**
     * Registry of all buttons that received an icon via setImageToButton /
     * setHoverEffectToButton, mapped to their normal-state icon path + size.
     * We use WeakHashMap so buttons that get garbage-collected are dropped.
     */
    private record IconKey(String path, int w, int h) {}
    private static final Map<Button, IconKey> iconRegistry = new WeakHashMap<>();

    private UIManager() {}

    /**
     * Loads an image and sets it to a button, applying the dark-theme brightness
     * effect automatically when dark mode is active.
     * Also registers the button so theme changes can re-apply the icon.
     */
    public static void setImageToButton(Button button, String imagePath, int width, int height) {
        iconRegistry.put(button, new IconKey(imagePath, width, height));
        applyIcon(button, imagePath, width, height);
    }

    /**
     * Sets a hover effect on a button, swapping between two images.
     * The dark-theme brightness effect is preserved across swaps.
     */
    public static void setHoverEffectToButton(Button button, String normalImagePath, String hoverImagePath,
                                               int width, int height) {
        setImageToButton(button, normalImagePath, width, height);
        button.setOnMouseEntered(e -> applyIcon(button, hoverImagePath, width, height));
        button.setOnMouseExited(e -> applyIcon(button, normalImagePath, width, height));
    }

    /** Creates a button with an image. */
    public static Button createImageButton(String imagePath, int width, int height) {
        var button = new Button();
        setImageToButton(button, imagePath, width, height);
        return button;
    }

    /**
     * Toggles the dark theme on/off without clearing other stylesheets,
     * so the base (modena.css) styling never changes and layout stays stable.
     * Also inverts all registered button icons (black → white) for dark mode.
     */
    public static void changeTheme(Scene scene, boolean darkTheme) {
        var sheets = scene.getStylesheets();
        if (darkTheme) {
            if (!sheets.contains(DARK_CSS)) {
                sheets.add(DARK_CSS);
            }
        } else {
            sheets.remove(DARK_CSS);
        }

        darkThemeActive = darkTheme;
        // Re-apply every registered button's icon with the new theme state
        iconRegistry.forEach((btn, key) -> applyIcon(btn, key.path, key.w, key.h));
    }

    /** Returns true if the dark theme is currently active. */
    public static boolean isDarkTheme() {
        return darkThemeActive;
    }

    /** Returns the shared brightness effect used to invert dark icons to white. */
    public static ColorAdjust getInvertEffect() {
        return INVERT_EFFECT;
    }

    /** Internal helper: create the ImageView with or without the brightness effect. */
    private static void applyIcon(Button button, String imagePath, int width, int height) {
        var image = new Image(Objects.requireNonNull(UIManager.class.getResourceAsStream(imagePath)));
        var imageView = new ImageView(image);
        imageView.setFitWidth(width);
        imageView.setFitHeight(height);
        if (darkThemeActive) {
            imageView.setEffect(INVERT_EFFECT);
        }
        button.setGraphic(imageView);
    }
}
