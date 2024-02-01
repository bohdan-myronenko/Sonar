package folltrace.sonar;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;

public class SonarMain extends Application {

    @Override
    public void start(Stage primaryStage) throws IOException {

        // Replace 'resources/MyUI.fxml' with the path to your FXML file
        Parent root = FXMLLoader.load(getClass().getResource("/folltrace/sonar/player.fxml"));
        primaryStage.setScene(new Scene(root));
        primaryStage.show();
        // URL of the audio file
        String filePath = "D:\\User Files\\ProgFiles\\Java\\Sonar\\music.mp3";
    }

    public static void main(String[] args) {
        launch(args);
    }
}
