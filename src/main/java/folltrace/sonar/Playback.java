package folltrace.sonar;

import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;

import java.io.File;

public class Playback {
    private MediaPlayer mediaPlayer;
    private PlaybackCallback callback;

    public Playback(PlaybackCallback callback){
        this.callback = callback;
    }
    public void playMedia(String filePath) {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.dispose(); // Dispose the old MediaPlayer
        }
        Media media = new Media(new File(filePath).toURI().toString());
        mediaPlayer = new MediaPlayer(media);

        mediaPlayer.setOnReady(() -> {
            callback.onMediaReady(mediaPlayer);
        });


        mediaPlayer.play();
    }

    public MediaPlayer getMediaPlayer(){
        return mediaPlayer;
    }
}
