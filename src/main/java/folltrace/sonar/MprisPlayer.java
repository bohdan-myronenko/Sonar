package folltrace.sonar;

import java.util.List;

/**
 * Interface implemented by SonarController so the MPRIS D-Bus service
 * can query/control playback without tight coupling.
 */
public interface MprisPlayer {

    enum PlaybackStatus { Playing, Paused, Stopped }

    PlaybackStatus getPlaybackStatus();
    void play();
    void pause();
    void playPause();
    void stop();
    void next();
    void previous();
    void seek(long offsetMicros);
    void setPosition(String trackId, long positionMicros);
    void openUri(String uri);
    void raise();

    String  getTrackId();
    long    getTrackDurationMicros();
    String  getTrackTitle();
    String  getTrackArtist();
    String  getTrackAlbum();
    String  getTrackArtUrl();
    byte[]  getTrackArtData();

    double  getVolume();
    void    setVolume(double vol);

    long    getPositionMicros();
    double  getMinimumRate();
    double  getMaximumRate();
    double  getRate();
    void    setRate(double rate);

    boolean getShuffle();
    void    setShuffle(boolean enabled);

    String  getLoopStatus();
    void    setLoopStatus(String status);

    boolean canGoNext();
    boolean canGoPrevious();
    boolean canPlay();
    boolean canPause();
    boolean canSeek();
    boolean canControl();

    int     getTrackCount();
    List<String> getTrackIds();
}
