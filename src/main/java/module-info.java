module folltrace.sonar {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.media;
    requires javafx.swing;
    requires java.desktop;
    requires mp3agic;

    opens folltrace.sonar to javafx.fxml;
    exports folltrace.sonar;
}
