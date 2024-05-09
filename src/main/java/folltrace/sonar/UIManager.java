package folltrace.sonar;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

public class UIManager {

    private static boolean isHovering = false;

    /**
     * Sets an image to a button.
     * @param button The button to set the image on.
     * @param imagePath The path to the image resource.
     * @param width The width of the image.
     * @param height The height of the image.
     */
    public static void setImageToButton(Button button, String imagePath, int width, int height) {
        Image image = new Image(UIManager.class.getResourceAsStream(imagePath));
        ImageView imageView = new ImageView(image);
        imageView.setFitWidth(width);
        imageView.setFitHeight(height);
        button.setGraphic(imageView);
    }

    public static void setHoverEffectToButton(Button button, String normalImagePath, String hoverImagePath, int width, int height) {
        // Set initial image based on hover state
        String initialImagePath = isHovering ? hoverImagePath : normalImagePath;
        setImageToButton(button, initialImagePath, width, height);

        // Change image on hover
        button.setOnMouseEntered(e -> {
            isHovering = true;
            setImageToButton(button, hoverImagePath, width, height);
        });
        button.setOnMouseExited(e -> {
            isHovering = false;
            setImageToButton(button, normalImagePath, width, height);
        });
    }


    /**
     * Creates a button with an image.
     *
     * @param imagePath The path to the image resource.
     * @param width The width of the image.
     * @param height The height of the image.
     * @return A new button with the specified image.
     */
    public static Button createImageButton(String imagePath, int width, int height) {
        Button button = new Button();
        setImageToButton(button, imagePath, width, height);
        return button;
    }


    /**
     * Changes the JavaFX style for a scene using a CSS file.
     *
     * @param scene
     * @param isChecked
     */
    public static void changeTheme(Scene scene, boolean isChecked){
        if (isChecked){
            scene.getStylesheets().clear();
            scene.getStylesheets().add(UIManager.class.getResource("/dark.css").toExternalForm());
        } else {
            scene.getStylesheets().clear();
        }
    }
}
