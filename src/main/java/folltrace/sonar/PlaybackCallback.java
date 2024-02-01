package folltrace.sonar;

import javafx.scene.media.MediaPlayer;

public interface PlaybackCallback {
    void onMediaReady(MediaPlayer duration);
    void onNextTrack();
    void onPreviousTrack();
}
