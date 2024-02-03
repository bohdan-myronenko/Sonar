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
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.dispose();
        }

        Media media = new Media(new File(filePath).toURI().toString());
        mediaPlayer = new MediaPlayer(media);

        mediaPlayer.setOnEndOfMedia(() -> {
            if (callback.getRepeatState() != RepeatState.REPEAT_ONE) {
                callback.onNextTrack();
            } else {
                mediaPlayer.seek(Duration.ZERO); // Restart the current track in 'Repeat One' mode
                mediaPlayer.play();
            }
        });

        mediaPlayer.setOnReady(() -> {
            callback.onMediaReady(mediaPlayer);
        });


        mediaPlayer.play();
    }



    public MediaPlayer getMediaPlayer(){
        return mediaPlayer;
    }
}
