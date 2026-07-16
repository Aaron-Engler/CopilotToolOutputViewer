package be.aaronengler.cptviewer;

import javafx.application.Application;

public final class CopilotToolViewerLauncher {
    private CopilotToolViewerLauncher() {
    }

    public static void main(String[] args) {
        Application.launch(CopilotToolViewerApp.ViewerApplication.class, args);
    }
}
