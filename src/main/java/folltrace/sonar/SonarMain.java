package folltrace.sonar;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.stage.Stage;

import java.io.IOException;

public class SonarMain extends Application {

    @Override
    public void start(Stage primaryStage) throws IOException {
        Parent root = FXMLLoader.load(getClass().getResource("/folltrace/sonar/player.fxml"));
        primaryStage.setScene(new Scene(root));
        primaryStage.show();

        // Load the music file from the resources folder
        String musicPath = "/music.mp3"; // Path relative to the classpath
        Media media = new Media(getClass().getResource(musicPath).toExternalForm());
        MediaPlayer mediaPlayer = new MediaPlayer(media);
        mediaPlayer.play(); // Play the music
    }

    public static void main(String[] args) {
        launch(args);
    }
}
