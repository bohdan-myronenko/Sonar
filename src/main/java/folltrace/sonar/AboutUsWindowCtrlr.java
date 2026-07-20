package folltrace.sonar;

import javafx.fxml.FXML;
import javafx.scene.control.Hyperlink;

import java.awt.Desktop;
import java.net.URI;

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
