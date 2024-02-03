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
    private void handleHyperlinkAction() {
        try {
            Desktop.getDesktop().browse(new URI("https://github.com/bohdan-myronenko/Sonar"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
