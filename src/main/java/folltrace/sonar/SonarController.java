package folltrace.sonar;

import com.mpatric.mp3agic.ID3v2;
import com.mpatric.mp3agic.InvalidDataException;
import com.mpatric.mp3agic.Mp3File;
import com.mpatric.mp3agic.UnsupportedTagException;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.stage.*;
import javafx.scene.Scene;
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
    private Button shuffleButton;

    @FXML
    private Button stopButton;

    @FXML
    private Button nextButton;

    @FXML
    private Button prevButton;

    @FXML
    private Button deleteButton;

    @FXML
    private Button savePlaylistButton;

    @FXML
    private Button loadPlaylistButton;

    @FXML
    private Button closeAppButton;

    @FXML
    private Button minimizeButton;

    @FXML
    private Button hideButton;


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
    private Label currentTimeLabel;

    @FXML
    private Label songName;

    @FXML
    private Label albumName;

    @FXML

    private Label authorName;


    // IMAGES
    @FXML
    private ImageView albumCoverImageView;

    @FXML
    private ImageView soundIcon;


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
    private MenuItem shuffleAllMenuItem;

    @FXML
    private MenuItem shuffleNextMenuItem;

    @FXML
    private MenuItem shuffleOffMenuItem;

    @FXML
    private MenuItem aboutUsMenuItem;

    @FXML
    private MenuItem quitMenuItem;

    @FXML
    private MenuItem deleteFromPlaylistMenuItem;

    @FXML
    private MenuItem savePlaylistMenuItem;

    @FXML
    private MenuItem loadPlaylistMenuItem;

    @FXML
    private CheckMenuItem darkThemeCheck;

    @FXML
    private CheckMenuItem minimisedCheck;


    // LISTS

    @FXML
    private ScrollPane fileScrollPane;

    @FXML
    private ListView<Track> fileListView;


    // ...

    @FXML
    private HBox titleBar;

    @FXML
    private AnchorPane dragPane;

    // ...
    private Scene scene;

    private Boolean isDarkTheme;



    private Timeline timeline;

    private List<String> playlist = new ArrayList<>();
    private List<String> originalPlaylist = new ArrayList<>();

    private Map<String, String> trackNamesToPaths = new HashMap<>();
    private Map<String, String> fileMap = new HashMap<>();

    private RepeatState repeatState = RepeatState.OFF;
    private ShuffleState shuffleState = ShuffleState.OFF;

    private double previousVolume = 50;
    private double xOffset = 0;
    private double yOffset = 0;

    private MediaPlayer mediaPlayer;
    private Player player;
    private static final List<String> SUPPORTED_FILE_EXTENSIONS = Arrays.asList(".mp3", ".wav", ".aac", ".m4a");

    @Override
    public RepeatState getRepeatState() {
        return this.repeatState;
    }

    public void setScene(Scene scene) {
        this.scene = scene;
    }

    private Stage primaryStage;

    private MiniController miniController;

    public void setMiniController(MiniController miniController) {
        this.miniController = miniController;
    }

    public MediaPlayer getMediaPlayer() {
        if (player != null) {
            return player.getMediaPlayer();
        }
        return null;
    }
    public void setPrimaryStage(Stage primaryStage) {
        this.primaryStage = primaryStage;
    }

    @FXML
    public void initialize() {
        player = new Player(this);
        String musicPath = "/music.mp3";
        Media media = new Media(getClass().getResource(musicPath).toExternalForm());
        mediaPlayer = new MediaPlayer(media);
        isDarkTheme = false;

        // Set up hover effects for buttons
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
        UIManager.setHoverEffectToButton(stopButton,"/icons/stop.png", "/icons/stop_solid.png", 15, 15);
        deleteButton.setText(null);
        UIManager.setImageToButton(deleteButton, "/icons/delete.png", 24, 24);
        savePlaylistButton.setText(null);
        UIManager.setImageToButton(savePlaylistButton, "/icons/save.png", 24, 24);
        loadPlaylistButton.setText(null);
        UIManager.setImageToButton(loadPlaylistButton, "/icons/load.png", 24, 24);

        // Set initial text for labels and status
        songName.setText("No tracks loaded");
        albumName.setText("No tracks loaded");
        authorName.setText("No tracks loaded");
        statusLabel.setText("No track selected");
        volumeLabel.setText((int) Math.round(volumeSlider.getValue() * 100) + "%");


        dragPane.setOnMousePressed(event -> {
            xOffset = event.getSceneX();
            yOffset = event.getSceneY();
        });

        dragPane.setOnMouseDragged(event -> {
            Stage stage = (Stage) dragPane.getScene().getWindow();
            stage.setX(event.getScreenX() - xOffset);
            stage.setY(event.getScreenY() - yOffset);
        });

        // Initialize volume slider listener
        volumeSlider.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (mediaPlayer != null) {
                mediaPlayer.setVolume(newValue.doubleValue());
            }
            int volumePercent = (int) Math.round(newValue.doubleValue() * 100);
            volumeLabel.setText(volumePercent + "%");

            // Update sound icon based on volume level
            updateVolumeIcon(newValue.doubleValue() * 100);
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

        timeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> updateCurrentTime()));
        timeline.setCycleCount(Animation.INDEFINITE);

        fileListView.setCellFactory(lv -> {
            ListCell<Track> cell = new ListCell<Track>() {
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
            };

            cell.setOnDragDetected(event -> {
                if (!cell.isEmpty()) {
                    Dragboard db = cell.startDragAndDrop(TransferMode.MOVE);
                    ClipboardContent cc = new ClipboardContent();
                    cc.putString(Integer.toString(cell.getIndex()));
                    db.setContent(cc);
                }
            });

            cell.setOnDragOver(event -> {
                Dragboard db = event.getDragboard();
                if (db.hasString()) {
                    event.acceptTransferModes(TransferMode.MOVE);
                }
            });

            cell.setOnDragDropped(event -> {
                Dragboard db = event.getDragboard();
                if (db.hasString()) {
                    int draggedIndex = Integer.parseInt(db.getString());
                    int thisIndex = cell.getIndex();

                    // Update the visual representation
                    Track draggedTrack = fileListView.getItems().remove(draggedIndex);
                    fileListView.getItems().add(thisIndex, draggedTrack);

                    // Update the underlying data model
                    String draggedFilePath = playlist.remove(draggedIndex);
                    playlist.add(thisIndex, draggedFilePath);

                    event.setDropCompleted(true);
                    fileListView.getSelectionModel().select(thisIndex);
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
            Dragboard db = event.getDragboard();
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
        soundIcon.setOnMouseClicked(event -> {
            // System.out.println("Mouse clicked");
            if (mediaPlayer != null) {
                //System.out.println("Media player is not null");
                if (mediaPlayer.getVolume() > 0) {
                    //System.out.println("Current volume: " + mediaPlayer.getVolume());
                    previousVolume = mediaPlayer.getVolume();
                    mediaPlayer.setVolume(0);
                    volumeSlider.setValue(0);
                    updateVolumeIcon(0);
                    //System.out.println("Muted volume. Previous volume is " + previousVolume);
                } else {
                    double restoredVolume = previousVolume;
                    //System.out.println("Unmuting... restored volume will be " + restoredVolume);
                    mediaPlayer.setVolume(restoredVolume);
                    volumeSlider.setValue(previousVolume);
                    updateVolumeIcon(restoredVolume);
                }
            }
        });
    }



    // HANDLERS
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
    protected void handleRepeatToggle() {
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
    protected void handleShuffleAll(){
        shuffleState = ShuffleState.SHUFFLE_ALL;
        shufflePlaylistAll();
        updateShuffleModeUI();
    }

    @FXML
    private void handleShuffleNext(){
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
            shuffleState = ShuffleState.SHUFFLE_ALL; // or any other shuffle state you want
            shufflePlaylistAll();
            updateShuffleModeUI();
        } else {
            shuffleState = ShuffleState.OFF;
            restoreOriginalOrder();
            updateListView();
            selectCurrentlyPlayingTrack();
            updateShuffleModeUI();
        }
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
            aboutUsStage.initModality(Modality.APPLICATION_MODAL);
            aboutUsStage.setScene(new Scene(aboutUsRoot));

            aboutUsStage.showAndWait();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void handleQuitAction() {
        Stage stage = (Stage) closeAppButton.getScene().getWindow();
        // Close the stage.
        stage.close();

    }

    @FXML
    private void handleHideApp(){
        Stage stage = (Stage)hideButton.getScene().getWindow();
        stage.setIconified(!stage.isIconified());
    }

    @FXML
    private void handleShrink() throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(getClass().getResource("/folltrace/sonar/player_minimised.fxml"));
        Parent newRoot = fxmlLoader.load();
        MiniController miniController = fxmlLoader.getController();
        miniController.setSonarController(this);

        Stage shrinkStage = new Stage();
        Scene newScene = new Scene(newRoot);

        shrinkStage.getIcons().add(new Image("/logo.png"));
        shrinkStage.setResizable(false);
        shrinkStage.setTitle("Sonar");
        shrinkStage.setScene(newScene);
        shrinkStage.initStyle(StageStyle.UNDECORATED);
        shrinkStage.show();
        primaryStage.hide();
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
            if (playlist.isEmpty()) {
                String defaultImagePath = "/no_track_img.png";
                Image defaultImage = new Image(getClass().getResourceAsStream(defaultImagePath));

                albumCoverImageView.setImage(defaultImage);
                songName.setText("No tracks loaded");
                albumName.setText("No tracks loaded");
                authorName.setText("No tracks loaded");
                statusLabel.setText("No track selected");
                currentTimeLabel.setText("--:--");
            }
        }
    }


    @FXML
    private void handleSavePlaylist(){
        savePlaylistAsExtendedM3U();
    }

    @FXML
    private void handleLoadPlaylist(){
        loadPlaylist();
    }

    @FXML
    public void handleThemeChange() {
        isDarkTheme = darkThemeCheck.isSelected();
        UIManager.changeTheme(scene, isDarkTheme);
        if (isDarkTheme) {
            UIManager.setHoverEffectToButton(nextButton, "/icons_dark/next_dark.png", "/icons_dark/next_solid_dark.png", 15, 15);
            prevButton.setText(null);
            UIManager.setHoverEffectToButton(prevButton, "/icons_dark/prev_dark.png", "/icons_dark/prev_solid_dark.png", 15, 15);
            shuffleButton.setText(null);
            UIManager.setImageToButton(shuffleButton, "/icons_dark/shuffle_dark.png", 15, 15);
            toggleRepeatButton.setText(null);
            UIManager.setImageToButton(toggleRepeatButton, "/icons_dark/repeat_all_dark.png", 15, 15);
            togglePlayPauseButton.setText(null);
            UIManager.setHoverEffectToButton(togglePlayPauseButton, "/icons_dark/play_dark.png", "/icons_dark/play_solid_dark.png", 15, 15);
            stopButton.setText(null);
            UIManager.setHoverEffectToButton(stopButton,"/icons_dark/stop_dark.png", "/icons_dark/stop_solid_dark.png", 15, 15);
            deleteButton.setText(null);
            UIManager.setImageToButton(deleteButton, "/icons_dark/delete_dark.png", 24, 24);
            savePlaylistButton.setText(null);
            UIManager.setImageToButton(savePlaylistButton, "/icons_dark/save_dark.png", 24, 24);
            loadPlaylistButton.setText(null);
            UIManager.setImageToButton(loadPlaylistButton, "/icons_dark/load_dark.png", 24, 24);
            soundIcon.setImage(new Image(getClass().getResourceAsStream("/icons_dark/vol_min_dark.png")));
        } else {
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
            soundIcon.setImage(new Image(getClass().getResourceAsStream("/icons/vol_min.png")));
        }
    }

    // METHODS

    private void addFileToPlaylist(File file) {
        if (file != null && isSupportedFile(file.getName())) {
            String filePath = file.getAbsolutePath();

            // Check if the file path is already in the playlist
            if (!playlist.contains(filePath)) {
                String trackDuration = getTrackDuration(file);
                Track track = new Track(file.getName(), trackDuration); // Use actual duration
                fileListView.getItems().add(track);

                trackNamesToPaths.put(file.getName(), filePath);
                playlist.add(filePath);

                // Add to the original playlist as well
                if (!originalPlaylist.contains(filePath)) {
                    originalPlaylist.add(filePath);
                }
            }
        }
    }

    public void showMainWindow() {
        if (primaryStage != null) {
            primaryStage.show();
            primaryStage.toFront();
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
                if(isDarkTheme){
                    UIManager.setImageToButton(toggleRepeatButton, "/icons_dark/off_dark.png", 15, 15);
                } else {
                    UIManager.setImageToButton(toggleRepeatButton, "/icons/off.png", 15, 15);
                }
                break;
            case REPEAT_ALL:
                statusLabel.setText("Repeat: All");
                if (isDarkTheme) {
                    UIManager.setImageToButton(toggleRepeatButton, "/icons_dark/repeat_all_dark.png", 15, 15);
                } else {
                    UIManager.setImageToButton(toggleRepeatButton, "/icons/repeat_all.png", 15, 15);
                }
                break;
            case REPEAT_ONE:
                statusLabel.setText("Repeat: One");
                if (isDarkTheme) {
                    UIManager.setImageToButton(toggleRepeatButton, "/icons_dark/repeat_one_dark.png", 15, 15);
                } else {
                    UIManager.setImageToButton(toggleRepeatButton, "/icons/repeat_one.png", 15, 15);
                }
                break;
        }
    }

    private void updateShuffleModeUI() {
        // Update the UI based on the current shuffle state
        switch (shuffleState) {
            case OFF:
                statusLabel.setText("Shuffle: Off");
                if(isDarkTheme){
                    UIManager.setImageToButton(shuffleButton, "/icons_dark/off_dark.png", 15, 15);
                } else {
                    UIManager.setImageToButton(shuffleButton, "/icons/off.png", 15, 15);
                }
                break;
            case SHUFFLE_ALL:
                statusLabel.setText("Shuffled all tracks");
                if (isDarkTheme) {
                    UIManager.setImageToButton(shuffleButton, "/icons_dark/shuffle_dark.png", 15, 15);
                }
                UIManager.setImageToButton(shuffleButton, "/icons/shuffle.png", 15, 15);
                break;
            case SHUFFLE_NEXT:
                statusLabel.setText("Shuffled next tracks");
                if (isDarkTheme) {
                    UIManager.setImageToButton(shuffleButton, "/icons_dark/shuffle_dark.png", 15, 15);
                }
                UIManager.setImageToButton(shuffleButton, "/icons/shuffle.png", 15, 15);
                break;
        }
    }

    private void updateVolumeIcon(double volume) {
        String imagePath;
        if (volume == 0) {
            if (isDarkTheme) {
                imagePath = "/icons_dark/vol_mute_dark.png";
            } else {
                    imagePath = "/icons/vol_mute.png";
            }
        } else if (volume < 30) {
            if (isDarkTheme) {
                imagePath = "/icons_dark/vol_min_dark.png";
            } else {
                imagePath = "/icons/vol_min.png";
            }
        } else if (volume < 70) {
            if (isDarkTheme) {
                imagePath = "/icons_dark/vol_low_dark.png";
            } else {
                imagePath = "/icons/vol_low.png";
            }
        } else {
            if (isDarkTheme) {
                imagePath = "/icons_dark/vol_max_dark.png";
            } else {
                imagePath = "/icons/vol_max.png";
            }
        }
        Image image = new Image(getClass().getResourceAsStream(imagePath));
        soundIcon.setImage(image);
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

        if (isDarkTheme) {
            UIManager.setImageToButton(togglePlayPauseButton, "/icons_dark/pause_dark.png", 15, 15);
        } else {
            UIManager.setImageToButton(togglePlayPauseButton, "/icons/pause.png", 15, 15);
        }
        statusLabel.setText("Playing");
    }


    private void updatePlayPauseButton(boolean isPlaying) {
        togglePlayPauseButton.setText(null);
        if (isPlaying) {
            if (isDarkTheme) {
                UIManager.setHoverEffectToButton(togglePlayPauseButton, "/icons_dark/pause_dark.png", "/icons_dark/pause_solid_dark.png", 15, 15);
            } else {
                UIManager.setHoverEffectToButton(togglePlayPauseButton, "/icons/pause.png", "/icons/pause_solid.png", 15, 15);
            }
        } else {
            if (isDarkTheme) {
                UIManager.setHoverEffectToButton(togglePlayPauseButton, "/icons_dark/play_dark.png", "/icons_dark/play_solid_dark.png", 15, 15);
            }else {
                UIManager.setHoverEffectToButton(togglePlayPauseButton, "/icons/play.png", "/icons/play_solid.png", 15, 15);
            }
        }
    }


    @Override
    public void onNextTrack() {
        int currentIndex = fileListView.getSelectionModel().getSelectedIndex();
        int nextIndex = currentIndex + 1;

        if (nextIndex < playlist.size()) {
            playSelectedTrack(nextIndex);
            fileListView.getSelectionModel().select(nextIndex); // Update selection to next track
        } else if (repeatState == RepeatState.REPEAT_ALL) {
            playSelectedTrack(0); // Start from the beginning
            fileListView.getSelectionModel().select(0); // Update selection to first track
        } else {
            // Playback stopped, no repeat
            mediaPlayer.stop();
            updateCurrentTime();
            timeline.stop();
            statusLabel.setText("Playback stopped");
        }
    }

    public void onVolumeChanged(double newVolume) {
        // Update the volume on the MediaPlayer
        if (mediaPlayer != null) {
            mediaPlayer.setVolume(newVolume / 100); // Assuming newVolume is a percentage
        }

        // Update the main window's volume slider
        if (volumeSlider != null) {
            volumeSlider.setValue(newVolume);
        }

        // Update the mini window's volume slider if it's open
        if (miniController != null) {
            miniController.updateVolumeSlider(newVolume);
        }

        // Update the volume icon or other UI components if necessary
        updateVolumeIcon(newVolume);
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



    private String currentlyPlayingTrackPath = null;

    private void playSelectedTrack(int index) {
        if (index >= 0 && index < playlist.size()) {
            String filePath = playlist.get(index);
            currentlyPlayingTrackPath = filePath; // Store the path of the currently playing track

            // Store the current volume level
            double currentVolume = mediaPlayer.getVolume();

            // Create a new MediaPlayer for the new track
            player.playMedia(filePath);

            // Set the volume of the new MediaPlayer instance
            mediaPlayer.setVolume(currentVolume);

            updateCurrentTime();
            timeline.play();
            fileListView.getSelectionModel().select(index);

            // Clear the button text and set the appropriate icon
            togglePlayPauseButton.setText(null);
            updatePlayPauseButton(true);
        }
    }



    private void selectCurrentlyPlayingTrack() {
        if (currentlyPlayingTrackPath != null) {
            int index = playlist.indexOf(currentlyPlayingTrackPath);
            if (index != -1) {
                fileListView.getSelectionModel().select(index);
            }
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
                return formatTrackLength(durationMs);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "Unknown";
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
            Duration currentTime = mp.getCurrentTime();
            currentTimeLabel.setText(formatDuration(currentTime));
        }
    }


    private String formatDuration(Duration duration) {
        int minutes = (int) duration.toMinutes();
        int seconds = (int) duration.toSeconds() % 60;
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


    private void shufflePlaylistAll() {
        if (!playlist.isEmpty()) {
            int currentIndex = fileListView.getSelectionModel().getSelectedIndex();
            String currentTrack = playlist.get(currentIndex);

            Collections.shuffle(playlist);

            int newIndex = playlist.indexOf(currentTrack);
            playlist.remove(newIndex);
            playlist.add(0, currentTrack);

            updateListView();
            fileListView.getSelectionModel().select(0); // Select the first item (current track)
        }
    }

    private void shufflePlaylistNext() {
        int currentIndex = fileListView.getSelectionModel().getSelectedIndex();
        if (currentIndex < playlist.size() - 1) {
            List<String> remainingTracks = new ArrayList<>(playlist.subList(currentIndex + 1, playlist.size()));
            Collections.shuffle(remainingTracks);
            playlist = new ArrayList<>(playlist.subList(0, currentIndex + 1));
            playlist.addAll(remainingTracks);

            updateListView();
            fileListView.getSelectionModel().select(currentIndex); // Keep the current track selected
        }
    }


    private void updateListView() {
        fileListView.getItems().clear();
        for (String filePath : playlist) {
            File file = new File(filePath);
            String trackName = file.getName();
            String trackDuration = getTrackDuration(file);
            Track track = new Track(trackName, trackDuration);
            fileListView.getItems().add(track);
        }
    }

    private void restoreOriginalOrder() {
        playlist.clear();
        playlist.addAll(originalPlaylist);
    }

    public void savePlaylistAsExtendedM3U() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Playlist");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("M3U Files", "*.m3u")
        );

        File file = fileChooser.showSaveDialog(null);

        if (file != null) {
            try (PrintWriter writer = new PrintWriter(file)) {
                writer.println("#EXTM3U");
                for (String filePath : playlist) {
                    Mp3File mp3file = new Mp3File(filePath);
                    if (mp3file.hasId3v2Tag()) {
                        ID3v2 id3v2Tag = mp3file.getId3v2Tag();
                        String artist = id3v2Tag.getArtist();
                        String title = id3v2Tag.getTitle();
                        long duration = mp3file.getLengthInSeconds();

                        writer.println("#EXTINF:" + duration + "," + artist + " - " + title);
                        writer.println(filePath);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
    public void loadPlaylist() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open Playlist");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("M3U Files", "*.m3u")
        );

        File file = fileChooser.showOpenDialog(null);

        if (file != null) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("#") && !line.trim().isEmpty()) {
                        addFileToPlaylist(new File(line));
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

}
