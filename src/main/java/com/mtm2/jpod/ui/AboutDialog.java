package com.mtm2.jpod.ui;

import javax.swing.*;
import java.awt.*;

/** About dialog — displays application name, description, and version info. */
public final class AboutDialog extends JDialog {

    public AboutDialog(Frame owner) {
        super(owner, "About JPod", true);

        JLabel title = new JLabel("JPod v1.2.1", SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));

        JLabel subtitle = new JLabel(
                "<html><div style='text-align:center'>"
                + "Terminal Reality POD Archive Viewer &amp; Extractor<br>"
                + "by Juan Pablo Utreras \"Kmaster\"<br>"
                + "Based on WinPod by MDMRE<br><br>"
                + "<tt>www.mtm2.com</tt>"
                + "</div></html>", SwingConstants.CENTER);

        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> dispose());

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(16, 24, 12, 24));
        content.add(title, BorderLayout.NORTH);
        content.add(subtitle, BorderLayout.CENTER);
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        btnPanel.add(closeBtn);
        content.add(btnPanel, BorderLayout.SOUTH);

        add(content);
        pack();
        setMinimumSize(new Dimension(360, 200));
        setLocationRelativeTo(owner);
    }
}
