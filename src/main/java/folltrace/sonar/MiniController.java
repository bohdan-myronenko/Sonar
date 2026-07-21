package folltrace.sonar;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.ImageView;
import javafx.scene.layout.AnchorPane;
import javafx.scene.media.MediaPlayer;
import javafx.stage.Stage;

public class MiniController {

    private SonarController sonarController;

    @FXML private Label songLabel;
    @FXML private Label artistLabel;
    @FXML private Label albumLabel;
    @FXML private Label volumeLabel;
    @FXML private Label currentTimeLabel;
    @FXML private ImageView miniAlbumArt;

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

    private Stage miniStage;

    @FXML
    private void initialize() {
        setupButtonIcons();

        // Volume changes from mini slider → propagate to media player
        miniVolumeSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            var mp = sonarController.getMediaPlayer();
            if (mp != null) {
                mp.setVolume(newVal.doubleValue());
            }
            int percent = (int) Math.round(newVal.doubleValue() * 100);
            volumeLabel.setText(percent + "%");
            // Propagate to main window
            sonarController.onVolumeChanged(percent);
        });

        // Drag-to-move window
        mini_drag_pane.setOnMousePressed(event -> {
            dragOffsetX = event.getSceneX();
            dragOffsetY = event.getSceneY();
        });
        mini_drag_pane.setOnMouseDragged(event -> {
            if (miniStage == null) {
                miniStage = (Stage) mini_drag_pane.getScene().getWindow();
            }
            miniStage.setX(event.getScreenX() - dragOffsetX);
            miniStage.setY(event.getScreenY() - dragOffsetY);
        });

        // Seek via mini slider — drag release
        miniSeekSlider.valueChangingProperty().addListener((obs, wasChanging, isChanging) -> {
            if (!isChanging) {
                sonarController.updateMediaPlayerTime(miniSeekSlider.getValue());
            }
        });

        // Seek via mini slider — click on track (not during a drag)
        miniSeekSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (!miniSeekSlider.isValueChanging()) {
                var mp = sonarController.getMediaPlayer();
                if (mp != null) {
                    double current = mp.getCurrentTime().toSeconds();
                    if (Math.abs(current - newVal.doubleValue()) > 0.5) {
                        sonarController.updateMediaPlayerTime(newVal.doubleValue());
                    }
                }
            }
        });
    }

    // ── Button icon setup (mirrors SonarController's setupButtonIcons) ────────

    private void setupButtonIcons() {
        stop_btn.setText(null);
        UIManager.setImageToButton(stop_btn, "/icons/stop_solid.png", 16, 16);

        next_btn.setText(null);
        UIManager.setHoverEffectToButton(next_btn, "/icons/next.png", "/icons/next_solid.png", 16, 16);

        prev_btn.setText(null);
        UIManager.setHoverEffectToButton(prev_btn, "/icons/prev.png", "/icons/prev_solid.png", 16, 16);

        // Play/Pause starts with play icon; updated by updatePlayPauseButton()
        play_pause_btn.setText(null);
        UIManager.setHoverEffectToButton(play_pause_btn, "/icons/play.png", "/icons/play_solid.png", 16, 16);

        // Repeat / Shuffle start with off icons; updated by SonarController pushes
        repeat_btn.setText(null);
        UIManager.setImageToButton(repeat_btn, "/icons/off.png", 16, 16);

        shuffle_btn.setText(null);
        UIManager.setImageToButton(shuffle_btn, "/icons/off.png", 16, 16);
    }

    public void setSonarController(SonarController sc) {
        this.sonarController = sc;
    }

    // ---- Pull current state from the main controller (called when mini opens) ----

    public void pullStateFromMain() {
        var mp = sonarController.getMediaPlayer();
        if (mp == null || mp.getMedia() == null) return;

        // Track info
        var meta = mp.getMedia().getMetadata();
        songLabel.setText(meta.containsKey("title")  ? meta.get("title").toString()  : "Unknown");
        artistLabel.setText(meta.containsKey("artist") ? meta.get("artist").toString() : "Unknown");
        albumLabel.setText(meta.containsKey("album")  ? meta.get("album").toString()  : "Unknown");

        // Seek slider
        double duration = mp.getMedia().getDuration().toSeconds();
        if (duration > 0) {
            miniSeekSlider.setMax(duration);
            miniSeekSlider.setValue(mp.getCurrentTime().toSeconds());
        }

        // Volume
        miniVolumeSlider.setValue(mp.getVolume());
        volumeLabel.setText((int) Math.round(mp.getVolume() * 100) + "%");

        // Current time
        updateCurrentTimeDisplay(mp);

        // Album art
        var mainArt = sonarController.getAlbumCoverImageView();
        if (mainArt != null && mainArt.getImage() != null) {
            miniAlbumArt.setImage(mainArt.getImage());
        }

        // Play/pause button state
        updatePlayPauseButton(mp.getStatus() == MediaPlayer.Status.PLAYING);

        // Repeat / Shuffle state
        updateRepeatIcon(sonarController.getRepeatState());
        updateShuffleIcon(sonarController.getShuffleState());
    }

    // ---- Push updates from main controller (called during playback) ----

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

    public void updateSeekSlider(double value) {
        if (miniSeekSlider != null) {
            Platform.runLater(() -> miniSeekSlider.setValue(value));
        }
    }

    public void updateCurrentTimeDisplay(MediaPlayer mp) {
        if (mp == null) return;
        var t = mp.getCurrentTime();
        int min = (int) t.toMinutes();
        int sec = (int) t.toSeconds() % 60;
        currentTimeLabel.setText(String.format("%02d:%02d", min, sec));
    }

    public void updatePlayPauseButton(boolean playing) {
        if (playing) {
            UIManager.setHoverEffectToButton(play_pause_btn, "/icons/pause.png", "/icons/pause_solid.png", 16, 16);
        } else {
            UIManager.setHoverEffectToButton(play_pause_btn, "/icons/play.png", "/icons/play_solid.png", 16, 16);
        }
    }

    public void updateRepeatIcon(RepeatState state) {
        switch (state) {
            case OFF        -> UIManager.setImageToButton(repeat_btn, "/icons/off.png", 16, 16);
            case REPEAT_ALL -> UIManager.setImageToButton(repeat_btn, "/icons/repeat_all.png", 16, 16);
            case REPEAT_ONE -> UIManager.setImageToButton(repeat_btn, "/icons/repeat_one.png", 16, 16);
        }
    }

    public void updateShuffleIcon(ShuffleState state) {
        switch (state) {
            case OFF         -> UIManager.setImageToButton(shuffle_btn, "/icons/off.png", 16, 16);
            case SHUFFLE_ALL, SHUFFLE_NEXT -> UIManager.setImageToButton(shuffle_btn, "/icons/shuffle.png", 16, 16);
        }
    }

    public void updateTrackInfo(String title, String artist, String album) {
        songLabel.setText(title);
        artistLabel.setText(artist);
        albumLabel.setText(album);
    }

    public ImageView getAlbumArtView() {
        return miniAlbumArt;
    }

    // ---- Window control handlers ----

    @FXML
    private void handleHideApp() {
        if (miniStage == null) {
            miniStage = (Stage) songLabel.getScene().getWindow();
        }
        miniStage.setIconified(!miniStage.isIconified());
    }

    @FXML
    private void handleClose() {
        // Close mini and restore main window
        sonarController.showMainWindow();
        if (miniStage == null) {
            miniStage = (Stage) songLabel.getScene().getWindow();
        }
        miniStage.close();
    }

    @FXML
    private void handleUnshrink() {
        sonarController.showMainWindow();
        if (miniStage == null) {
            miniStage = (Stage) songLabel.getScene().getWindow();
        }
        miniStage.close();
    }

    // ---- Playback control handlers ----

    @FXML private void switchPlayPause()   { sonarController.handleTogglePlayPause(); }
    @FXML private void playNextTrack()     { sonarController.onNextTrack(); }
    @FXML private void playPreviousTrack() { sonarController.onPreviousTrack(); }
    @FXML private void shuffleToggle()     { sonarController.toggleShuffle(); }
    @FXML private void repeatToggle()      { sonarController.handleRepeatToggle(); }
    @FXML private void stopPlayback()      { sonarController.handleStop(); }
}
