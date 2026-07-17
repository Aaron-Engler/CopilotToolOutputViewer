# Copilot Tool Output Viewer

Small JavaFX desktop utility for watching the latest Copilot tool output file in real time.

The application scans the system temp directory for files matching:

`copilot-tool-output-<timestamp>-<id>.txt`

It opens the most recent matching file, refreshes once per second, and shows:

- the file path
- creation time
- last modified time
- the current file contents in a large-file-friendly viewer

The viewer is intended for local debugging and inspection of tool output without repeatedly reopening temp files by hand.

## Behavior

- Polls the OS temp directory every second
- Tracks the latest matching file by filename timestamp and file timestamps
- Displays the full file in a virtualized line list for better performance on large outputs
- Updates automatically when the file content changes
- Removes leading `<absolute-path>:<line>:` prefixes from displayed lines
- Lets you select one or more lines and mirrors them into a read-only text area below
- Pauses list updates while lines are selected so inspection and copying stay stable
- Resumes auto-update when the selection is cleared
- Supports light and dark mode
- Uses `Consolas` for the output controls with a generic `monospace` fallback

## Requirements

- JDK 21
- Maven 3.9+
- Windows is the primary target in the current build configuration

## Run locally

```bash
mvn clean package
java -jar target/CopilotToolOutputViewer-1.0-SNAPSHOT.jar
```

## Project structure

```text
src/main/java/be/aaronengler/cptviewer/
  CopilotToolViewerLauncher.java
  CopilotToolViewerApp.java
  CopilotToolViewerController.java
src/main/resources/
  viewer.fxml
  dark.css
  light.css
  logback.xml
```

- `CopilotToolViewerLauncher` is the entry point used by the packaged application.
- `CopilotToolViewerApp` loads the FXML view, applies the active stylesheet, and starts the UI.
- `CopilotToolViewerController` contains the viewer behavior, polling logic, selection handling, and copy actions.
- `viewer.fxml` defines the layout.
- `dark.css` and `light.css` define the theme styling.

## Release flow

The repository includes a GitHub Actions workflow at `.github/workflows/release.yml` that:

1. accepts a version input
2. builds the application on Windows with JDK 21
3. zips the generated app image
4. publishes the zip as a GitHub release asset

## Notes

- The app reads from the system temp directory defined by `java.io.tmpdir`.
- No file contents are modified; the viewer is read-only.
- The lower text area is intended for partial text selection and copy after selecting rows in the main list.
