package folltrace.sonar;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.AnchorPane;
import javafx.stage.Stage;

public class MiniController {

    private SonarController sonarController;

    @FXML private Label songLabel;
    @FXML private Label artistLabel;
    @FXML private Label albumLabel;
    @FXML private Label volumeLabel;

    @FXML private Button hide_btn;
    @FXML private Button unshrink_btn;
    @FXML private Button play_pause_btn;
    @FXML private Button next_btn;
    @FXML private Button prev_btn;
    @FXML private Button stop_btn;
    @FXML private Button repeat_btn;
    @FXML private Button shuffle_btn;

    @FXML private Slider miniVolumeSlider;
    @FXML private Slider miniSeekSlider;
    @FXML private AnchorPane mini_drag_pane;

    private double dragOffsetX;
    private double dragOffsetY;

    @FXML
    private void initialize() {
        miniVolumeSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            var mp = sonarController.getMediaPlayer();
            if (mp != null) {
                mp.setVolume(newVal.doubleValue());
            }
            int percent = (int) Math.round(newVal.doubleValue() * 100);
            volumeLabel.setText(percent + "%");
        });

        mini_drag_pane.setOnMousePressed(event -> {
            dragOffsetX = event.getSceneX();
            dragOffsetY = event.getSceneY();
        });
        mini_drag_pane.setOnMouseDragged(event -> {
            var stage = (Stage) mini_drag_pane.getScene().getWindow();
            stage.setX(event.getScreenX() - dragOffsetX);
            stage.setY(event.getScreenY() - dragOffsetY);
        });

        miniSeekSlider.valueChangingProperty().addListener((obs, wasChanging, isChanging) -> {
            if (!isChanging) {
                sonarController.updateMediaPlayerTime(miniSeekSlider.getValue());
            }
        });
    }

    public void setSonarController(SonarController sc) {
        this.sonarController = sc;
    }

    public void setSliderMax(double max) {
        miniSeekSlider.setMax(max);
    }

    public void updateVolumeSlider(double volume) {
        if (miniVolumeSlider != null) {
            miniVolumeSlider.setValue(volume / 100.0);
        }
    }

    public void updateSeekSliderMax(double max) {
        if (miniSeekSlider != null) {
            miniSeekSlider.setMax(max);
        }
    }

    public void updateSeekSliderValue(double time) {
        if (miniSeekSlider != null) {
            Platform.runLater(() -> miniSeekSlider.setValue(time));
        }
    }

    public void updateSeekSlider(double value) {
        miniSeekSlider.setValue(value);
    }

    @FXML
    private void handleHideApp() {
        var stage = (Stage) songLabel.getScene().getWindow();
        if (stage != null) {
            stage.setIconified(!stage.isIconified());
        }
    }

    @FXML
    private void handleClose() {
        var stage = (Stage) songLabel.getScene().getWindow();
        if (stage != null) {
            stage.close();
        }
    }

    @FXML
    private void handleUnshrink() {
        if (sonarController != null) {
            sonarController.showMainWindow();
        }
        var stage = (Stage) songLabel.getScene().getWindow();
        if (stage != null) {
            stage.close();
        }
    }

    @FXML private void switchPlayPause()    { sonarController.handleTogglePlayPause(); }
    @FXML private void playNextTrack()      { sonarController.onNextTrack(); }
    @FXML private void playPreviousTrack()  { sonarController.onPreviousTrack(); }
    @FXML private void shuffleToggle()      { sonarController.toggleShuffle(); }
    @FXML private void repeatToggle()       { sonarController.handleRepeatToggle(); }
    @FXML private void stopPlayback()       { sonarController.handleStop(); }
}
