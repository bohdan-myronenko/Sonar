package folltrace.sonar;

import com.mpatric.mp3agic.Mp3File;
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
import javafx.stage.*;
import javafx.util.Duration;
import javafx.scene.text.Text;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SonarController implements PlayerCallback, MprisPlayer {

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
    private final Map<String, TrackInfo> trackMetadataCache = new HashMap<>();
    private final ExecutorService metadataExecutor = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "sonar-metadata");
        t.setDaemon(true);
        return t;
    });
    private RepeatState repeatState = RepeatState.OFF;
    private ShuffleState shuffleState = ShuffleState.OFF;

    private double previousVolume = 0.5;
    private double dragOffsetX;
    private double dragOffsetY;

    private Player player;
    private MiniController miniController;
    private String currentlyPlayingTrackPath;
    private String currentAlbumArtPath;   // temp file holding album art for MPRIS
    private String currentTrackTitle;     // full title for D-Bus (label may show truncated)
    private MprisService mpris;
    private volatile boolean isPlaying;  // tracked so MPRIS reads correct state immediately
    private long lastMprisPositionSync;   // throttle position updates to D-Bus

    private static final List<String> SUPPORTED_EXTENSIONS =
            List.of(".mp3", ".wav", ".aac", ".m4a", ".flac", ".ogg", ".opus",
                    ".aiff", ".aif", ".wma", ".wavpack", ".wv", ".ape", ".mka",
                    ".ac3", ".dts", ".alac", ".tta", ".spx", ".ra", ".rm", ".mid",
                    ".midi", ".mod", ".xm", ".s3m", ".it");

    // ---- PlayerCallback ----

    @Override
    public RepeatState getRepeatState() {
        return repeatState;
    }

    public ShuffleState getShuffleState() {
        return shuffleState;
    }

    @Override
    public void onMediaReady(double durationSeconds) {
        isPlaying = true;
        updateUIForMediaPlayer(durationSeconds);
        updateSongInfo(player.getSourceUri());
        if (miniController != null) {
            miniController.updateSeekSliderMax(durationSeconds);
            miniController.pullStateFromMain();
        }
        mprisNotifyState();
    }

    @Override
    public void onNextTrack() {
        int currentIndex = playlist.indexOf(currentlyPlayingTrackPath);
        int nextIndex = currentIndex + 1;

        if (nextIndex >= 0 && nextIndex < playlist.size()) {
            playSelectedTrack(nextIndex);
        } else if (repeatState == RepeatState.REPEAT_ALL && !playlist.isEmpty()) {
            playSelectedTrack(0);
        } else {
            if (player != null) player.stop();
            timeline.stop();
            isPlaying = false;
            statusLabel.setText("Playback stopped");
            updateCurrentTime();
            mprisNotifyState();
        }
    }

    @Override
    public void onPreviousTrack() {
        int currentIndex = playlist.indexOf(currentlyPlayingTrackPath);
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
        setupMediaKeys();
        if (pendingDarkTheme) {
            darkThemeCheck.setSelected(true);
            UIManager.changeTheme(scene, true);
            pendingDarkTheme = false;
        }
    }
    public void setPrimaryStage(Stage stage) { this.primaryStage = stage; }
    public void setMiniController(MiniController mc) { this.miniController = mc; }

    public Player getPlayer() {
        return player;
    }

    public javafx.scene.image.ImageView getAlbumCoverImageView() {
        return albumCoverImageView;
    }

    // ---- Initialization ----

    @FXML
    public void initialize() {
        // Listeners are attached lazily when the first real track is loaded.

        try {
            player = new Player(this);
        } catch (IOException e) {
            statusLabel.setText("Error: mpv not found. Install mpv.");
            e.printStackTrace();
            return;
        }

        // Safety net: kill mpv if JVM exits without proper cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            metadataExecutor.shutdownNow();
            if (player != null) {
                player.close();
            }
        }, "mpv-shutdown"));

        setupButtonIcons();
        setupInitialLabels();
        setupDragHandling();
        setupVolumeSlider();
        setupSeekSlider();
        setupFileListView();
        setupDragAndDrop();
        setupSoundIconToggle();

        // Restore saved settings
        restoreSettings();

        timeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            updateSliders();
            updateCurrentTime();
            // Sync MPRIS position every 5 ticks (5 seconds) while playing
            if (isPlaying && mpris != null && timeline.getCurrentRate() > 0) {
                var now = System.currentTimeMillis();
                if (now - lastMprisPositionSync >= 5000) {
                    lastMprisPositionSync = now;
                    mpris.notifyStateChanged();
                }
            }
        }));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();

        // Register MPRIS (system media controls) on Linux
        mpris = new MprisService(this);
        mpris.start();

        // Setup media key capture (scenes are set later via setScene)
    }

    private boolean pendingDarkTheme;

    private void restoreSettings() {
        var s = Settings.get();

        // Volume
        double savedVolume = s.getVolume();
        volumeSlider.setValue(savedVolume);
        player.setVolume(savedVolume);
        int pct = (int) Math.round(savedVolume * 100);
        volumeLabel.setText(pct + "%");
        updateVolumeIcon(pct);

        // Dark theme — deferred until scene is available
        pendingDarkTheme = s.getDarkTheme();
    }

    /**
     * Sets up keyboard capture for XF86 media keys.
     * Must be called after setScene().
     */
    private void setupMediaKeys() {
        if (scene == null) return;
        scene.setOnKeyPressed(event -> {
            switch (event.getCode()) {
                // Standard JavaFX key codes (works on some platforms)
                case PLAY   -> handleTogglePlayPause();
                case PAUSE  -> handleTogglePlayPause();
                case STOP   -> handleStop();
                case TRACK_NEXT -> onNextTrack();
                case TRACK_PREV -> onPreviousTrack();
                // Also try to match by name (XF86Audio* keys may not have
                // dedicated KeyCode entries in all JavaFX versions)
                default -> {
                    String name = event.getCode().getName();
                    if (name == null) break;
                    switch (name) {
                        case "Play", "Media Play", "Media Play/Pause" ->
                            handleTogglePlayPause();
                        case "Pause", "Media Pause" ->
                            handleTogglePlayPause();
                        case "Stop", "Media Stop" ->
                            handleStop();
                        case "Next", "Media Next", "Next Track" ->
                            onNextTrack();
                        case "Previous", "Media Previous", "Previous Track" ->
                            onPreviousTrack();
                    }
                }
            }
        });
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

    /** Returns the Player instance if a track is loaded. */
    private Player getActivePlayer() {
        if (player == null) return null;
        if (player.getDuration() <= 0 && !player.isPlaying()) return null;
        return player;
    }

    private void setupVolumeSlider() {
        volumeSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            var p = getActivePlayer();
            if (p != null) {
                p.setVolume(newVal.doubleValue());
            }
            int percent = (int) Math.round(newVal.doubleValue() * 100);
            volumeLabel.setText(percent + "%");
            updateVolumeIcon(newVal.doubleValue() * 100);
            mprisNotifyState();
        });
    }

    private void setupMediaPlayerListeners() {
        // With mpv backend, position updates come from the timeline tick.
        // seekSlider max and song info are set in onMediaReady.
    }

    private void setupSeekSlider() {
        seekSlider.valueChangingProperty().addListener((obs, wasChanging, isChanging) -> {
            if (!isChanging) {
                var p = getActivePlayer();
                if (p != null) p.seek(seekSlider.getValue());
                mprisNotifyState();
            }
        });
        seekSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (!seekSlider.isValueChanging()) {
                var p = getActivePlayer();
                if (p != null) {
                    double current = p.getPosition();
                    if (Math.abs(current - newVal.doubleValue()) > 0.5) {
                        p.seek(newVal.doubleValue());
                    }
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
            var p = getActivePlayer();
            if (p != null) {
                if (p.getVolume() > 0) {
                    previousVolume = p.getVolume();
                    p.setVolume(0);
                    volumeSlider.setValue(0);
                    updateVolumeIcon(0);
                } else {
                    p.setVolume(previousVolume);
                    volumeSlider.setValue(previousVolume);
                    updateVolumeIcon(previousVolume * 100);
                }
            }
        });
    }

    // ---- FXML handler methods ----

    @FXML
    protected void handleTogglePlayPause() {
        var p = getActivePlayer();
        if (p == null) return;
        if (isPlaying) {
            p.pause();
            timeline.pause();
            updatePlayPauseButton(false);
            isPlaying = false;
        } else {
            p.play();
            timeline.play();
            updatePlayPauseButton(true);
            isPlaying = true;
        }
        mprisNotifyState();
    }

    @FXML
    void handleStop() {
        var p = getActivePlayer();
        if (p != null) {
            p.stop();
        }
        timeline.stop();
        isPlaying = false;
        currentTimeLabel.setText("--:--");
        statusLabel.setText("Stopped");
        mprisNotifyState();
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
                new FileChooser.ExtensionFilter("Audio Files",
                        "*.mp3", "*.wav", "*.aac", "*.m4a", "*.flac", "*.ogg", "*.opus",
                        "*.aiff", "*.aif", "*.wma", "*.wavpack", "*.wv", "*.ape", "*.mka",
                        "*.ac3", "*.dts", "*.alac", "*.tta", "*.spx", "*.ra", "*.rm", "*.mid",
                        "*.midi", "*.mod", "*.xm", "*.s3m", "*.it"));
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
        mprisNotifyState();
    }

    @FXML private void handleRepeatOff()   { repeatState = RepeatState.OFF;        updateRepeatModeUI(); mprisNotifyState(); }
    @FXML private void handleRepeatAll()   { repeatState = RepeatState.REPEAT_ALL;  updateRepeatModeUI(); mprisNotifyState(); }
    @FXML private void handleRepeatOne()   { repeatState = RepeatState.REPEAT_ONE;  updateRepeatModeUI(); mprisNotifyState(); }

    @FXML
    protected void handleShuffleAll() {
        shuffleState = ShuffleState.SHUFFLE_ALL;
        shufflePlaylistAll();
        updateShuffleModeUI();
        mprisNotifyState();
    }

    @FXML
    private void handleShuffleNext() {
        shuffleState = ShuffleState.SHUFFLE_NEXT;
        shufflePlaylistNext();
        updateShuffleModeUI();
        mprisNotifyState();
    }

    @FXML
    protected void handleShuffleOff() {
        shuffleState = ShuffleState.OFF;
        restoreOriginalOrder();
        updateListView();
        selectCurrentlyPlayingTrack();
        updateShuffleModeUI();
        mprisNotifyState();
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
        mprisNotifyState();
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
        closeMpris();
        if (player != null) {
            player.close();
            player = null;
        }
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
        setMiniController(mc);
        mc.pullStateFromMain();

        var stage = new Stage();
        stage.getIcons().add(new Image(Objects.requireNonNull(getClass().getResourceAsStream("/logo.png"))));
        stage.setResizable(false);
        stage.setTitle("Sonar");
        var miniScene = new Scene(root);
        stage.setScene(miniScene);
        stage.initStyle(StageStyle.UNDECORATED);

        // Sync dark theme to the mini window
        UIManager.changeTheme(miniScene, darkThemeCheck.isSelected());

        stage.setOnHidden(e -> {
            setMiniController(null);
            showMainWindow();
        });

        stage.show();
        primaryStage.hide();
        minimisedCheck.setSelected(true);
    }

    @FXML
    private void handleDeleteFromPlaylist() {
        int idx = fileListView.getSelectionModel().getSelectedIndex();
        if (idx == -1) return;

        Track selected = fileListView.getSelectionModel().getSelectedItem();
        String path = trackNamesToPaths.get(selected.name());

        var p = getActivePlayer();
        boolean wasPlaying = p != null
                && new File(currentlyPlayingTrackPath).toURI().toString()
                   .equals(new File(path).toURI().toString());

        fileListView.getItems().remove(idx);
        trackNamesToPaths.remove(selected.name());
        playlist.remove(path);

        if (wasPlaying) {
            if (repeatState == RepeatState.REPEAT_ONE) {
                if (!playlist.isEmpty()) {
                    playSelectedTrack(Math.min(idx, playlist.size() - 1));
                } else {
                    p.stop();
                    statusLabel.setText("Playback stopped");
                }
            } else {
                player.stop();
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
        boolean dark = darkThemeCheck.isSelected();
        UIManager.changeTheme(scene, dark);
        // Also sync the mini window if it's showing
        if (miniController != null) {
            var miniScene = miniController.getScene();
            if (miniScene != null) {
                UIManager.changeTheme(miniScene, dark);
            }
        }
        Settings.get().setDarkTheme(dark);
    }

    @FXML
    private void handleMinimisedCheck() {
        if (minimisedCheck.isSelected()) {
            try { handleShrink(); } catch (IOException e) { e.printStackTrace(); }
        } else {
            // Restore full mode — close the mini stage if it exists
            if (miniController != null) {
                miniController.closeMiniStage();
            }
        }
    }

    // ---- Public API ----

    public void showMainWindow() {
        if (primaryStage != null) {
            primaryStage.show();
            primaryStage.toFront();
            minimisedCheck.setSelected(false);
        }
    }

    public void updateMediaPlayerTime(double time) {
        var p = getActivePlayer();
        if (p != null) {
            p.seek(time);
            mprisNotifyState();
        }
    }

    public void onVolumeChanged(double newVolume) {
        var p = getActivePlayer();
        if (p != null) {
            p.setVolume(newVolume / 100.0);
        }
        if (volumeSlider != null) {
            volumeSlider.setValue(newVolume / 100.0);
        }
        if (miniController != null) {
            miniController.updateVolumeSlider(newVolume);
        }
        updateVolumeIcon(newVolume);
        Settings.get().setVolume(newVolume / 100.0);
        mprisNotifyState();
    }

    public void updateSongInfo(String sourceUri) {
        var meta = player.getMetadata();
        // mpv metadata keys are case-sensitive; do a case-insensitive lookup
        String title  = getMetaCI(meta, "title");
        String artist = getMetaCI(meta, "artist");
        String album  = getMetaCI(meta, "album");

        // Fallback: extract from filename if metadata is missing
        try {
            var uri = new URI(sourceUri);
            var file = new File(uri);
            if (title == null || title.isBlank()) {
                String name = file.getName();
                int dot = name.lastIndexOf('.');
                title = dot > 0 ? name.substring(0, dot) : name;
            }
            if (artist == null || artist.isBlank()) artist = "Unknown Artist";
            if (album == null || album.isBlank()) album = "Unknown Album";

            var cover = getAlbumCover(file);
            if (cover != null) {
                albumCoverImageView.setImage(SwingFXUtils.toFXImage(cover, null));
                saveAlbumArtToTempFile(cover);
            } else {
                albumCoverImageView.setImage(null);
                deleteAlbumArtTempFile();
            }
        } catch (Exception e) {
            e.printStackTrace();
            if (title == null || title.isBlank()) title = "Unknown";
            if (artist == null) artist = "Unknown Artist";
            if (album == null) album = "Unknown Album";
        }

        currentTrackTitle = title;
        applyTitleDisplay(songName, title);
        authorName.setText(artist);
        albumName.setText(album);
    }

    /** Case-insensitive lookup in a string→string map. */
    private static String getMetaCI(Map<String, String> map, String key) {
        // Try exact match first
        String v = map.get(key);
        if (v != null) return v;
        // Try case-insensitive
        for (var entry : map.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        // mpv also reports as "icy-title", "icy-name" etc. for streams
        if ("title".equalsIgnoreCase(key)) {
            v = map.get("icy-title");
            if (v != null) return v;
        }
        if ("artist".equalsIgnoreCase(key)) {
            v = map.get("icy-name");
            if (v != null) return v;
        }
        return null;
    }

    // ── Title truncation + marquee (label display only; D-Bus gets full title) ─

    /** Truncates a filename at {@code maxLen} chars, preserving the file extension. */
    static String truncateFilename(String name, int maxLen) {
        if (name == null || name.length() <= maxLen) return name;
        int dot = name.lastIndexOf('.');
        String ext = (dot > 0 && dot > name.length() - 6) ? name.substring(dot) : "";
        int keep = maxLen - 3 - ext.length();  // 3 = "..."
        if (keep < 3) keep = 3;
        return name.substring(0, keep) + "..." + ext;
    }

    /** Sets the full title on the label and starts marquee scrolling only if
     *  the text actually overflows the label's visual width. */
    void applyTitleDisplay(Label label, String fullTitle) {
        cancelLabelMarquee(label);
        label.setText(fullTitle);

        // Re-check after layout to see if text overflows the label
        Platform.runLater(() -> {
            if (textExceedsLabel(label, fullTitle)) {
                startLabelMarquee(label, fullTitle);
            }
        });
    }

    static boolean textExceedsLabel(Label label, String text) {
        Text helper = new Text(text);
        helper.setFont(label.getFont());
        double tw = helper.getLayoutBounds().getWidth();
        double lw = label.getWidth() - label.getPadding().getLeft() - label.getPadding().getRight();
        return tw > lw && lw > 0;
    }

    static void startLabelMarquee(Label label, String displayText) {
        // Pad with spaces and repeat so we have a continuous scroll
        String padded = displayText + "     " + displayText;
        final int[] pos = {0};

        Timeline tl = new Timeline(new KeyFrame(Duration.millis(280), e -> {
            pos[0] = (pos[0] + 1) % (displayText.length() + 5);
            int end = Math.min(pos[0] + displayText.length() + 3, padded.length());
            label.setText(padded.substring(pos[0], end));
        }));
        tl.setCycleCount(Animation.INDEFINITE);
        label.getProperties().put("marquee_timeline", tl);
        tl.play();
    }

    static void cancelLabelMarquee(Label label) {
        Object obj = label.getProperties().get("marquee_timeline");
        if (obj instanceof Timeline tl) {
            tl.stop();
            label.getProperties().remove("marquee_timeline");
        }
    }

    /** Metadata extracted via ffprobe for any audio format. */
    private record TrackInfo(int durationSecs, String artist, String title) {}

    /**
     * Extracts duration, artist, and title from any audio file using ffprobe.
     * Returns null if ffprobe fails entirely. Individual fields may be null/empty
     * when the file doesn't contain that tag.
     */
    private TrackInfo getTrackMetadata(File file) {
        try {
            var pb = new ProcessBuilder(
                    "ffprobe", "-v", "quiet",
                    "-show_entries", "format=duration:format_tags=title,artist",
                    "-of", "csv=p=0",
                    file.getAbsolutePath());
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            var p = pb.start();
            String line;
            try (var r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                line = r.readLine();
            }
            p.waitFor(3, TimeUnit.SECONDS);
            p.destroyForcibly();

            if (line != null && !line.isBlank()) {
                String[] parts = line.split(",", -1);
                // format=duration:format_tags returns: duration,title,artist
                int idx = 0;
                double durSecs = -1;
                if (idx < parts.length && !parts[idx].isBlank()
                        && !parts[idx].equals("N/A")) {
                    durSecs = Double.parseDouble(parts[idx].trim());
                }
                idx++;
                String title = (idx < parts.length && !parts[idx].isBlank()) ? parts[idx].trim() : null;
                idx++;
                String artist = (idx < parts.length && !parts[idx].isBlank()) ? parts[idx].trim() : null;
                return new TrackInfo((int) Math.round(durSecs), artist, title);
            }
        } catch (Exception e) {
            // ffprobe not available or failed
        }
        return null;
    }

    public String getTrackDuration(File file) {
        // Use ffprobe for accurate duration on any format
        try {
            var pb = new ProcessBuilder(
                    "ffprobe", "-v", "quiet",
                    "-show_entries", "format=duration",
                    "-of", "csv=p=0",
                    file.getAbsolutePath());
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            var p = pb.start();
            String line;
            try (var r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                line = r.readLine();
            }
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            p.destroyForcibly();
            if (line != null && !line.isBlank() && !line.equals("N/A")) {
                double secs = Double.parseDouble(line.trim());
                long totalMs = Math.round(secs * 1000);
                return formatTrackLength(totalMs);
            }
        } catch (Exception e) {
            // ffprobe not available or failed
        }

        // Fallback: try mp3agic for MP3 files
        if (file.getName().toLowerCase().endsWith(".mp3")) {
            try {
                var mp3 = new Mp3File(file.getAbsolutePath());
                long seconds = mp3.getLengthInSeconds();
                return formatTrackLength(seconds * 1000);
            } catch (Exception ignored) {}
        }

        return "Unknown";
    }

    public BufferedImage getAlbumCover(File file) {
        if (!file.exists()) return null;

        // Try mp3agic for MP3 files (fast, pure Java)
        String name = file.getName().toLowerCase();
        if (name.endsWith(".mp3")) {
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
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // For non-MP3 formats, use ffmpeg to extract embedded cover art
        return extractCoverWithFfmpeg(file);
    }

    /** Extract embedded cover art from any audio format using ffmpeg. */
    private BufferedImage extractCoverWithFfmpeg(File audioFile) {
        try {
            Path tmpCover = Files.createTempFile("sonar_ffcover_", ".jpg");
            var pb = new ProcessBuilder(
                    "ffmpeg", "-y", "-i", audioFile.getAbsolutePath(),
                    "-an", "-vcodec", "copy", "-f", "image2",
                    tmpCover.toAbsolutePath().toString());
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            p.destroyForcibly();

            if (Files.size(tmpCover) > 0) {
                BufferedImage img = ImageIO.read(tmpCover.toFile());
                Files.deleteIfExists(tmpCover);
                return img;
            }
            Files.deleteIfExists(tmpCover);
        } catch (Exception e) {
            // ffmpeg not available or extraction failed — silently return null
        }
        return null;
    }

    /** Writes embedded album art to a temp PNG file so MPRIS can expose it via mpris:artUrl. */
    private void saveAlbumArtToTempFile(BufferedImage cover) {
        // Delete previous art file first
        deleteAlbumArtTempFile();
        try {
            Path tmp = Files.createTempFile("sonar_art_", ".png");
            ImageIO.write(cover, "png", tmp.toFile());
            currentAlbumArtPath = tmp.toAbsolutePath().toString();
        } catch (IOException e) {
            System.err.println("[Sonar] Failed to write album art temp file: " + e);
        }
    }

    private void deleteAlbumArtTempFile() {
        if (currentAlbumArtPath != null) {
            try {
                Files.deleteIfExists(Path.of(currentAlbumArtPath));
            } catch (IOException ignored) {}
            currentAlbumArtPath = null;
        }
    }

    // ---- Internal helpers ----

    private void addFileToPlaylist(File file) {
        if (file == null || !isSupportedFile(file.getName())) return;
        String path = file.getAbsolutePath();
        if (playlist.contains(path)) return;

        String fileName = file.getName();
        // Add placeholder immediately; fetch real metadata in background
        fileListView.getItems().add(new Track(fileName, "Loading..."));
        trackNamesToPaths.put(fileName, path);
        playlist.add(path);
        if (!originalPlaylist.contains(path)) {
            originalPlaylist.add(path);
        }

        metadataExecutor.submit(() -> {
            var info = getTrackMetadata(file);
            Platform.runLater(() -> {
                String duration = (info != null && info.durationSecs >= 0)
                        ? formatTrackLength((long) info.durationSecs * 1000)
                        : "Unknown";
                // Update the ListView item in place
                int idx = playlist.indexOf(path);
                if (idx >= 0 && idx < fileListView.getItems().size()) {
                    fileListView.getItems().set(idx, new Track(fileName, duration));
                }
                if (info != null) {
                    trackMetadataCache.put(path, info);
                }
            });
        });
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
        if (miniController != null) {
            miniController.updateRepeatIcon(repeatState);
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
        if (miniController != null) {
            miniController.updateShuffleIcon(shuffleState);
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

    private void updateUIForMediaPlayer(double durationSeconds) {
        // Reset slider BEFORE setting max to prevent value clamping
        // from the previous track's end position to the new track's max,
        // which would trigger a seek-to-end.
        seekSlider.setValue(0);
        seekSlider.setMax(durationSeconds);
        UIManager.setImageToButton(togglePlayPauseButton, "/icons/pause.png", 15, 15);
        statusLabel.setText("Playing");
    }

    private void updateSliders() {
        var p = getActivePlayer();
        if (p != null) {
            double current = p.getPosition();
            Platform.runLater(() -> {
                seekSlider.setValue(current);
                if (miniController != null) {
                    miniController.updateSeekSlider(current);
                    miniController.updateCurrentTimeDisplay(current);
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
        if (miniController != null) {
            miniController.updatePlayPauseButton(playing);
        }
    }

    private void playSelectedTrack(int index) {
        if (index < 0 || index >= playlist.size()) return;

        String path = playlist.get(index);
        currentlyPlayingTrackPath = path;

        isPlaying = true;
        player.playMedia(path);

        updateCurrentTime();
        timeline.play();
        fileListView.getSelectionModel().select(index);
        togglePlayPauseButton.setText(null);
        updatePlayPauseButton(true);
        mprisNotifyState();
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
        var p = player;
        if (p != null && p.getDuration() > 0) {
            double t = p.getPosition();
            int min = (int) (t / 60);
            int sec = (int) (t % 60);
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
            String duration;
            var cached = trackMetadataCache.get(path);
            if (cached != null && cached.durationSecs >= 0) {
                duration = formatTrackLength((long) cached.durationSecs * 1000);
            } else {
                duration = getTrackDuration(file);
                // Cache whatever we got (even "Unknown") to avoid re-fetching
            }
            fileListView.getItems().add(new Track(file.getName(), duration));
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
        cancelLabelMarquee(songName);
        songName.setText("No tracks loaded");
        albumName.setText("No tracks loaded");
        authorName.setText("No tracks loaded");
        statusLabel.setText("No track selected");
        currentTimeLabel.setText("--:--");
        deleteAlbumArtTempFile();
    }

    // ---- Playlist I/O ----

    public void savePlaylistAsExtendedM3U() {
        var chooser = new FileChooser();
        chooser.setTitle("Save Playlist");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("M3U Playlist", "*.m3u", "*.m3u8"));
        File file = chooser.showSaveDialog(null);
        if (file == null) return;
        // Append .m3u if the user didn't type an extension
        String name = file.getName().toLowerCase();
        if (!name.endsWith(".m3u") && !name.endsWith(".m3u8")) {
            file = new File(file.getParentFile(), file.getName() + ".m3u");
        }

        try (var writer = new PrintWriter(file)) {
            writer.println("#EXTM3U");
            for (String path : playlist) {
                var info = getTrackMetadata(new File(path));
                if (info != null && info.durationSecs >= 0) {
                    String display = (info.artist != null && info.title != null)
                            ? info.artist + " - " + info.title
                            : new File(path).getName();
                    writer.println("#EXTINF:" + info.durationSecs + "," + display);
                }
                writer.println(path);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void loadPlaylist() {
        var chooser = new FileChooser();
        chooser.setTitle("Open Playlist");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("M3U Playlist", "*.m3u", "*.m3u8"));
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

    // ---- MPRIS support ----

    private void mprisNotifyState() {
        if (mpris != null) mpris.notifyStateChanged();
    }

    /**
     * Notify MPRIS after a short delay — used after seek operations,
     * because MediaPlayer.seek() is asynchronous and an immediate
     * notification would report the stale pre-seek position.
     */
    private void mprisNotifyStateDelayed() {
        var pause = new javafx.animation.PauseTransition(Duration.millis(300));
        pause.setOnFinished(e -> mprisNotifyState());
        pause.play();
    }

    private void closeMpris() {
        if (mpris != null) { mpris.shutdown(); mpris = null; }
    }

    private void handleTogglePlayPauseIfPaused() {
        var p = getActivePlayer();
        if (p != null && !isPlaying) {
            handleTogglePlayPause();
        }
    }

    private void handleTogglePlayPauseIfPlaying() {
        var p = getActivePlayer();
        if (p != null && isPlaying) {
            handleTogglePlayPause();
        }
    }

    @Override public MprisPlayer.PlaybackStatus getPlaybackStatus() {
        var p = getActivePlayer();
        if (p == null || !p.isPlaying()) return MprisPlayer.PlaybackStatus.Stopped;
        return isPlaying ? MprisPlayer.PlaybackStatus.Playing
                         : MprisPlayer.PlaybackStatus.Paused;
    }

    @Override public void play()         { handleTogglePlayPauseIfPaused(); }
        @Override public void pause()        { handleTogglePlayPauseIfPlaying(); }
        @Override public void playPause()    { handleTogglePlayPause(); }
    @Override public void stop()         { handleStop(); }
    @Override public void next()         { onNextTrack(); }
    @Override public void previous()     { onPreviousTrack(); }
    @Override public void seek(long offsetMicros) {
        var p = getActivePlayer();
        if (p != null) {
            double newPos = p.getPosition() + (offsetMicros / 1_000_000.0);
            p.seek(Math.max(0, newPos));
        }
        mprisNotifyStateDelayed();
    }
    @Override public void setPosition(String trackId, long posMicros) {
        var p = getActivePlayer();
        if (p != null) p.seek(posMicros / 1_000_000.0);
        mprisNotifyStateDelayed();
    }
    @Override public void openUri(String uri) {
        addFileToPlaylist(new File(java.net.URI.create(uri)));
    }
    @Override public void raise() {
        if (primaryStage != null) {
            Platform.runLater(() -> {
                primaryStage.setIconified(false);
                primaryStage.toFront();
            });
        }
    }

    @Override public String getTrackId() {
        return currentlyPlayingTrackPath != null ? Integer.toHexString(currentlyPlayingTrackPath.hashCode()) : "/org/mpris/MediaPlayer2/TrackList/NoTrack";
    }
    @Override public long getTrackDurationMicros() {
        var p = getActivePlayer();
        if (p != null) {
            return (long) (p.getDuration() * 1_000_000);
        }
        return 0;
    }
    @Override public String getTrackTitle() { return currentTrackTitle != null ? currentTrackTitle : songName.getText(); }
    @Override public String getTrackArtist() { return authorName.getText(); }
    @Override public String getTrackAlbum()  { return albumName.getText(); }
    @Override public String getTrackArtUrl() {
        if (currentAlbumArtPath != null) return new File(currentAlbumArtPath).toURI().toString();
        return "";
    }
    @Override public byte[] getTrackArtData() {
        if (albumCoverImageView.getImage() == null) return null;
        try {
            var imgUrl = albumCoverImageView.getImage().getUrl();
            if (imgUrl != null) {
                try (var in = new java.net.URL(imgUrl).openStream()) {
                    return in.readAllBytes();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Override public double  getVolume() {
        var p = getActivePlayer();
        return p != null ? p.getVolume() : 0.5;
    }
    @Override public void    setVolume(double v) {
        var p = getActivePlayer();
        if (p != null) p.setVolume(v);
        volumeSlider.setValue(v);
        int pct = (int) Math.round(v * 100);
        volumeLabel.setText(pct + "%");
        updateVolumeIcon(pct);
        Settings.get().setVolume(v);
    }
    @Override public long    getPositionMicros() {
        var p = getActivePlayer();
        if (p != null) return (long) (p.getPosition() * 1_000_000);
        return 0;
    }
    @Override public double  getMinimumRate() { return 0.5; }
    @Override public double  getMaximumRate() { return 2.0; }
    @Override public double  getRate() {
        var p = getActivePlayer();
        return p != null ? p.getRate() : 1.0;
    }
    @Override public void    setRate(double rate) {
        var p = getActivePlayer();
        if (p != null) p.setRate(rate);
        mprisNotifyState();
    }
    @Override public boolean getShuffle() { return shuffleState != ShuffleState.OFF; }
    @Override public void    setShuffle(boolean s) {
        if (s != (shuffleState != ShuffleState.OFF)) toggleShuffle();
    }
    @Override public String  getLoopStatus() {
        return switch (repeatState) {
            case REPEAT_ONE -> "Track";
            case REPEAT_ALL -> "Playlist";
            default        -> "None";
        };
    }
    @Override public void    setLoopStatus(String s) {
        switch (s) {
            case "None"     -> handleRepeatOff();
            case "Track"    -> handleRepeatOne();
            case "Playlist" -> handleRepeatAll();
        }
    }

    @Override public boolean canGoNext()     { return !playlist.isEmpty(); }
    @Override public boolean canGoPrevious() { return !playlist.isEmpty(); }
    @Override public boolean canPlay()       { return true; }
    @Override public boolean canPause()      { return true; }
    @Override public boolean canSeek()       { return getActivePlayer() != null; }
    @Override public boolean canControl()    { return true; }

    @Override public int getTrackCount() { return playlist.size(); }
    @Override public List<String> getTrackIds() { return List.copyOf(playlist); }
}
