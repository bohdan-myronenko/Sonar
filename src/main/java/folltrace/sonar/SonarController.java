package folltrace.sonar;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.text.Text;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SonarController implements PlaybackCallback{
    // JAVAFX COMPONENTS
    // BUTTONS

    @FXML
    private Button togglePlayPauseButton;

    @FXML
    private Button toggleRepeatButton;

    @FXML
    private Button toggleShuffleButton;

    @FXML
    private Button stopButton;

    @FXML
    private Button nextButton;

    @FXML
    private Button prevButton;


    // LABELS

    @FXML
    private Label statusLabel;

    @FXML
    private Slider seekSlider;

    @FXML
    private Slider volumeSlider;

    @FXML
    private Label volumeLabel;

    @FXML
    private Text songName;

    @FXML
    private Text albumName;

    @FXML

    private Text authorName;


    // IMAGES
    @FXML
    private ImageView albumCoverImageView;


    //MENU ITEMS

    @FXML
    private MenuItem browseMenuItem;

    @FXML
    private MenuItem playPauseMenuitem;

    @FXML
    private MenuItem stopMenuItem;

    @FXML
    private MenuItem nextTrackMenuItem;

    @FXML
    private MenuItem prevTrackMenuItem;

    @FXML
    private MenuItem repeatOffMenuItem;

    @FXML
    private MenuItem repeatAllMenuItem;

    @FXML
    private MenuItem repeatOneMenuItem;

    @FXML
    private MenuItem aboutUsMenuItem;

    @FXML
    private MenuItem quitMenuItem;


    // LISTS

    @FXML
    private ScrollPane fileScrollPane;

    @FXML
    private ListView<String> fileListView;


    // ...

    private Map<String, String> fileMap = new HashMap<>();

    private RepeatState repeatState = RepeatState.OFF;

    private MediaPlayer mediaPlayer;
    private static final List<String> SUPPORTED_FILE_EXTENSIONS = Arrays.asList(".mp3", ".wav", ".aac", ".m4a");

    @Override
    public RepeatState getRepeatState() {
        return this.repeatState;
    }
    private Playback playback;

    @FXML
    public void initialize() {
        playback = new Playback(this);

        // Load the music file from the resources folder
        String musicPath = "/music.mp3"; // Path relative to the classpath
        Media media = new Media(getClass().getResource(musicPath).toExternalForm());
        mediaPlayer = new MediaPlayer(media);

        // Set initial text for labels and status
        songName.setText("No tracks loaded");
        albumName.setText("No tracks loaded");
        authorName.setText("No tracks loaded");
        statusLabel.setText("No track selected");
        volumeLabel.setText((int) Math.round(volumeSlider.getValue() * 100) + "%");

        // Initialize volume slider listener
        volumeSlider.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (mediaPlayer != null) {
                mediaPlayer.setVolume(newValue.doubleValue());
            }
            int volumePercent = (int) Math.round(newValue.doubleValue() * 100);
            volumeLabel.setText(volumePercent + "%");
        });

        mediaPlayer.setOnReady(() -> {
            seekSlider.setMax(mediaPlayer.getMedia().getDuration().toSeconds());
            updateSongInfo(media);
        });

        mediaPlayer.currentTimeProperty().addListener((obs, oldTime, newTime) -> {
            if (!seekSlider.isValueChanging()) {
                seekSlider.setValue(newTime.toSeconds());
            }
        });

        seekSlider.valueChangingProperty().addListener((obs, wasChanging, isChanging) -> {
            if (!isChanging) {
                mediaPlayer.seek(Duration.seconds(seekSlider.getValue()));
            }
        });

        seekSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (!seekSlider.isValueChanging()) {
                double currentTime = mediaPlayer.getCurrentTime().toSeconds();
                if (Math.abs(currentTime - newValue.doubleValue()) > 0.5) {
                    mediaPlayer.seek(Duration.seconds(newValue.doubleValue()));
                }
            }
        });
    }



    // HANDLERS
    @FXML
    private void handleTogglePlayPause() {
        if (mediaPlayer.getStatus() == MediaPlayer.Status.PLAYING) {
            mediaPlayer.pause();
            togglePlayPauseButton.setText("▶");
            statusLabel.setText("Paused");
        } else {
            mediaPlayer.play();
            togglePlayPauseButton.setText("⏸");
            statusLabel.setText("Playing");
        }
    }

    @FXML
    private void handleStop() {
        mediaPlayer.stop();
        statusLabel.setText("Stopped");
    }


    @FXML
    private void handleBrowse() {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Select Folder");
        File selectedDirectory = directoryChooser.showDialog(null);
        if (selectedDirectory != null) {
            updateFileList(selectedDirectory);
        }
    }


    @FXML
    private void handleRepeatToggle() {
        switch (repeatState) {
            case OFF:
                repeatState = RepeatState.REPEAT_ALL;
                updateRepeatModeUI();
                break;
            case REPEAT_ALL:
                repeatState = RepeatState.REPEAT_ONE;
                updateRepeatModeUI();
                break;
            case REPEAT_ONE:
                repeatState = RepeatState.OFF;
                updateRepeatModeUI();
                break;
        }
    }

    @FXML
    private void handleRepeatOff() {
        repeatState = RepeatState.OFF;
        updateRepeatModeUI();
    }

    @FXML
    private void handleRepeatAll() {
        repeatState = RepeatState.REPEAT_ALL;
        updateRepeatModeUI();
    }

    @FXML
    private void handleRepeatOne() {
        repeatState = RepeatState.REPEAT_ONE;
        updateRepeatModeUI();
    }
    @FXML
    private void handleNext() {
        onNextTrack();
    }

    @FXML
    private void handlePrevious() {
        onPreviousTrack();
    }

    @FXML
    private void handleAboutUs() {
        try {
            // Load the About Us FXML file
            FXMLLoader fxmlLoader = new FXMLLoader(getClass().getResource("/folltrace/sonar/about.fxml"));
            Parent aboutUsRoot = fxmlLoader.load();

            // Create a new Stage for the About Us window
            Stage aboutUsStage = new Stage();
            aboutUsStage.setTitle("About Us");
            aboutUsStage.initModality(Modality.APPLICATION_MODAL); // Makes the window modal
            aboutUsStage.setScene(new Scene(aboutUsRoot));

            // Show the About Us window and wait for it to be closed
            aboutUsStage.showAndWait();
        } catch (Exception e) {
            e.printStackTrace();
            // Handle exceptions (e.g., FXML file not found)
        }
    }

    @FXML
    private void handleQuitAction() {
        // Get the current stage and close it
        Stage stage = (Stage) quitMenuItem.getParentPopup().getOwnerWindow();
        stage.close();
    }


    // METHODS

    private void updateFileList(File folder) {
        File[] files = folder.listFiles();
        if (files != null) {
            fileListView.getItems().clear();
            fileMap.clear();
            for (File file : files) {
                if (file.isFile() && isSupportedFile(file.getName())) {
                    fileListView.getItems().add(file.getName());
                    fileMap.put(file.getName(), file.getAbsolutePath());
                }
            }
        }
        // Add listener to ListView
        fileListView.getSelectionModel().selectedItemProperty().addListener((obs, oldSelection, newSelection) -> {
            if (newSelection != null) {
                playback.playMedia(fileMap.get(newSelection));
            }
        });
    }

    private boolean isSupportedFile(String fileName) {
        for (String extension : SUPPORTED_FILE_EXTENSIONS) {
            if (fileName.toLowerCase().endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private void updateRepeatModeUI() {
        // Update the UI based on the current repeat state
        switch (repeatState) {
            case OFF:
                statusLabel.setText("Repeat: Off");
                toggleRepeatButton.setText("✕");
                break;
            case REPEAT_ALL:
                statusLabel.setText("Repeat: All");
                toggleRepeatButton.setText("🔁");
                break;
            case REPEAT_ONE:
                statusLabel.setText("Repeat: One");
                toggleRepeatButton.setText("🔂");
                break;
        }
    }
    private void updateSongInfo(Media media) {
        if (media.getMetadata().containsKey("title")) {
            songName.setText(media.getMetadata().get("title").toString());
        }
        if (media.getMetadata().containsKey("album")) {
            albumName.setText(media.getMetadata().get("album").toString());
        }
        if (media.getMetadata().containsKey("artist")) {
            authorName.setText(media.getMetadata().get("artist").toString());
        }
        if (media.getMetadata().containsKey("image")) {
            Image coverImage = (Image) media.getMetadata().get("image");
            albumCoverImageView.setImage(coverImage);
        }
    }

    @Override
    public void onMediaReady(MediaPlayer mediaPlayer) {
        this.mediaPlayer = mediaPlayer;

        // Update UI components
        updateUIForMediaPlayer(mediaPlayer);

        updateSongInfo(mediaPlayer.getMedia());
    }

    private void updateUIForMediaPlayer(MediaPlayer mediaPlayer) {
        seekSlider.setMax(mediaPlayer.getMedia().getDuration().toSeconds());

        mediaPlayer.currentTimeProperty().addListener((obs, oldTime, newTime) -> {
            if (!seekSlider.isValueChanging()) {
                seekSlider.setValue(newTime.toSeconds());
            }
        });

        togglePlayPauseButton.setText("⏸");
        statusLabel.setText("Playing");
    }



    @Override
    public void onNextTrack() {
        int currentIndex = fileListView.getSelectionModel().getSelectedIndex();
        int totalTracks = fileListView.getItems().size();

        if (currentIndex < totalTracks - 1) {
            // Move to the next track
            playSelectedTrack(currentIndex + 1);
        } else {
            // At the last track
            if (repeatState == RepeatState.REPEAT_ALL) {
                // Start from the beginning in 'Repeat All' mode
                playSelectedTrack(0);
            } else {
                // If repeat is off or 'Repeat One', stop playback at the end of the playlist
                mediaPlayer.stop();
                statusLabel.setText("Playback stopped");
                fileListView.getSelectionModel().clearSelection();
            }
        }
    }



    @Override
    public void onPreviousTrack() {
        int currentIndex = fileListView.getSelectionModel().getSelectedIndex();
        if (currentIndex > 0) {
            // Go to the previous track
            playSelectedTrack(currentIndex - 1);
        } else {
            // At the beginning of the playlist
            switch (repeatState) {
                case REPEAT_ALL:
                    // Go to the last track
                    playSelectedTrack(fileListView.getItems().size() - 1);
                    break;
                case REPEAT_ONE:
                    // Repeat the current (first) track
                    playSelectedTrack(currentIndex);
                    break;
                default:
                    // If repeat is off, stay on the first track
                    mediaPlayer.stop();
                    statusLabel.setText("Playback stopped");
                    fileListView.getSelectionModel().clearSelection();
                    break;
            }
        }
    }



    private void playSelectedTrack(int index) {
        String trackName = fileListView.getItems().get(index);
        playback.playMedia(fileMap.get(trackName));
        fileListView.getSelectionModel().select(index);
    }
}
