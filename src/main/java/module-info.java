module folltrace.sonar {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.desktop;
    requires com.sun.jna;

    opens folltrace.sonar to javafx.fxml;
    exports folltrace.sonar;
}
