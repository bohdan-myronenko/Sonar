module folltrace.sonar {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.media;
    requires javafx.swing;
    requires java.desktop;
    requires org.apache.tika.core;
    requires org.apache.tika.parser.audiovideo;
    requires mp3agic;

    opens folltrace.sonar to javafx.fxml;
    exports folltrace.sonar;
}
