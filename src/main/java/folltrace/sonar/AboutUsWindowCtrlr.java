package folltrace.sonar;

import javafx.fxml.FXML;
import javafx.scene.control.Hyperlink;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.net.URI;

import java.awt.Desktop;

public class AboutUsWindowCtrlr {
    @FXML
    private Hyperlink websiteLink;

    @FXML
    private ImageView aboutImage;

    @FXML
    public void initialize() {
        // Set the clip as a circle to crop the image
        double radius = aboutImage.getFitWidth() / 2;
        if (radius == 0) {
            radius = aboutImage.getFitHeight() / 2;
        }
        Circle clip = new Circle(radius, radius, radius);
        aboutImage.setClip(clip);

        // Load the image
        Image image = new Image("/logo.png");
        aboutImage.setImage(image);

        // Create and apply the drop shadow effect
        DropShadow dropShadow = new DropShadow();
        dropShadow.setRadius(20.0);
        dropShadow.setOffsetX(3.0);
        dropShadow.setOffsetY(3.0);
        dropShadow.setColor(Color.BLACK);

        aboutImage.setEffect(dropShadow);
    }
    @FXML
    private void handleHyperlinkAction() {
        try {
            Desktop.getDesktop().browse(new URI("https://github.com/bohdan-myronenko/Sonar"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
