module folltrace.sonar {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.media;
    requires java.desktop;
    requires tika.core;
    requires org.apache.tika.parser.audiovideo;
    requires mp3agic;
    requires javafx.swing;


    opens folltrace.sonar to javafx.fxml;
    exports folltrace.sonar;
}