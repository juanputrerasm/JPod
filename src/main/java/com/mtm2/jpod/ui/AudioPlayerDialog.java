package com.mtm2.jpod.ui;

import com.mtm2.jpod.ui.audio.AudioPlayer;
import com.mtm2.jpod.ui.audio.ModulePlayer;
import com.mtm2.jpod.ui.audio.WavePlayer;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Locale;

/**
 * Shared WAV and MOD transport: play, pause/resume, stop, a seek bar and the elapsed
 * and total time. A {@code .mod} entry plays through JPod's own tracker renderer, so
 * nothing needs installing for it.
 */
public final class AudioPlayerDialog extends JDialog {

    private static final int SEEK_STEPS = 1000;

    private final AudioPlayer player;
    private final JLabel timeLabel = new JLabel(" ", SwingConstants.CENTER);
    private final JSlider seekBar = new JSlider(0, SEEK_STEPS, 0);
    private final JButton playButton = new JButton("Play");
    private final JButton pauseButton = new JButton("Pause");
    private final JButton stopButton = new JButton("Stop");
    private final Timer ticker;
    private boolean updatingSeek;

    public AudioPlayerDialog(Window owner, String entryName, byte[] data) {
        super(owner, "Audio Player - " + entryName, ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        player = entryName.toUpperCase(Locale.ROOT).endsWith(".MOD")
                ? new ModulePlayer(data) : new WavePlayer(data);
        ticker = new Timer(100, event -> updateTransport());

        timeLabel.setFont(timeLabel.getFont().deriveFont(Font.BOLD, 15f));
        seekBar.setEnabled(player.isAvailable() && player.canSeek() && player.durationMicros() > 0);
        playButton.setEnabled(player.isAvailable());
        pauseButton.setEnabled(player.isAvailable());
        stopButton.setEnabled(player.isAvailable());

        playButton.addActionListener(event -> {
            if (player.state() == AudioPlayer.State.PAUSED) player.resume();
            else player.play();
            ticker.start();
            updateTransport();
        });
        pauseButton.addActionListener(event -> {
            if (player.state() == AudioPlayer.State.PAUSED) player.resume();
            else player.pause();
            ticker.start();
            updateTransport();
        });
        stopButton.addActionListener(event -> {
            player.stop();
            updateTransport();
        });
        seekBar.addChangeListener(event -> {
            if (updatingSeek || seekBar.getValueIsAdjusting()) return;
            long target = player.durationMicros() * seekBar.getValue() / SEEK_STEPS;
            player.seek(target);
            updateTransport();
        });

        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(event -> dispose());
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) {
                ticker.stop();
                player.close();
            }
        });
        getRootPane().registerKeyboardAction(event -> dispose(),
                KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        buttons.add(playButton);
        buttons.add(pauseButton);
        buttons.add(stopButton);
        buttons.add(closeButton);

        JPanel content = new JPanel(new GridLayout(3, 1, 0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(timeLabel);
        content.add(seekBar);
        content.add(buttons);
        setContentPane(content);

        updateTransport();
        pack();
        setMinimumSize(new Dimension(420, getHeight()));
        setResizable(false);
        setLocationRelativeTo(owner);

        if (!player.isAvailable()) {
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                    player.errorMessage() != null ? player.errorMessage() : "Cannot play this audio file.",
                    "JPod", JOptionPane.WARNING_MESSAGE));
        }
    }

    private void updateTransport() {
        if (!player.isAvailable()) {
            timeLabel.setText("N/A");
            return;
        }
        long position = player.positionMicros();
        long duration = player.durationMicros();
        timeLabel.setText(format(position) + " / " + (duration > 0 ? format(duration) : "unknown"));
        if (!seekBar.getValueIsAdjusting() && duration > 0) {
            updatingSeek = true;
            seekBar.setValue((int) Math.round(Math.max(0, Math.min(1, position / (double) duration)) * SEEK_STEPS));
            updatingSeek = false;
        }
        pauseButton.setText(player.state() == AudioPlayer.State.PAUSED ? "Resume" : "Pause");
        if (player.state() == AudioPlayer.State.STOPPED) ticker.stop();
    }

    /** Elapsed time as {@code m:ss.fff}, or {@code h:mm:ss.fff} past an hour. */
    static String format(long micros) {
        long millis = Math.max(0, micros / 1000);
        long hours = millis / 3_600_000;
        long minutes = millis / 60_000 % 60;
        double seconds = (millis % 60_000) / 1000.0;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%06.3f", hours, minutes, seconds)
                : String.format(Locale.ROOT, "%d:%06.3f", millis / 60_000, seconds);
    }
}
