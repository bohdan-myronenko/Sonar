package folltrace.sonar;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.IOException;

public class SonarMain extends Application {

    private double xOffset = 0;
    private double yOffset = 0;
    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/folltrace/sonar/player.fxml"));
        Parent root = loader.load();

        Scene scene = new Scene(root);
        stage.setScene(scene);

        stage.getIcons().add(new Image("/logo.png"));
        stage.setResizable(false);
        stage.setTitle("Sonar");

        // Get the controller and set the primary stage
        SonarController controller = loader.getController();
        controller.setScene(scene);
        controller.setPrimaryStage(stage);  // Pass the stage here

        stage.initStyle(StageStyle.UNDECORATED);
        stage.show();
    }


    public static void main(String[] args) {
        launch(args);
    }
}
