package com.mtm2.jpod.ui;

import com.mtm2.jpod.PodSession;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;

/**
 * Dialog for configuring a new POD archive build.
 *
 * <p>Collects the output folder, filename, and optional 80-character archive
 * comment before the caller writes the file via {@link com.mtm2.jpod.io.pod.PodArchiveWriter}.
 */
public final class BuildArchiveDialog extends JDialog {

    private final JTextField nameField = new JTextField(20);
    private final JTextField commentField = new JTextField(80);
    private final JTextField folderField = new JTextField(30);
    private boolean confirmed;

    public BuildArchiveDialog(Frame owner, PodSession session) {
        super(owner, "Make Archive", true);

        if (session.getSourceFolderPath() != null) {
            folderField.setText(session.getSourceFolderPath().toString());
        }
        if (session.getArchiveComment() != null) {
            commentField.setText(session.getArchiveComment());
        }

        JButton browseBtn = new JButton("Browse…");
        browseBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(folderField.getText().isBlank() ? null
                    : Path.of(folderField.getText()).toFile());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("Choose Output Folder");
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                folderField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });

        JButton okBtn = new JButton("Build");
        JButton cancelBtn = new JButton("Cancel");
        okBtn.addActionListener(e -> {
            if (nameField.getText().isBlank()) {
                JOptionPane.showMessageDialog(this, "Enter an output filename.", "JPod", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (folderField.getText().isBlank()) {
                JOptionPane.showMessageDialog(this, "Choose an output folder.", "JPod", JOptionPane.WARNING_MESSAGE);
                return;
            }
            confirmed = true;
            dispose();
        });
        cancelBtn.addActionListener(e -> dispose());

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints lc = new GridBagConstraints();
        lc.anchor = GridBagConstraints.WEST;
        lc.insets = new Insets(4, 6, 4, 4);
        GridBagConstraints fc2 = new GridBagConstraints();
        fc2.fill = GridBagConstraints.HORIZONTAL;
        fc2.weightx = 1.0;
        fc2.insets = new Insets(4, 0, 4, 4);

        int row = 0;
        lc.gridy = fc2.gridy = row++;
        lc.gridx = 0; fc2.gridx = 1;
        form.add(new JLabel("Output folder:"), lc);
        JPanel folderRow = new JPanel(new BorderLayout(4, 0));
        folderRow.add(folderField, BorderLayout.CENTER);
        folderRow.add(browseBtn, BorderLayout.EAST);
        form.add(folderRow, fc2);

        lc.gridy = fc2.gridy = row++;
        form.add(new JLabel("Filename:"), lc);
        form.add(nameField, fc2);

        lc.gridy = fc2.gridy = row++;
        form.add(new JLabel("POD Comment:"), lc);
        form.add(commentField, fc2);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btnPanel.add(okBtn);
        btnPanel.add(cancelBtn);

        setLayout(new BorderLayout(4, 4));
        add(form, BorderLayout.CENTER);
        add(btnPanel, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(owner);
    }

    public boolean wasConfirmed() { return confirmed; }

    public Path getTargetPath() {
        return Path.of(folderField.getText()).resolve(nameField.getText());
    }

    public String getComment() { return commentField.getText(); }
}
