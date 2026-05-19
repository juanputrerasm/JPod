package com.mtm2.jpod.ui;

import com.mtm2.jpod.PodSession;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.nio.file.Path;

/**
 * Extract options dialog.
 *
 * <p>Lets the user pick a destination folder and toggle whether to preserve
 * the archive's subfolder structure or merge all entries into a single output file.
 * Confirmed choices are written back into the provided {@link com.mtm2.jpod.PodSession}.
 */
public final class ExtractOptionsDialog extends JDialog {

    private final JTextField folderField = new JTextField(30);
    private final JCheckBox preserveFoldersCheck = new JCheckBox("Preserve folder structure", true);
    private final JCheckBox singleFileCheck = new JCheckBox("Merge into single output file");
    private boolean confirmed;
    private final PodSession session;

    public ExtractOptionsDialog(Frame owner, PodSession session) {
        super(owner, "JPod — Extract", true);
        this.session = session;

        if (session.getTargetFolderPath() != null) {
            folderField.setText(session.getTargetFolderPath().toString());
        }

        JButton browseBtn = new JButton("Browse…");
        browseBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(singleFileCheck.isSelected()
                    ? JFileChooser.FILES_ONLY : JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("Choose Extract Destination");
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                folderField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });

        singleFileCheck.addActionListener(e -> {
            preserveFoldersCheck.setEnabled(!singleFileCheck.isSelected());
        });

        JButton okBtn = new JButton("Extract");
        JButton cancelBtn = new JButton("Cancel");
        okBtn.addActionListener(e -> {
            if (folderField.getText().isBlank()) {
                JOptionPane.showMessageDialog(this, "Choose a destination.", "JPod", JOptionPane.WARNING_MESSAGE);
                return;
            }
            session.setTargetFolderPath(Path.of(folderField.getText()));
            session.setExtractToSingleOutputFile(singleFileCheck.isSelected());
            confirmed = true;
            dispose();
        });
        cancelBtn.addActionListener(e -> dispose());

        JPanel form = new JPanel(new BorderLayout(4, 6));
        form.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));

        JPanel folderRow = new JPanel(new BorderLayout(4, 0));
        folderRow.add(new JLabel("Destination:"), BorderLayout.WEST);
        folderRow.add(folderField, BorderLayout.CENTER);
        folderRow.add(browseBtn, BorderLayout.EAST);

        JPanel checkPanel = new JPanel(new GridLayout(2, 1, 0, 4));
        checkPanel.add(preserveFoldersCheck);
        checkPanel.add(singleFileCheck);

        form.add(folderRow, BorderLayout.NORTH);
        form.add(checkPanel, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btnPanel.add(okBtn);
        btnPanel.add(cancelBtn);

        setLayout(new BorderLayout(4, 4));
        add(form, BorderLayout.CENTER);
        add(btnPanel, BorderLayout.SOUTH);
        pack();
        setMinimumSize(new Dimension(420, 160));
        setLocationRelativeTo(owner);
    }

    public boolean wasConfirmed() { return confirmed; }
}
