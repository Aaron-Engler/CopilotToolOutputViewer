package be.aaronengler.cptviewer;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Objects;

public final class CopilotToolViewerApp {
    private CopilotToolViewerApp() {
    }

    public static void main(String[] args) {
        Application.launch(ViewerApplication.class, args);
    }

    public static final class ViewerApplication extends Application {
        private static final String DARK_THEME_STYLESHEET = "dark.css";
        private static final String LIGHT_THEME_STYLESHEET = "light.css";

        private CopilotToolViewerController controller;

        @Override
        public void start(Stage stage) throws IOException {
            FXMLLoader loader = new FXMLLoader(ViewerApplication.class.getResource("/viewer.fxml"));
            VBox root = loader.load();
            controller = loader.getController();

            Scene scene = new Scene(root, 1000, 700);
            stage.setTitle("Copilot Tool Output Viewer");
            stage.setScene(scene);

            controller.darkModeToggle().selectedProperty().addListener((ignored, oldValue, darkModeEnabled) ->
                    applyTheme(scene, darkModeEnabled));
            applyTheme(scene, controller.darkModeToggle().isSelected());

            stage.show();
            controller.startPolling();
        }

        @Override
        public void stop() {
            if (controller != null) {
                controller.stopPolling();
            }
        }

        private void applyTheme(Scene scene, boolean darkModeEnabled) {
            scene.getStylesheets().setAll(resolveStylesheet(darkModeEnabled ? DARK_THEME_STYLESHEET : LIGHT_THEME_STYLESHEET));
        }

        private String resolveStylesheet(String stylesheetName) {
            return Objects.requireNonNull(
                    ViewerApplication.class.getResource("/" + stylesheetName),
                    "Missing stylesheet: " + stylesheetName
            ).toExternalForm();
        }
    }
}
