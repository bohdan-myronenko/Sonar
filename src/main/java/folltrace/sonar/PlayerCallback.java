package folltrace.sonar;

import javafx.scene.media.MediaPlayer;

public interface PlayerCallback {
    void onMediaReady(MediaPlayer duration);
    void onNextTrack();
    void onPreviousTrack();
    RepeatState getRepeatState();
}
