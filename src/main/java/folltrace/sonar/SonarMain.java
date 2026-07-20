package folltrace.sonar;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.Objects;

public class SonarMain extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        var loader = new FXMLLoader(getClass().getResource("/folltrace/sonar/player.fxml"));
        var root = (javafx.scene.Parent) loader.load();

        var scene = new Scene(root);
        stage.setScene(scene);
        stage.getIcons().add(new Image(Objects.requireNonNull(getClass().getResourceAsStream("/logo.png"))));
        stage.setResizable(false);
        stage.setTitle("Sonar");
        stage.initStyle(StageStyle.UNDECORATED);

        var controller = (SonarController) loader.getController();
        controller.setScene(scene);
        controller.setPrimaryStage(stage);

        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
