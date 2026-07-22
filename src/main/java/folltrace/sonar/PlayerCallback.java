package folltrace.sonar;

public interface PlayerCallback {
    /** Called when a track is loaded and its duration is known. */
    void onMediaReady(double durationSeconds);
    void onNextTrack();
    void onPreviousTrack();
    RepeatState getRepeatState();
    ShuffleState getShuffleState();
}
