module folltrace.sonar {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.media;


    opens folltrace.sonar to javafx.fxml;
    exports folltrace.sonar;
}