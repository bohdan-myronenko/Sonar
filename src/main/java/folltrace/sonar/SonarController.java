package folltrace.sonar;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.text.Text;
import javafx.stage.DirectoryChooser;
import javafx.util.Duration;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SonarController implements PlaybackCallback{
    private static final List<String> SUPPORTED_FILE_EXTENSIONS = Arrays.asList(".mp3", ".wav", ".aac", ".m4a");


    private Playback playback;
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

    @FXML
    private ImageView albumCoverImageView;

    @FXML
    private MenuItem browseMenuItem;

    @FXML
    private MenuItem playPauseMenuitem;

    @FXML
    private MenuItem stopMenuItem;

    @FXML
    private ScrollPane fileScrollPane;

    @FXML
    private ListView<String> fileListView;

    private Map<String, String> fileMap = new HashMap<>();


    private MediaPlayer mediaPlayer;

    @FXML
    public void initialize() {
        playback = new Playback(this);
        String filePath = "D:\\User Files\\ProgFiles\\Java\\Sonar\\music.mp3";
        Media media = new Media(new File(filePath).toURI().toString());
        mediaPlayer = new MediaPlayer(media);

        // Example of setting initial label text
        statusLabel.setText("Ready to play");
        mediaPlayer.volumeProperty().bind(volumeSlider.valueProperty());

        // Add a listener to the slider's value property
        volumeSlider.valueProperty().addListener((observable, oldValue, newValue) -> {
            int volumePercent = (int) Math.round(newValue.doubleValue() * 100);
            volumeLabel.setText(volumePercent + "%");
        });

        // Initialize the label with the current volume
        volumeLabel.setText((int) Math.round(volumeSlider.getValue() * 100) + "%");

        mediaPlayer.setOnReady(() -> {
            seekSlider.setMax(mediaPlayer.getMedia().getDuration().toSeconds());
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

        mediaPlayer.setOnReady(() -> {
            seekSlider.setMax(mediaPlayer.getMedia().getDuration().toSeconds());
            updateSongInfo(media);
        });
    }

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
        this.mediaPlayer = mediaPlayer; // Assign the new MediaPlayer instance

        seekSlider.setMax(mediaPlayer.getMedia().getDuration().toSeconds());
        mediaPlayer.volumeProperty().bind(volumeSlider.valueProperty());
        mediaPlayer.currentTimeProperty().addListener((obs, oldTime, newTime) -> {
            if (!seekSlider.isValueChanging()) {
                seekSlider.setValue(newTime.toSeconds());
            }
        });

        togglePlayPauseButton.setText("⏸");
        statusLabel.setText("Playing");

        updateSongInfo(mediaPlayer.getMedia());
    }

    @Override
    public void onNextTrack() {
        int currentIndex = fileListView.getSelectionModel().getSelectedIndex();
        if (currentIndex < fileListView.getItems().size() - 1) {
            String nextTrackName = fileListView.getItems().get(currentIndex + 1);
            playback.playMedia(fileMap.get(nextTrackName));
            fileListView.getSelectionModel().select(currentIndex + 1);
        }
    }

    @Override
    public void onPreviousTrack() {
        int currentIndex = fileListView.getSelectionModel().getSelectedIndex();
        if (currentIndex > 0) {
            String previousTrackName = fileListView.getItems().get(currentIndex - 1);
            playback.playMedia(fileMap.get(previousTrackName));
            fileListView.getSelectionModel().select(currentIndex - 1);
        }
    }
}
