package com.mtm2.jpod.ui;

import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Simple WAV audio player dialog.
 *
 * <p>Plays PCM WAV data from a byte array using {@link javax.sound.sampled}.
 * Shows playback length, current position, and Play / Pause-Stop controls.
 * Displays an error message for unsupported audio formats.
 */
public final class AudioPlayerDialog extends JDialog {

    private Clip clip;
    private final JLabel positionLabel = new JLabel("0");
    private final JLabel lengthLabel = new JLabel("0");
    private final JButton playBtn = new JButton("Play");
    private final JButton stopBtn = new JButton("Pause / Stop");
    private final Timer positionTimer;

    public AudioPlayerDialog(Frame owner, String entryName, byte[] data) {
        super(owner, "Audio Player - " + entryName, true);
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        positionTimer = new Timer(250, e -> updatePosition());

        try {
            AudioInputStream ais = AudioSystem.getAudioInputStream(
                    new ByteArrayInputStream(data));
            clip = AudioSystem.getClip();
            clip.open(ais);
            long frames = clip.getFrameLength();
            float rate = clip.getFormat().getFrameRate();
            long totalMs = (long) (frames / rate * 1000);
            lengthLabel.setText(totalMs + " ms");

            playBtn.addActionListener(e -> {
                clip.start();
                positionTimer.start();
            });
            stopBtn.addActionListener(e -> {
                clip.stop();
                positionTimer.stop();
            });

            addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosing(java.awt.event.WindowEvent e) {
                    positionTimer.stop();
                    if (clip != null) { clip.stop(); clip.close(); }
                }
            });
        } catch (UnsupportedAudioFileException | LineUnavailableException | IOException ex) {
            playBtn.setEnabled(false);
            stopBtn.setEnabled(false);
            lengthLabel.setText("N/A");
            JOptionPane.showMessageDialog(this, "Cannot play this audio file:\n" + ex.getMessage(),
                    "JPod", JOptionPane.WARNING_MESSAGE);
        }

        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> {
            positionTimer.stop();
            if (clip != null) { clip.stop(); clip.close(); }
            dispose();
        });

        JPanel info = new JPanel(new GridLayout(2, 2, 6, 4));
        info.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        info.add(new JLabel("Length:"));
        info.add(lengthLabel);
        info.add(new JLabel("Position:"));
        info.add(positionLabel);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        btnRow.add(playBtn);
        btnRow.add(stopBtn);
        btnRow.add(closeBtn);

        setLayout(new BorderLayout(4, 4));
        add(info, BorderLayout.CENTER);
        add(btnRow, BorderLayout.SOUTH);
        pack();
        setMinimumSize(new Dimension(280, 130));
        setLocationRelativeTo(owner);
    }

    private void updatePosition() {
        if (clip != null) {
            long ms = clip.getMicrosecondPosition() / 1000;
            positionLabel.setText(ms + " ms");
        }
    }
}
