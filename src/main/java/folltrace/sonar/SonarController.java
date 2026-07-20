package folltrace.sonar;

import com.mpatric.mp3agic.InvalidDataException;
import com.mpatric.mp3agic.Mp3File;
import com.mpatric.mp3agic.UnsupportedTagException;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.stage.*;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.util.*;

public class SonarController implements PlayerCallback {

    // ---- FXML-injected fields ----

    @FXML private Button togglePlayPauseButton;
    @FXML private Button toggleRepeatButton;
    @FXML private Button shuffleButton;
    @FXML private Button stopButton;
    @FXML private Button nextButton;
    @FXML private Button prevButton;
    @FXML private Button deleteButton;
    @FXML private Button savePlaylistButton;
    @FXML private Button loadPlaylistButton;
    @FXML private Button closeAppButton;
    @FXML private Button minimizeButton;
    @FXML private Button hideButton;

    @FXML private Label statusLabel;
    @FXML private Slider seekSlider;
    @FXML private Slider volumeSlider;
    @FXML private Label volumeLabel;
    @FXML private Label currentTimeLabel;
    @FXML private Label songName;
    @FXML private Label albumName;
    @FXML private Label authorName;

    @FXML private ImageView albumCoverImageView;
    @FXML private ImageView soundIcon;

    @FXML private MenuItem scanFolderMenuItem;
    @FXML private MenuItem scanFileMenuItem;
    @FXML private MenuItem playPauseMenuitem;
    @FXML private MenuItem stopMenuItem;
    @FXML private MenuItem nextTrackMenuItem;
    @FXML private MenuItem prevTrackMenuItem;
    @FXML private MenuItem repeatOffMenuItem;
    @FXML private MenuItem repeatAllMenuItem;
    @FXML private MenuItem repeatOneMenuItem;
    @FXML private MenuItem shuffleAllMenuItem;
    @FXML private MenuItem shuffleNextMenuItem;
    @FXML private MenuItem shuffleOffMenuItem;
    @FXML private MenuItem aboutUsMenuItem;
    @FXML private MenuItem quitMenuItem;
    @FXML private MenuItem deleteFromPlaylistMenuItem;
    @FXML private MenuItem savePlaylistMenuItem;
    @FXML private MenuItem loadPlaylistMenuItem;
    @FXML private CheckMenuItem darkThemeCheck;
    @FXML private CheckMenuItem minimisedCheck;

    @FXML private ScrollPane fileScrollPane;
    @FXML private ListView<Track> fileListView;
    @FXML private HBox titleBar;
    @FXML private AnchorPane dragPane;

    // ---- Internal state ----

    private Scene scene;
    private Stage primaryStage;
    private Timeline timeline;

    private final List<String> playlist = new ArrayList<>();
    private final List<String> originalPlaylist = new ArrayList<>();
    private final Map<String, String> trackNamesToPaths = new HashMap<>();
    private RepeatState repeatState = RepeatState.OFF;
    private ShuffleState shuffleState = ShuffleState.OFF;

    private double previousVolume = 0.5;
    private double dragOffsetX;
    private double dragOffsetY;

    private MediaPlayer mediaPlayer;
    private Player player;
    private MiniController miniController;
    private String currentlyPlayingTrackPath;

    private static final List<String> SUPPORTED_EXTENSIONS =
            List.of(".mp3", ".wav", ".aac", ".m4a");

    // ---- PlayerCallback ----

    @Override
    public RepeatState getRepeatState() {
        return repeatState;
    }

    @Override
    public void onMediaReady(MediaPlayer mp) {
        mediaPlayer = mp;
        updateUIForMediaPlayer(mp);
        updateSongInfo(mp.getMedia());
        if (miniController != null) {
            miniController.updateSeekSliderMax(mp.getMedia().getDuration().toSeconds());
        }
    }

    @Override
    public void onNextTrack() {
        int currentIndex = fileListView.getSelectionModel().getSelectedIndex();
        int nextIndex = currentIndex + 1;

        if (nextIndex < playlist.size()) {
            playSelectedTrack(nextIndex);
        } else if (repeatState == RepeatState.REPEAT_ALL) {
            playSelectedTrack(0);
        } else {
            mediaPlayer.stop();
            timeline.stop();
            statusLabel.setText("Playback stopped");
            updateCurrentTime();
        }
    }

    @Override
    public void onPreviousTrack() {
        int currentIndex = fileListView.getSelectionModel().getSelectedIndex();
        int prevIndex = currentIndex - 1;
        if (prevIndex >= 0) {
            playSelectedTrack(prevIndex);
        } else if (!playlist.isEmpty()) {
            playSelectedTrack(playlist.size() - 1);
        }
    }

    // ---- Setters ----

    public void setScene(Scene scene) {
        this.scene = scene;
    }

    public void setPrimaryStage(Stage stage) {
        this.primaryStage = stage;
    }

    public void setMiniController(MiniController mc) {
        this.miniController = mc;
    }

    public MediaPlayer getMediaPlayer() {
        return player != null ? player.getMediaPlayer() : null;
    }

    // ---- Initialization ----

    @FXML
    public void initialize() {
        player = new Player(this);

        // JavaFX Media cannot play jar:/resource: URLs, so extract bundled track to a temp file
        var defaultMusicResource = getClass().getResourceAsStream("/music.mp3");
        if (defaultMusicResource != null) {
            try {
                var tempFile = File.createTempFile("sonar_default_", ".mp3");
                tempFile.deleteOnExit();
                try (var out = new FileOutputStream(tempFile)) {
                    defaultMusicResource.transferTo(out);
                }
                mediaPlayer = new MediaPlayer(new Media(tempFile.toURI().toString()));
            } catch (IOException e) {
                mediaPlayer = new MediaPlayer(new Media(new File("").toURI().toString()));
            }
        } else {
            mediaPlayer = new MediaPlayer(new Media(new File("").toURI().toString()));
        }

        setupButtonIcons();
        setupInitialLabels();
        setupDragHandling();
        setupVolumeSlider();
        setupMediaPlayerListeners();
        setupSeekSlider();
        setupFileListView();
        setupDragAndDrop();
        setupSoundIconToggle();

        timeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            updateSliders();
            updateCurrentTime();
        }));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
    }

    private void setupButtonIcons() {
        nextButton.setText(null);
        UIManager.setHoverEffectToButton(nextButton, "/icons/next.png", "/icons/next_solid.png", 15, 15);
        prevButton.setText(null);
        UIManager.setHoverEffectToButton(prevButton, "/icons/prev.png", "/icons/prev_solid.png", 15, 15);
        shuffleButton.setText(null);
        UIManager.setImageToButton(shuffleButton, "/icons/shuffle.png", 15, 15);
        toggleRepeatButton.setText(null);
        UIManager.setImageToButton(toggleRepeatButton, "/icons/repeat_all.png", 15, 15);
        togglePlayPauseButton.setText(null);
        UIManager.setHoverEffectToButton(togglePlayPauseButton, "/icons/play.png", "/icons/play_solid.png", 15, 15);
        stopButton.setText(null);
        UIManager.setHoverEffectToButton(stopButton, "/icons/stop.png", "/icons/stop_solid.png", 15, 15);
        deleteButton.setText(null);
        UIManager.setImageToButton(deleteButton, "/icons/delete.png", 24, 24);
        savePlaylistButton.setText(null);
        UIManager.setImageToButton(savePlaylistButton, "/icons/save.png", 24, 24);
        loadPlaylistButton.setText(null);
        UIManager.setImageToButton(loadPlaylistButton, "/icons/load.png", 24, 24);
    }

    private void setupInitialLabels() {
        songName.setText("No tracks loaded");
        albumName.setText("No tracks loaded");
        authorName.setText("No tracks loaded");
        statusLabel.setText("No track selected");
        volumeLabel.setText((int) Math.round(volumeSlider.getValue() * 100) + "%");
    }

    private void setupDragHandling() {
        dragPane.setOnMousePressed(event -> {
            dragOffsetX = event.getSceneX();
            dragOffsetY = event.getSceneY();
        });
        dragPane.setOnMouseDragged(event -> {
            var stage = (Stage) dragPane.getScene().getWindow();
            stage.setX(event.getScreenX() - dragOffsetX);
            stage.setY(event.getScreenY() - dragOffsetY);
        });
    }

    private void setupVolumeSlider() {
        volumeSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (mediaPlayer != null) {
                mediaPlayer.setVolume(newVal.doubleValue());
            }
            int percent = (int) Math.round(newVal.doubleValue() * 100);
            volumeLabel.setText(percent + "%");
            updateVolumeIcon(newVal.doubleValue() * 100);
        });
    }

    private void setupMediaPlayerListeners() {
        mediaPlayer.setOnReady(() -> {
            seekSlider.setMax(mediaPlayer.getMedia().getDuration().toSeconds());
            updateSongInfo(mediaPlayer.getMedia());
        });

        mediaPlayer.currentTimeProperty().addListener((obs, oldTime, newTime) -> {
            double currentTime = newTime.toSeconds();
            seekSlider.setValue(currentTime);
            if (miniController != null) {
                Platform.runLater(() -> miniController.updateSeekSlider(currentTime));
            }
        });
    }

    private void setupSeekSlider() {
        seekSlider.valueChangingProperty().addListener((obs, wasChanging, isChanging) -> {
            if (!isChanging) {
                mediaPlayer.seek(Duration.seconds(seekSlider.getValue()));
            }
        });
        seekSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (!seekSlider.isValueChanging()) {
                double current = mediaPlayer.getCurrentTime().toSeconds();
                if (Math.abs(current - newVal.doubleValue()) > 0.5) {
                    mediaPlayer.seek(Duration.seconds(newVal.doubleValue()));
                }
            }
        });
    }

    private void setupFileListView() {
        fileListView.setCellFactory(lv -> {
            var cell = new ListCell<Track>() {
                @Override
                protected void updateItem(Track track, boolean empty) {
                    super.updateItem(track, empty);
                    if (empty || track == null) {
                        setText(null);
                        setGraphic(null);
                    } else {
                        setText(track.name() + " - " + track.duration());
                    }
                }
            };
            return cell;
        });

        fileListView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                Track selected = fileListView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    String path = trackNamesToPaths.get(selected.name());
                    if (path != null) {
                        playSelectedTrack(playlist.indexOf(path));
                    }
                }
            }
        });
    }

    private void setupDragAndDrop() {
        // Internal drag-and-drop for reordering
        fileListView.setCellFactory(lv -> {
            var cell = new ListCell<Track>() {
                @Override
                protected void updateItem(Track track, boolean empty) {
                    super.updateItem(track, empty);
                    if (empty || track == null) {
                        setText(null);
                        setGraphic(null);
                    } else {
                        setText(track.name() + " - " + track.duration());
                    }
                }
            };

            cell.setOnDragDetected(event -> {
                if (!cell.isEmpty()) {
                    var db = cell.startDragAndDrop(TransferMode.MOVE);
                    var cc = new ClipboardContent();
                    cc.putString(Integer.toString(cell.getIndex()));
                    db.setContent(cc);
                }
            });

            cell.setOnDragOver(event -> {
                if (event.getDragboard().hasString()) {
                    event.acceptTransferModes(TransferMode.MOVE);
                }
            });

            cell.setOnDragDropped(event -> {
                var db = event.getDragboard();
                if (db.hasString()) {
                    int draggedIdx = Integer.parseInt(db.getString());
                    int thisIdx = cell.getIndex();

                    var dragged = fileListView.getItems().remove(draggedIdx);
                    fileListView.getItems().add(thisIdx, dragged);

                    String draggedPath = playlist.remove(draggedIdx);
                    playlist.add(thisIdx, draggedPath);

                    event.setDropCompleted(true);
                    fileListView.getSelectionModel().select(thisIdx);
                    event.consume();
                }
            });
            return cell;
        });

        // External file drag-and-drop
        fileListView.setOnDragOver(event -> {
            if (event.getGestureSource() != fileListView && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
            }
            event.consume();
        });

        fileListView.setOnDragDropped(event -> {
            var db = event.getDragboard();
            boolean success = false;
            if (db.hasFiles()) {
                success = true;
                for (File file : db.getFiles()) {
                    addFileToPlaylist(file);
                }
            }
            event.setDropCompleted(success);
            event.consume();
        });
    }

    private void setupSoundIconToggle() {
        soundIcon.setOnMouseClicked(event -> {
            if (mediaPlayer != null) {
                if (mediaPlayer.getVolume() > 0) {
                    previousVolume = mediaPlayer.getVolume();
                    mediaPlayer.setVolume(0);
                    volumeSlider.setValue(0);
                    updateVolumeIcon(0);
                } else {
                    mediaPlayer.setVolume(previousVolume);
                    volumeSlider.setValue(previousVolume);
                    updateVolumeIcon(previousVolume * 100);
                }
            }
        });
    }

    // ---- FXML handler methods ----

    @FXML
    protected void handleTogglePlayPause() {
        if (mediaPlayer.getStatus() == MediaPlayer.Status.PLAYING) {
            mediaPlayer.pause();
            timeline.pause();
            updatePlayPauseButton(false);
        } else {
            mediaPlayer.play();
            timeline.play();
            updatePlayPauseButton(true);
        }
    }

    @FXML
    void handleStop() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            timeline.stop();
            currentTimeLabel.setText("--:--");
            statusLabel.setText("Stopped");
        }
    }

    @FXML
    private void handleBrowse() {
        var chooser = new DirectoryChooser();
        chooser.setTitle("Select Folder");
        File dir = chooser.showDialog(null);
        if (dir != null) {
            updateFileList(dir);
        }
    }

    @FXML
    private void handleAddFileToPlaylist() {
        var chooser = new FileChooser();
        chooser.setTitle("Select Music File");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Audio Files", "*.mp3", "*.wav", "*.aac", "*.m4a"));
        File file = chooser.showOpenDialog((Stage) fileListView.getScene().getWindow());
        addFileToPlaylist(file);
    }

    @FXML
    protected void handleRepeatToggle() {
        repeatState = switch (repeatState) {
            case OFF -> RepeatState.REPEAT_ALL;
            case REPEAT_ALL -> RepeatState.REPEAT_ONE;
            case REPEAT_ONE -> RepeatState.OFF;
        };
        updateRepeatModeUI();
    }

    @FXML private void handleRepeatOff()   { repeatState = RepeatState.OFF;        updateRepeatModeUI(); }
    @FXML private void handleRepeatAll()   { repeatState = RepeatState.REPEAT_ALL;  updateRepeatModeUI(); }
    @FXML private void handleRepeatOne()   { repeatState = RepeatState.REPEAT_ONE;  updateRepeatModeUI(); }

    @FXML
    protected void handleShuffleAll() {
        shuffleState = ShuffleState.SHUFFLE_ALL;
        shufflePlaylistAll();
        updateShuffleModeUI();
    }

    @FXML
    private void handleShuffleNext() {
        shuffleState = ShuffleState.SHUFFLE_NEXT;
        shufflePlaylistNext();
        updateShuffleModeUI();
    }

    @FXML
    protected void handleShuffleOff() {
        shuffleState = ShuffleState.OFF;
        restoreOriginalOrder();
        updateListView();
        selectCurrentlyPlayingTrack();
        updateShuffleModeUI();
    }

    public void toggleShuffle() {
        if (shuffleState == ShuffleState.OFF) {
            shuffleState = ShuffleState.SHUFFLE_ALL;
            shufflePlaylistAll();
        } else {
            shuffleState = ShuffleState.OFF;
            restoreOriginalOrder();
            updateListView();
            selectCurrentlyPlayingTrack();
        }
        updateShuffleModeUI();
    }

    @FXML private void handleNext()     { onNextTrack(); }
    @FXML private void handlePrevious() { onPreviousTrack(); }

    @FXML
    private void handleAboutUs() {
        try {
            var loader = new FXMLLoader(getClass().getResource("/folltrace/sonar/about.fxml"));
            var stage = new Stage();
            stage.getIcons().add(new Image(Objects.requireNonNull(getClass().getResourceAsStream("/logo.png"))));
            stage.setTitle("About Us");
            stage.initModality(Modality.APPLICATION_MODAL);
            stage.setScene(new Scene(loader.load()));
            stage.showAndWait();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void handleQuitAction() {
        ((Stage) closeAppButton.getScene().getWindow()).close();
    }

    @FXML
    private void handleHideApp() {
        var stage = (Stage) hideButton.getScene().getWindow();
        stage.setIconified(!stage.isIconified());
    }

    @FXML
    private void handleShrink() throws IOException {
        var loader = new FXMLLoader(getClass().getResource("/folltrace/sonar/player_minimised.fxml"));
        var root = (javafx.scene.Parent) loader.load();

        var mc = (MiniController) loader.getController();
        mc.setSonarController(this);
        if (mediaPlayer != null && mediaPlayer.getMedia() != null) {
            mc.setSliderMax(mediaPlayer.getMedia().getDuration().toSeconds());
        }

        var stage = new Stage();
        stage.getIcons().add(new Image(Objects.requireNonNull(getClass().getResourceAsStream("/logo.png"))));
        stage.setResizable(false);
        stage.setTitle("Sonar");
        stage.setScene(new Scene(root));
        stage.initStyle(StageStyle.UNDECORATED);
        stage.show();
        primaryStage.hide();
    }

    @FXML
    private void handleDeleteFromPlaylist() {
        int idx = fileListView.getSelectionModel().getSelectedIndex();
        if (idx == -1) return;

        Track selected = fileListView.getSelectionModel().getSelectedItem();
        String path = trackNamesToPaths.get(selected.name());

        boolean wasPlaying = mediaPlayer != null
                && mediaPlayer.getMedia().getSource().equals(new File(path).toURI().toString());

        fileListView.getItems().remove(idx);
        trackNamesToPaths.remove(selected.name());
        playlist.remove(path);

        if (wasPlaying) {
            if (repeatState == RepeatState.REPEAT_ONE) {
                if (!playlist.isEmpty()) {
                    playSelectedTrack(Math.min(idx, playlist.size() - 1));
                } else {
                    mediaPlayer.stop();
                    statusLabel.setText("Playback stopped");
                }
            } else {
                player.stopMedia();
                if (idx < playlist.size()) {
                    playSelectedTrack(idx);
                } else if (repeatState == RepeatState.REPEAT_ALL && !playlist.isEmpty()) {
                    playSelectedTrack(0);
                } else {
                    statusLabel.setText("Playback stopped");
                }
            }
        }

        if (playlist.isEmpty()) {
            resetTrackInfoDisplay();
        }
    }

    @FXML private void handleSavePlaylist() { savePlaylistAsExtendedM3U(); }
    @FXML private void handleLoadPlaylist() { loadPlaylist(); }

    @FXML
    public void handleThemeChange() {
        UIManager.changeTheme(scene, darkThemeCheck.isSelected());
    }

    // ---- Public API ----

    public void showMainWindow() {
        if (primaryStage != null) {
            primaryStage.show();
            primaryStage.toFront();
        }
    }

    public void updateMediaPlayerTime(double time) {
        if (mediaPlayer != null) {
            mediaPlayer.seek(Duration.seconds(time));
        }
    }

    public void onVolumeChanged(double newVolume) {
        if (mediaPlayer != null) {
            mediaPlayer.setVolume(newVolume / 100.0);
        }
        if (volumeSlider != null) {
            volumeSlider.setValue(newVolume / 100.0);
        }
        if (miniController != null) {
            miniController.updateVolumeSlider(newVolume);
        }
        updateVolumeIcon(newVolume);
    }

    public void updateSongInfo(Media media) {
        var meta = media.getMetadata();
        if (meta.containsKey("title"))  songName.setText(meta.get("title").toString());
        if (meta.containsKey("album"))  albumName.setText(meta.get("album").toString());
        if (meta.containsKey("artist")) authorName.setText(meta.get("artist").toString());

        try {
            var uri = new URI(media.getSource());
            var file = new File(uri);
            var cover = getAlbumCover(file);
            if (cover != null) {
                albumCoverImageView.setImage(SwingFXUtils.toFXImage(cover, null));
            } else {
                albumCoverImageView.setImage(null);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public String getTrackDuration(File file) {
        try {
            var mp3 = new Mp3File(file.getAbsolutePath());
            long seconds = mp3.getLengthInSeconds();
            return formatTrackLength(seconds * 1000);
        } catch (Exception e) {
            return "Unknown";
        }
    }

    public BufferedImage getAlbumCover(File file) {
        if (!file.exists()) return null;
        try {
            var mp3 = new Mp3File(file.getAbsolutePath());
            if (mp3.hasId3v2Tag()) {
                byte[] imageData = mp3.getId3v2Tag().getAlbumImage();
                if (imageData != null) {
                    try (var bis = new ByteArrayInputStream(imageData)) {
                        return ImageIO.read(bis);
                    }
                }
            }
        } catch (IOException | UnsupportedTagException | InvalidDataException e) {
            e.printStackTrace();
        }
        return null;
    }

    // ---- Internal helpers ----

    private void addFileToPlaylist(File file) {
        if (file != null && isSupportedFile(file.getName())) {
            String path = file.getAbsolutePath();
            if (!playlist.contains(path)) {
                String duration = getTrackDuration(file);
                fileListView.getItems().add(new Track(file.getName(), duration));
                trackNamesToPaths.put(file.getName(), path);
                playlist.add(path);
                if (!originalPlaylist.contains(path)) {
                    originalPlaylist.add(path);
                }
            }
        }
    }

    private void updateFileList(File folder) {
        File[] files = folder.listFiles();
        if (files != null) {
            for (File f : files) {
                addFileToPlaylist(f);
            }
        }
    }

    private boolean isSupportedFile(String name) {
        String lower = name.toLowerCase();
        return SUPPORTED_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private void updateRepeatModeUI() {
        switch (repeatState) {
            case OFF -> {
                statusLabel.setText("Repeat: Off");
                UIManager.setImageToButton(toggleRepeatButton, "/icons/off.png", 15, 15);
            }
            case REPEAT_ALL -> {
                statusLabel.setText("Repeat: All");
                UIManager.setImageToButton(toggleRepeatButton, "/icons/repeat_all.png", 15, 15);
            }
            case REPEAT_ONE -> {
                statusLabel.setText("Repeat: One");
                UIManager.setImageToButton(toggleRepeatButton, "/icons/repeat_one.png", 15, 15);
            }
        }
    }

    private void updateShuffleModeUI() {
        switch (shuffleState) {
            case OFF -> {
                statusLabel.setText("Shuffle: Off");
                UIManager.setImageToButton(shuffleButton, "/icons/off.png", 15, 15);
            }
            case SHUFFLE_ALL -> {
                statusLabel.setText("Shuffled all tracks");
                UIManager.setImageToButton(shuffleButton, "/icons/shuffle.png", 15, 15);
            }
            case SHUFFLE_NEXT -> {
                statusLabel.setText("Shuffled next tracks");
                UIManager.setImageToButton(shuffleButton, "/icons/shuffle.png", 15, 15);
            }
        }
    }

    private void updateVolumeIcon(double volume) {
        String path;
        if (volume == 0)          path = "/icons/vol_mute.png";
        else if (volume < 30)     path = "/icons/vol_min.png";
        else if (volume < 70)     path = "/icons/vol_low.png";
        else                      path = "/icons/vol_max.png";
        soundIcon.setImage(new Image(Objects.requireNonNull(
                getClass().getResourceAsStream(path))));
    }

    private void updateUIForMediaPlayer(MediaPlayer mp) {
        seekSlider.setMax(mp.getMedia().getDuration().toSeconds());
        mp.currentTimeProperty().addListener((obs, oldTime, newTime) -> {
            if (!seekSlider.isValueChanging()) {
                seekSlider.setValue(newTime.toSeconds());
            }
        });
        UIManager.setImageToButton(togglePlayPauseButton, "/icons/pause.png", 15, 15);
        statusLabel.setText("Playing");
    }

    private void updateSliders() {
        if (mediaPlayer != null) {
            double current = mediaPlayer.getCurrentTime().toSeconds();
            Platform.runLater(() -> {
                seekSlider.setValue(current);
                if (miniController != null) {
                    miniController.updateSeekSlider(current);
                }
            });
        }
    }

    private void updatePlayPauseButton(boolean playing) {
        togglePlayPauseButton.setText(null);
        if (playing) {
            UIManager.setHoverEffectToButton(togglePlayPauseButton, "/icons/pause.png", "/icons/pause_solid.png", 15, 15);
        } else {
            UIManager.setHoverEffectToButton(togglePlayPauseButton, "/icons/play.png", "/icons/play_solid.png", 15, 15);
        }
    }

    private void playSelectedTrack(int index) {
        if (index < 0 || index >= playlist.size()) return;

        String path = playlist.get(index);
        currentlyPlayingTrackPath = path;
        double currentVolume = mediaPlayer != null ? mediaPlayer.getVolume() : 1.0;

        player.playMedia(path);

        if (mediaPlayer != null) {
            mediaPlayer.setVolume(currentVolume);
        }
        updateCurrentTime();
        timeline.play();
        fileListView.getSelectionModel().select(index);
        togglePlayPauseButton.setText(null);
        updatePlayPauseButton(true);

        if (miniController != null && mediaPlayer.getMedia() != null) {
            miniController.updateSeekSliderMax(mediaPlayer.getMedia().getDuration().toSeconds());
        }
    }

    private void selectCurrentlyPlayingTrack() {
        if (currentlyPlayingTrackPath != null) {
            int idx = playlist.indexOf(currentlyPlayingTrackPath);
            if (idx != -1) {
                fileListView.getSelectionModel().select(idx);
            }
        }
    }

    private String formatTrackLength(long durationMs) {
        long seconds = durationMs / 1000;
        long minutes = seconds / 60;
        seconds %= 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    private void updateCurrentTime() {
        MediaPlayer mp = player.getMediaPlayer();
        if (mp != null && mp.getStatus() == MediaPlayer.Status.PLAYING) {
            Duration t = mp.getCurrentTime();
            int min = (int) t.toMinutes();
            int sec = (int) t.toSeconds() % 60;
            currentTimeLabel.setText(String.format("%02d:%02d", min, sec));
        }
    }

    private void shufflePlaylistAll() {
        if (playlist.isEmpty()) return;
        int currentIdx = fileListView.getSelectionModel().getSelectedIndex();
        String current = playlist.get(currentIdx);
        Collections.shuffle(playlist);
        int newIdx = playlist.indexOf(current);
        playlist.remove(newIdx);
        playlist.add(0, current);
        updateListView();
        fileListView.getSelectionModel().select(0);
    }

    private void shufflePlaylistNext() {
        int currentIdx = fileListView.getSelectionModel().getSelectedIndex();
        if (currentIdx < playlist.size() - 1) {
            var remaining = new ArrayList<>(playlist.subList(currentIdx + 1, playlist.size()));
            Collections.shuffle(remaining);
            var newPlaylist = new ArrayList<>(playlist.subList(0, currentIdx + 1));
            newPlaylist.addAll(remaining);
            playlist.clear();
            playlist.addAll(newPlaylist);
            updateListView();
            fileListView.getSelectionModel().select(currentIdx);
        }
    }

    private void updateListView() {
        fileListView.getItems().clear();
        for (String path : playlist) {
            var file = new File(path);
            fileListView.getItems().add(new Track(file.getName(), getTrackDuration(file)));
        }
    }

    private void restoreOriginalOrder() {
        playlist.clear();
        playlist.addAll(originalPlaylist);
    }

    private void resetTrackInfoDisplay() {
        var defaultImg = new Image(Objects.requireNonNull(
                getClass().getResourceAsStream("/no_track_img.png")));
        albumCoverImageView.setImage(defaultImg);
        songName.setText("No tracks loaded");
        albumName.setText("No tracks loaded");
        authorName.setText("No tracks loaded");
        statusLabel.setText("No track selected");
        currentTimeLabel.setText("--:--");
    }

    // ---- Playlist I/O ----

    public void savePlaylistAsExtendedM3U() {
        var chooser = new FileChooser();
        chooser.setTitle("Save Playlist");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("M3U Files", "*.m3u"));
        File file = chooser.showSaveDialog(null);
        if (file == null) return;

        try (var writer = new PrintWriter(file)) {
            writer.println("#EXTM3U");
            for (String path : playlist) {
                var mp3 = new Mp3File(path);
                if (mp3.hasId3v2Tag()) {
                    var tag = mp3.getId3v2Tag();
                    writer.println("#EXTINF:" + mp3.getLengthInSeconds() + ","
                            + tag.getArtist() + " - " + tag.getTitle());
                    writer.println(path);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void loadPlaylist() {
        var chooser = new FileChooser();
        chooser.setTitle("Open Playlist");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("M3U Files", "*.m3u"));
        File file = chooser.showOpenDialog(null);
        if (file == null) return;

        try (var reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("#") && !line.isBlank()) {
                    addFileToPlaylist(new File(line));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
