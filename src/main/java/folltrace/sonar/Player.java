package folltrace.sonar;

import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

import java.io.File;

public class Player {
    private MediaPlayer mediaPlayer;
    private PlayerCallback callback;

    public Player(PlayerCallback callback){
        this.callback = callback;
    }
    public void playMedia(String filePath) {
        double currentVolume = 1.0; // Default volume level

        // Check if a MediaPlayer already exists and store its volume level
        if (mediaPlayer != null) {
            currentVolume = mediaPlayer.getVolume();
            mediaPlayer.stop();
            mediaPlayer.dispose();
        }

        Media media = new Media(new File(filePath).toURI().toString());
        this.mediaPlayer = new MediaPlayer(media);

        // Set the volume of the new MediaPlayer to the stored volume level
        mediaPlayer.setVolume(currentVolume);

        mediaPlayer.setOnEndOfMedia(() -> {
            if (callback.getRepeatState() != RepeatState.REPEAT_ONE) {
                callback.onNextTrack();
            } else {
                mediaPlayer.seek(Duration.ZERO);
                mediaPlayer.play();
            }
        });

        mediaPlayer.setOnReady(() -> {
            callback.onMediaReady(mediaPlayer);
        });

        mediaPlayer.play();
    }

    public void stopMedia() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.dispose();
            mediaPlayer = null;
        }
    }




    public MediaPlayer getMediaPlayer(){
        return this.mediaPlayer;
    }
}
