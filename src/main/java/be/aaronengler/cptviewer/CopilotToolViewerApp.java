package be.aaronengler.cptviewer;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CopilotToolViewerApp {
    private CopilotToolViewerApp() {
    }

    public static void main(String[] args) {
        Application.launch(ViewerApplication.class, args);
    }

    public static final class ViewerApplication extends Application {
        private static final Path TEMP_DIRECTORY = Path.of(System.getProperty("java.io.tmpdir"));
        private static final Pattern FILE_PATTERN = Pattern.compile("copilot-tool-output-(\\d+)-[a-zA-Z0-9]+\\.txt");
        private static final DateTimeFormatter DATE_FORMATTER =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

        private final Label fileNameLabel = new Label("File: searching...");
        private final Label createdLabel = new Label("Created: -");
        private final Label modifiedLabel = new Label("Modified: -");
        private final Label statusLabel = new Label("Status: waiting for matching files");
        private final CheckBox darkModeToggle = new CheckBox("Dark mode");
        private final TextArea outputArea = new TextArea();

        private ScheduledExecutorService scheduler;
        private FileSnapshot lastSnapshot;

        @Override
        public void start(Stage stage) {
            outputArea.setEditable(false);
            outputArea.setWrapText(false);
            outputArea.setFocusTraversable(true);
            VBox.setVgrow(outputArea, Priority.ALWAYS);

            Region headerSpacer = new Region();
            HBox.setHgrow(headerSpacer, Priority.ALWAYS);

            HBox header = new HBox(8, fileNameLabel, headerSpacer, darkModeToggle);
            header.setAlignment(Pos.CENTER_LEFT);

            VBox root = new VBox(8, header, createdLabel, modifiedLabel, statusLabel, outputArea);
            root.setPadding(new Insets(12));

            darkModeToggle.setSelected(true);

            stage.setTitle("Copilot Tool Output Viewer");
            Scene scene = new Scene(root, 1000, 700);
            stage.setScene(scene);
            darkModeToggle.selectedProperty().addListener((ignored, oldValue, darkModeEnabled) ->
                    applyTheme(root, darkModeEnabled));
            applyTheme(root, darkModeToggle.isSelected());
            stage.show();

            scheduler = Executors.newSingleThreadScheduledExecutor(new PollerThreadFactory());
            scheduler.scheduleAtFixedRate(this::pollLatestFile, 0, 1, TimeUnit.SECONDS);
        }

        @Override
        public void stop() {
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
        }

        private void pollLatestFile() {
            try {
                Optional<Path> latestFile = findLatestMatchingFile();
                if (latestFile.isEmpty()) {
                    if (lastSnapshot != null) {
                        lastSnapshot = null;
                        Platform.runLater(() -> {
                            fileNameLabel.setText("File: searching...");
                            createdLabel.setText("Created: -");
                            modifiedLabel.setText("Modified: -");
                            statusLabel.setText("Status: waiting for matching files");
                            outputArea.clear();
                        });
                    }
                    return;
                }

                FileSnapshot currentSnapshot = buildSnapshot(latestFile.get());
                if (!currentSnapshot.equals(lastSnapshot)) {
                    lastSnapshot = currentSnapshot;
                    Platform.runLater(() -> applySnapshot(currentSnapshot));
                }
            } catch (Exception exception) {
                Platform.runLater(() -> statusLabel.setText("Status: " + exception.getMessage()));
            }
        }

        private Optional<Path> findLatestMatchingFile() throws IOException {
            if (!Files.isDirectory(TEMP_DIRECTORY)) {
                return Optional.empty();
            }

            try (var stream = Files.list(TEMP_DIRECTORY)) {
                return stream
                        .filter(Files::isRegularFile)
                        .filter(path -> FILE_PATTERN.matcher(path.getFileName().toString()).matches())
                        .max(Comparator.comparingLong(this::timestampFromFileName)
                                .thenComparing(this::latestRelevantTimestamp)
                                .thenComparing(path -> path.getFileName().toString()));
            }
        }

        private FileSnapshot buildSnapshot(Path path) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            String content = sanitizeContent(path, Files.readString(path, StandardCharsets.UTF_8));
            return new FileSnapshot(
                    path,
                    attributes.creationTime(),
                    attributes.lastModifiedTime(),
                    content
            );
        }

        private FileTime latestRelevantTimestamp(Path path) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                return attributes.lastModifiedTime().compareTo(attributes.creationTime()) >= 0
                        ? attributes.lastModifiedTime()
                        : attributes.creationTime();
            } catch (IOException exception) {
                return FileTime.fromMillis(Long.MIN_VALUE);
            }
        }

        private String sanitizeContent(Path path, String content) {
            Pattern linePrefixPattern = Pattern.compile("(?m)^\\s*" + Pattern.quote(path.toAbsolutePath().toString()) + ":\\d+:");
            Matcher matcher = linePrefixPattern.matcher(content);
            return matcher.replaceAll("");
        }

        private long timestampFromFileName(Path path) {
            var matcher = FILE_PATTERN.matcher(path.getFileName().toString());
            if (!matcher.matches()) {
                return Long.MIN_VALUE;
            }
            return Long.parseLong(matcher.group(1));
        }

        private void applySnapshot(FileSnapshot snapshot) {
            fileNameLabel.setText("File: " + snapshot.path().toString());
            createdLabel.setText("Created: " + formatTime(snapshot.created()));
            modifiedLabel.setText("Modified: " + formatTime(snapshot.modified()));
            statusLabel.setText("Status: polling every second");
            outputArea.setText(snapshot.content());
            outputArea.positionCaret(outputArea.getLength());
        }

        private void applyTheme(VBox root, boolean darkModeEnabled) {
            String labelStyle;
            String toggleStyle;
            String areaStyle;

            if (darkModeEnabled) {
                root.setStyle("-fx-background-color: #1e1e1e;");
                labelStyle = "-fx-text-fill: #e6e6e6;";
                toggleStyle = "-fx-text-fill: #e6e6e6;";
                areaStyle = "-fx-control-inner-background: #252526; -fx-text-fill: #e6e6e6; -fx-highlight-fill: #3a78c2; -fx-highlight-text-fill: white; -fx-font-family: 'Consolas', monospace;";
            } else {
                root.setStyle("-fx-background-color: #f4f4f4;");
                labelStyle = "-fx-text-fill: #1f1f1f;";
                toggleStyle = "-fx-text-fill: #1f1f1f;";
                areaStyle = "-fx-control-inner-background: white; -fx-text-fill: #1f1f1f; -fx-highlight-fill: #cfe8ff; -fx-highlight-text-fill: #1f1f1f; -fx-font-family: 'Consolas', monospace;";
            }

            fileNameLabel.setStyle(labelStyle);
            createdLabel.setStyle(labelStyle);
            modifiedLabel.setStyle(labelStyle);
            statusLabel.setStyle(labelStyle);
            darkModeToggle.setStyle(toggleStyle);
            outputArea.setStyle(areaStyle);
        }

        private String formatTime(FileTime time) {
            return DATE_FORMATTER.format(time.toInstant());
        }
    }

    private record FileSnapshot(Path path, FileTime created, FileTime modified, String content) {
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FileSnapshot snapshot)) {
                return false;
            }
            return Objects.equals(path, snapshot.path)
                    && Objects.equals(created, snapshot.created)
                    && Objects.equals(modified, snapshot.modified)
                    && Objects.equals(content, snapshot.content);
        }

        @Override
        public int hashCode() {
            return Objects.hash(path, created, modified, content);
        }
    }

    private static final class PollerThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "copilot-tool-output-poller");
            thread.setDaemon(true);
            return thread;
        }
    }
}
