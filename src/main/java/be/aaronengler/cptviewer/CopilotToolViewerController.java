package be.aaronengler.cptviewer;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CopilotToolViewerController {
    private static final Path TEMP_DIRECTORY = Path.of(System.getProperty("java.io.tmpdir"));
    private static final Pattern FILE_PATTERN = Pattern.compile("copilot-tool-output-(\\d+)-[a-zA-Z0-9]+\\.txt");
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final KeyCombination COPY_KEY_COMBINATION =
            new KeyCodeCombination(KeyCode.C, KeyCombination.CONTROL_DOWN);

    @FXML
    private SplitPane outputPane;

    @FXML
    private Label fileNameLabel;

    @FXML
    private Label createdLabel;

    @FXML
    private Label modifiedLabel;

    @FXML
    private Label statusLabel;

    @FXML
    private CheckBox darkModeToggle;

    @FXML
    private Button clearSelectionButton;

    @FXML
    private ListView<String> outputList;

    @FXML
    private TextArea selectionArea;

    private ScheduledExecutorService scheduler;
    private FileSnapshot lastSnapshot;

    @FXML
    private void initialize() {
        outputList.setFocusTraversable(true);
        outputList.setFixedCellSize(20);
        outputList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        outputList.getSelectionModel().getSelectedItems().addListener((ListChangeListener<String>) ignored ->
                syncSelectionArea());
        outputList.setCellFactory(ignored -> new OutputLineCell());
        outputList.setOnKeyPressed(event -> {
            if (COPY_KEY_COMBINATION.match(event)) {
                copySelectedLines();
                event.consume();
            }
        });

        selectionArea.setEditable(false);
        selectionArea.setWrapText(false);
        selectionArea.setFocusTraversable(false);

        clearSelectionButton.disableProperty().bind(outputList.getSelectionModel().selectedItemProperty().isNull());
        clearSelectionButton.setOnAction(ignored -> clearSelection());

        installCopyContextMenu();
        darkModeToggle.setSelected(true);
        outputPane.setDividerPositions(0.75);
    }

    void startPolling() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(new PollerThreadFactory());
        scheduler.scheduleAtFixedRate(this::pollLatestFile, 0, 1, TimeUnit.SECONDS);
    }

    void stopPolling() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    CheckBox darkModeToggle() {
        return darkModeToggle;
    }

    private void pollLatestFile() {
        try {
            Optional<Path> latestFile = findLatestMatchingFile();
            if (latestFile.isEmpty()) {
                if (lastSnapshot != null) {
                    lastSnapshot = null;
                    Platform.runLater(this::applyEmptyState);
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
        List<String> lines = sanitizeLines(path, Files.readAllLines(path, StandardCharsets.UTF_8));
        return new FileSnapshot(path, attributes.creationTime(), attributes.lastModifiedTime(), lines);
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

    private List<String> sanitizeLines(Path path, List<String> lines) {
        Pattern linePrefixPattern = Pattern.compile("^\\s*" + Pattern.quote(path.toAbsolutePath().toString()) + ":\\d+:");
        List<String> sanitizedLines = new ArrayList<>(lines.size());
        for (String line : lines) {
            Matcher matcher = linePrefixPattern.matcher(line);
            sanitizedLines.add(matcher.replaceFirst(""));
        }
        return List.copyOf(sanitizedLines);
    }

    private long timestampFromFileName(Path path) {
        var matcher = FILE_PATTERN.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            return Long.MIN_VALUE;
        }
        return Long.parseLong(matcher.group(1));
    }

    private void applySnapshot(FileSnapshot snapshot) {
        if (!outputList.getSelectionModel().getSelectedItems().isEmpty()) {
            statusLabel.setText("Status: paused while selection is active");
            return;
        }

        fileNameLabel.setText("File: " + snapshot.path());
        createdLabel.setText("Created: " + formatTime(snapshot.created()));
        modifiedLabel.setText("Modified: " + formatTime(snapshot.modified()));
        statusLabel.setText("Status: polling every second");
        outputList.setItems(FXCollections.observableArrayList(snapshot.lines()));
        selectionArea.clear();
        if (!snapshot.lines().isEmpty()) {
            outputList.scrollTo(snapshot.lines().size() - 1);
        }
    }

    private void applyEmptyState() {
        if (!outputList.getSelectionModel().getSelectedItems().isEmpty()) {
            statusLabel.setText("Status: paused while selection is active");
            return;
        }

        fileNameLabel.setText("File: searching...");
        createdLabel.setText("Created: -");
        modifiedLabel.setText("Modified: -");
        statusLabel.setText("Status: waiting for matching files");
        outputList.getItems().clear();
        selectionArea.clear();
    }

    private void installCopyContextMenu() {
        MenuItem copyItem = new MenuItem("Copy");
        copyItem.setOnAction(ignored -> copySelectedLines());
        copyItem.disableProperty().bind(outputList.getSelectionModel().selectedItemProperty().isNull());
        outputList.setContextMenu(new ContextMenu(copyItem));
    }

    private void syncSelectionArea() {
        List<String> selectedLines = outputList.getSelectionModel().getSelectedItems();
        if (selectedLines.isEmpty()) {
            selectionArea.clear();
            statusLabel.setText(lastSnapshot != null ? "Status: polling every second" : "Status: waiting for matching files");
            outputList.refresh();
            return;
        }

        selectionArea.setText(String.join(System.lineSeparator(), selectedLines));
        selectionArea.positionCaret(0);
        statusLabel.setText("Status: paused while selection is active");
        outputList.refresh();
    }

    private void clearSelection() {
        outputList.getSelectionModel().clearSelection();
    }

    private void copySelectedLines() {
        List<String> selectedLines = outputList.getSelectionModel().getSelectedItems();
        if (selectedLines.isEmpty()) {
            return;
        }

        ClipboardContent clipboardContent = new ClipboardContent();
        clipboardContent.putString(String.join(System.lineSeparator(), selectedLines));
        Clipboard.getSystemClipboard().setContent(clipboardContent);
    }

    private String formatTime(FileTime time) {
        return DATE_FORMATTER.format(time.toInstant());
    }

    private record FileSnapshot(Path path, FileTime created, FileTime modified, List<String> lines) {
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
                    && Objects.equals(lines, snapshot.lines);
        }

        @Override
        public int hashCode() {
            return Objects.hash(path, created, modified, lines);
        }
    }

    private static final class OutputLineCell extends ListCell<String> {
        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setStyle(null);
                return;
            }

            setText(item);
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
