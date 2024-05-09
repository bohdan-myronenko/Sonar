package folltrace.sonar;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.AnchorPane;
import javafx.scene.media.MediaPlayer;
import javafx.stage.Stage;

public class MiniController {
    private SonarController sonarController;
    @FXML
    private Label songLabel;
    @FXML
    private Label artistLabel;
    @FXML
    private Label albumLabel;
    @FXML
    private Label volumeLabel;

    @FXML
    private Button hide_btn;
    @FXML
    private Button unshrink_btn;
    @FXML
    private Button play_pause_btn;
    @FXML
    private Button next_btn;
    @FXML
    private Button prev_btn;
    @FXML
    private Button stop_btn;
    @FXML
    private Button repeat_btn;
    @FXML
    private Button shuffle_btn;

    @FXML
    private Slider miniVolumeSlider;
    @FXML
    private Slider miniSeekSlider;

    @FXML
    private AnchorPane mini_drag_pane;


    private double xOffset = 0;
    private double yOffset = 0;

    @FXML
    private void initialize(){
        miniVolumeSlider.valueProperty().addListener((observable, oldValue, newValue) -> {
            MediaPlayer mediaPlayer = sonarController.getMediaPlayer();
            if (mediaPlayer != null) {
                ((MediaPlayer) mediaPlayer).setVolume(newValue.doubleValue());
            }
            int volumePercent = (int) Math.round(newValue.doubleValue() * 100);
            volumeLabel.setText(volumePercent + "%"); // Update if you have a label

            // Update sound icon based on volume level
            // updateVolumeIcon(newValue.doubleValue() * 100); // Implement this if needed
        });

        mini_drag_pane.setOnMousePressed(event -> {
            xOffset = event.getSceneX();
            yOffset = event.getSceneY();
        });
        mini_drag_pane.setOnMouseDragged(event -> {
            Stage stage = (Stage) mini_drag_pane.getScene().getWindow();
            stage.setX(event.getScreenX() - xOffset);
            stage.setY(event.getScreenY() - yOffset);
        });
        miniSeekSlider.valueChangingProperty().addListener((obs, wasChanging, isChanging) -> {
            if (!isChanging) {
                sonarController.updateMediaPlayerTime(miniSeekSlider.getValue());
            }
        });
    }

    public void updateVolumeSlider(double volume) {
        if (miniVolumeSlider != null) {
            miniVolumeSlider.setValue(volume);
        }
    }

    public void updateSeekSliderMax(double maxDuration) {
        if (miniSeekSlider != null) {
            miniSeekSlider.setMax(maxDuration);
        }
    }

    public void updateSeekSliderValue(double currentTime) {
        if (miniSeekSlider != null) {
            Platform.runLater(() -> {
                miniSeekSlider.setValue(currentTime);
            });
        }
    }

    public void updateSeekSlider(double value) {
        miniSeekSlider.setValue(value);
    }



    public void setSliderMax(double max) {
        miniSeekSlider.setMax(max);
    }
    public void setSonarController(SonarController sonarController) {
        this.sonarController = sonarController;
    }
    public MiniController(){}
    @FXML
    private void handleHideApp() {
        Stage stage = (Stage) songLabel.getScene().getWindow(); // Use any FXML-injected component
        if (stage != null) {
            stage.setIconified(!stage.isIconified());
        }
    }

    @FXML
    private void handleClose() {
        Stage stage = (Stage) songLabel.getScene().getWindow(); // Use any FXML-injected component
        if (stage != null) {
            stage.close();
        }
    }

    @FXML
    private void handleUnshrink() {
        if (sonarController != null) {
            sonarController.showMainWindow();
        }

        Stage miniStage = (Stage) songLabel.getScene().getWindow(); // Assuming songLabel is part of MiniController's FXML
        if (miniStage != null) {
            miniStage.close();
        }
    }

    @FXML
    private void switchPlayPause(){
        sonarController.handleTogglePlayPause();
    }

    @FXML
    private void playNextTrack() {
        sonarController.onNextTrack();
    }

    @FXML
    private void playPreviousTrack() {
        sonarController.onPreviousTrack();
    }

    @FXML
    private void shuffleToggle(){
        sonarController.toggleShuffle();
    }

    @FXML
    private void repeatToggle(){
        sonarController.handleRepeatToggle();
    }
    @FXML
    private void stopPlayback(){
        sonarController.handleStop();
    }
}
