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
            // Fall back to the default cross-platform L&F — non-fatal.
        }

        SwingUtilities.invokeLater(() -> {
            MainWindow window = new MainWindow();
            window.setVisible(true);
        });
    }
}
