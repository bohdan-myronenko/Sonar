module folltrace.sonar {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.media;
    requires java.desktop;


    opens folltrace.sonar to javafx.fxml;
    exports folltrace.sonar;
}