package folltrace.sonar;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.util.Objects;

public final class UIManager {

    private static final String DARK_CSS = Objects.requireNonNull(
            UIManager.class.getResource("/dark.css")).toExternalForm();

    /** Effect that turns black silhouettes white — applied to button icons in dark mode. */
    private static final ColorAdjust INVERT_EFFECT = new ColorAdjust();
    static { INVERT_EFFECT.setBrightness(1.0); }

    /** Tracks whether dark theme is active so icon effects stay correct across hover swaps. */
    private static boolean darkThemeActive = false;

    private UIManager() {}

    /**
     * Loads an image and sets it to a button, applying the dark-theme brightness
     * effect automatically when dark mode is active.
     */
    public static void setImageToButton(Button button, String imagePath, int width, int height) {
        var image = new Image(Objects.requireNonNull(UIManager.class.getResourceAsStream(imagePath)));
        var imageView = new ImageView(image);
        imageView.setFitWidth(width);
        imageView.setFitHeight(height);
        if (darkThemeActive) {
            imageView.setEffect(INVERT_EFFECT);
        }
        button.setGraphic(imageView);
    }

    /**
     * Sets a hover effect on a button, swapping between two images.
     * The dark-theme brightness effect is preserved across swaps.
     */
    public static void setHoverEffectToButton(Button button, String normalImagePath, String hoverImagePath,
                                               int width, int height) {
        setImageToButton(button, normalImagePath, width, height);

        button.setOnMouseEntered(e -> setImageToButton(button, hoverImagePath, width, height));
        button.setOnMouseExited(e -> setImageToButton(button, normalImagePath, width, height));
    }

    /**
     * Creates a button with an image.
     */
    public static Button createImageButton(String imagePath, int width, int height) {
        var button = new Button();
        setImageToButton(button, imagePath, width, height);
        return button;
    }

    /**
     * Toggles the dark theme on/off without clearing other stylesheets,
     * so the base (modena.css) styling never changes and layout stays stable.
     * Also inverts button icons (black → white) for dark mode.
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
        applyIconEffect(scene, darkTheme);
    }

    /** Recolour every Button's icon ImageView in the scene to match the theme. */
    private static void applyIconEffect(Scene scene, boolean dark) {
        for (var n : scene.getRoot().lookupAll("Button")) {
            if (n instanceof Button b && b.getGraphic() instanceof ImageView iv) {
                iv.setEffect(dark ? INVERT_EFFECT : null);
            }
        }
    }
}
