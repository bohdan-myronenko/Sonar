package folltrace.sonar;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.util.Objects;

public final class UIManager {

    private UIManager() {}

    /**
     * Loads an image and sets it to a button.
     *
     * @param button    The button to set the image on.
     * @param imagePath The path to the image resource.
     * @param width     The width of the image.
     * @param height    The height of the image.
     */
    public static void setImageToButton(Button button, String imagePath, int width, int height) {
        var image = new Image(Objects.requireNonNull(UIManager.class.getResourceAsStream(imagePath)));
        var imageView = new ImageView(image);
        imageView.setFitWidth(width);
        imageView.setFitHeight(height);
        button.setGraphic(imageView);
    }

    /**
     * Sets a hover effect on a button, swapping between two images.
     *
     * @param button          The button to add the effect to.
     * @param normalImagePath Image shown when not hovering.
     * @param hoverImagePath  Image shown when hovering.
     * @param width           Image width.
     * @param height          Image height.
     */
    public static void setHoverEffectToButton(Button button, String normalImagePath, String hoverImagePath,
                                               int width, int height) {
        setImageToButton(button, normalImagePath, width, height);

        button.setOnMouseEntered(e -> setImageToButton(button, hoverImagePath, width, height));
        button.setOnMouseExited(e -> setImageToButton(button, normalImagePath, width, height));
    }

    /**
     * Creates a button with an image.
     *
     * @param imagePath The path to the image resource.
     * @param width     The width of the image.
     * @param height    The height of the image.
     * @return A new button with the specified image.
     */
    public static Button createImageButton(String imagePath, int width, int height) {
        var button = new Button();
        setImageToButton(button, imagePath, width, height);
        return button;
    }

    /**
     * Changes the JavaFX style for a scene using a CSS file.
     *
     * @param scene     The scene to apply the theme to.
     * @param darkTheme Whether to apply the dark theme.
     */
    public static void changeTheme(Scene scene, boolean darkTheme) {
        scene.getStylesheets().clear();
        if (darkTheme) {
            scene.getStylesheets().add(Objects.requireNonNull(
                    UIManager.class.getResource("/dark.css")).toExternalForm());
        }
    }
}
