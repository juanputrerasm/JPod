package com.mtm2.jpod;

import com.mtm2.jpod.ui.MainWindow;

import javax.swing.*;

/**
 * Application entry point for JPod.
 *
 * Sets the system look-and-feel (so the app looks native on macOS, Windows,
 * and Linux) then opens the main window on the Event Dispatch Thread.
 */
public final class JPodApp {

    private JPodApp() {}

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Fall back to the default cross-platform look and feel; not fatal.
        }

        // An archive passed on the command line, or dropped on a launcher, opens at once.
        java.nio.file.Path startupFile = args.length > 0 && java.nio.file.Files.isRegularFile(
                java.nio.file.Path.of(args[0])) ? java.nio.file.Path.of(args[0]) : null;
        SwingUtilities.invokeLater(() -> {
            MainWindow window = new MainWindow();
            window.setVisible(true);
            if (startupFile != null) {
                window.openAtStartup(startupFile);
            }
        });
    }
}
