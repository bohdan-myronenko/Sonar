package folltrace.sonar;

import com.mpatric.mp3agic.ID3v2;
import com.mpatric.mp3agic.InvalidDataException;
import com.mpatric.mp3agic.Mp3File;
import com.mpatric.mp3agic.UnsupportedTagException;
import javafx.embed.swing.SwingFXUtils;
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
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.mp3.Mp3Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.ContentHandler;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.util.*;




public class SonarController implements PlayerCallback {
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

    @FXML
    private Button deleteButton;


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
    private MenuItem scanFolderMenuItem;

    @FXML
    private MenuItem scanFileMenuItem;

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

    @FXML
    private MenuItem deleteFromPlaylistMenuItem;


    // LISTS

    @FXML
    private ScrollPane fileScrollPane;

    @FXML
    private ListView<Track> fileListView;


    // ...


    private List<String> playlist = new ArrayList<>();
    private Map<String, String> trackNamesToPaths = new HashMap<>();
    private Map<String, String> fileMap = new HashMap<>();

    private RepeatState repeatState = RepeatState.OFF;

    private MediaPlayer mediaPlayer;
    private static final List<String> SUPPORTED_FILE_EXTENSIONS = Arrays.asList(".mp3", ".wav", ".aac", ".m4a");

    @Override
    public RepeatState getRepeatState() {
        return this.repeatState;
    }
    private Player player;

    @FXML
    public void initialize() {
        player = new Player(this);

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

        fileListView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                Track selectedTrack = fileListView.getSelectionModel().getSelectedItem();
                String selectedTrackName = selectedTrack.getName();
                if (selectedTrackName != null) {
                    String selectedTrackPath = trackNamesToPaths.get(selectedTrackName);
                    playSelectedTrack(playlist.indexOf(selectedTrackPath));
                }
            }
        });

        fileListView.setCellFactory(lv -> new ListCell<Track>() {
            @Override
            protected void updateItem(Track track, boolean empty) {
                super.updateItem(track, empty);
                if (empty || track == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(track.getName() + " - " + track.getDuration());
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
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            statusLabel.setText("Stopped");
        }
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
    private void handleAddFileToPlaylist() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Music File");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Audio Files", "*.mp3", "*.wav", "*.aac", "*.m4a")
        );

        Stage stage = (Stage) fileListView.getScene().getWindow();
        File file = fileChooser.showOpenDialog(stage);

        addFileToPlaylist(file);
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
            aboutUsStage.getIcons().add(new Image("/logo.png"));
            aboutUsStage.setTitle("About Us");
            aboutUsStage.initModality(Modality.APPLICATION_MODAL); // Makes the window modal
            aboutUsStage.setScene(new Scene(aboutUsRoot));

            // Show the About Us window and wait for it to be closed
            aboutUsStage.showAndWait();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void handleQuitAction() {
        // Get the current stage and close it
        Stage stage = (Stage) quitMenuItem.getParentPopup().getOwnerWindow();
        stage.close();
    }

    @FXML
    private void handleDeleteFromPlaylist() {
        int selectedIndex = fileListView.getSelectionModel().getSelectedIndex();
        if (selectedIndex != -1) {
            Track selectedTrack = fileListView.getSelectionModel().getSelectedItem();
            String selectedTrackName = selectedTrack.getName();
            String selectedTrackPath = trackNamesToPaths.get(selectedTrackName);

            boolean isPlayingTrackDeleted = mediaPlayer != null &&
                    mediaPlayer.getMedia().getSource().equals(new File(selectedTrackPath).toURI().toString());

            // Remove the track from the ListView, track map, and playlist
            fileListView.getItems().remove(selectedIndex);
            trackNamesToPaths.remove(selectedTrackName);
            playlist.remove(selectedTrackPath);

            if (isPlayingTrackDeleted) {
                if (repeatState == RepeatState.REPEAT_ONE) {
                    // Check if there are other tracks in the playlist
                    if (!playlist.isEmpty()) {
                        // Play the next track in the playlist or start over
                        int nextIndex = (selectedIndex < playlist.size()) ? selectedIndex : 0;
                        playSelectedTrack(nextIndex);
                    } else {
                        // No more tracks to play
                        mediaPlayer.stop();
                        statusLabel.setText("Playback stopped");
                    }
                } else {
                    // Handle other repeat states
                    player.stopMedia(); // Stop the media player
                    if (selectedIndex < playlist.size()) {
                        playSelectedTrack(selectedIndex); // Play next track
                    } else if (repeatState == RepeatState.REPEAT_ALL && !playlist.isEmpty()) {
                        playSelectedTrack(0); // Play first track in 'Repeat All' mode
                    } else {
                        mediaPlayer.stop();
                        statusLabel.setText("Playback stopped");
                    }
                }
            }
        }
    }








    // METHODS

    private void addFileToPlaylist(File file) {
        if (file != null && isSupportedFile(file.getName())) {
            String trackDuration = getTrackDuration(file);
            Track track = new Track(file.getName(), trackDuration); // Use actual duration
            fileListView.getItems().add(track);

            // Update the map with track name to file path mapping
            trackNamesToPaths.put(file.getName(), file.getAbsolutePath());

            // Add the file path to the playlist for playback
            playlist.add(file.getAbsolutePath());
        }
    }



    private void updateFileList(File folder) {
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                addFileToPlaylist(file);
            }
        }
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
    public void updateSongInfo(Media media) {
        if (media.getMetadata().containsKey("title")) {
            songName.setText(media.getMetadata().get("title").toString());
        }
        if (media.getMetadata().containsKey("album")) {
            albumName.setText(media.getMetadata().get("album").toString());
        }
        if (media.getMetadata().containsKey("artist")) {
            authorName.setText(media.getMetadata().get("artist").toString());
        }

        String sourceUrl = media.getSource();
        URI uri = null;
        try {
            uri = new URI(sourceUrl);
        } catch (Exception e) {
            e.printStackTrace();
        }
        File file = new File(uri);

        // Get the album cover as BufferedImage
        BufferedImage albumCover;
        try {
            albumCover = getAlbumCover(file);
            if (albumCover != null) {
                // Convert BufferedImage to javafx.scene.image.Image
                Image coverImage = SwingFXUtils.toFXImage(albumCover, null);
                albumCoverImageView.setImage(coverImage);
            } else {
                // Set a default image or clear the existing image
                albumCoverImageView.setImage(null);
            }
        } catch (Exception e) {
            e.printStackTrace();
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
        int nextIndex = currentIndex + 1;

        if (nextIndex < playlist.size()) {
            playSelectedTrack(nextIndex);
        } else if (repeatState == RepeatState.REPEAT_ALL) {
            playSelectedTrack(0); // Start from the beginning
        } else {
            // Playback stopped, no repeat
            mediaPlayer.stop();
            statusLabel.setText("Playback stopped");
        }
    }



    @Override
    public void onPreviousTrack() {
        int currentIndex = fileListView.getSelectionModel().getSelectedIndex();
        int previousIndex = currentIndex - 1;

        if (previousIndex >= 0) {
            // Normal case: Go to the previous track
            playSelectedTrack(previousIndex);
        } else {
            // Special case: If at the first track, go to the last track in the playlist
            playSelectedTrack(playlist.size() - 1);
        }
    }




    private void playSelectedTrack(int index) {
        if (index >= 0 && index < playlist.size()) {
            String filePath = playlist.get(index);
            player.playMedia(filePath);
            fileListView.getSelectionModel().select(index);
        }
    }





    public String getTrackDuration(File file) {
        try (InputStream input = new FileInputStream(file)) {
            ContentHandler handler = new BodyContentHandler();
            Metadata metadata = new Metadata();
            new Mp3Parser().parse(input, handler, metadata, new ParseContext());

            String durationStr = metadata.get("xmpDM:duration");
            if (durationStr != null) {
                // Parse the duration as a floating-point number and convert to milliseconds
                double durationSecs = Double.parseDouble(durationStr);
                long durationMs = (long) (durationSecs * 1000);
                return formatDuration(durationMs);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "Unknown";
    }

    private String formatDuration(long durationMs) {
        long seconds = durationMs / 1000;
        long minutes = seconds / 60;
        seconds %= 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    public BufferedImage getAlbumCover(File file) {
        if (!file.exists()) {
            System.out.println("File not found: " + file.getAbsolutePath());
            return null;
        }

        try {
            Mp3File mp3file = new Mp3File(file.getAbsolutePath());
            if (mp3file.hasId3v2Tag()) {
                ID3v2 id3v2tag = mp3file.getId3v2Tag();
                byte[] imageData = id3v2tag.getAlbumImage();
                if (imageData != null) {
                    try (ByteArrayInputStream bis = new ByteArrayInputStream(imageData)) {
                        return ImageIO.read(bis);
                    }
                }
            }
        } catch (IOException | UnsupportedTagException | InvalidDataException e) {
            e.printStackTrace();
        }
        return null;
    }

}
