package folltrace.sonar;

/**
 * Bootstrap launcher for environments where JavaFX modules are not on the
 * boot module path. Use {@code SonarMain} directly when launching with
 * {@code mvn javafx:run} or when JavaFX is on the module path.
 */
public class Launcher {
    public static void main(String[] args) {
        SonarMain.main(args);
    }
}
