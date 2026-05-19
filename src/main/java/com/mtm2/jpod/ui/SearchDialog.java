package com.mtm2.jpod.ui;

import com.mtm2.jpod.io.pod.PodArchive;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * Archive entry search dialog.
 *
 * <p>Finds entries by name substring or file size (case-insensitive by default).
 * Double-clicking a result or pressing <em>Jump To</em> calls the provided
 * {@code selectCallback} to highlight the entry in the main window's table.
 */
public final class SearchDialog extends JDialog {

    private final List<PodArchive.Entry> entries;
    private final IntConsumer selectCallback;

    private final JTextField searchBox = new JTextField(24);
    private final JCheckBox matchCaseCheck = new JCheckBox("Match case");
    private final JCheckBox searchNamesCheck = new JCheckBox("Names", true);
    private final JCheckBox searchSizesCheck = new JCheckBox("Sizes");
    private final DefaultTableModel resultModel;
    private final JTable resultTable;
    private final JLabel statusLabel = new JLabel(" ");
    private int searchFrom = 0;

    public SearchDialog(Frame owner, List<PodArchive.Entry> entries, IntConsumer selectCallback) {
        super(owner, "JPod — Search", false);
        this.entries = entries;
        this.selectCallback = selectCallback;

        resultModel = new DefaultTableModel(new String[]{"Name", "Size"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        resultTable = new JTable(resultModel);
        resultTable.getColumnModel().getColumn(0).setPreferredWidth(260);
        resultTable.getColumnModel().getColumn(1).setPreferredWidth(90);
        resultTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) jumpToSelected();
            }
        });

        JButton findNextBtn = new JButton("Find Next");
        JButton findPrevBtn = new JButton("Find Previous");
        JButton jumpBtn = new JButton("Jump To");
        JButton closeBtn = new JButton("Close");

        findNextBtn.addActionListener(e -> findNext(true));
        findPrevBtn.addActionListener(e -> findNext(false));
        jumpBtn.addActionListener(e -> jumpToSelected());
        closeBtn.addActionListener(e -> dispose());

        searchBox.addActionListener(e -> {
            searchFrom = 0;
            findNext(true);
        });

        JPanel topPanel = new JPanel(new BorderLayout(4, 4));
        topPanel.setBorder(BorderFactory.createEmptyBorder(6, 6, 4, 6));
        JPanel searchRow = new JPanel(new BorderLayout(4, 0));
        searchRow.add(new JLabel("Search for:"), BorderLayout.WEST);
        searchRow.add(searchBox, BorderLayout.CENTER);
        JPanel optRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        optRow.add(searchNamesCheck);
        optRow.add(searchSizesCheck);
        optRow.add(matchCaseCheck);
        topPanel.add(searchRow, BorderLayout.NORTH);
        topPanel.add(optRow, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new GridLayout(4, 1, 0, 4));
        btnPanel.setBorder(BorderFactory.createEmptyBorder(6, 4, 6, 6));
        btnPanel.add(findNextBtn);
        btnPanel.add(findPrevBtn);
        btnPanel.add(jumpBtn);
        btnPanel.add(closeBtn);

        JPanel centre = new JPanel(new BorderLayout(4, 4));
        centre.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
        centre.add(new JScrollPane(resultTable), BorderLayout.CENTER);
        centre.add(statusLabel, BorderLayout.SOUTH);

        setLayout(new BorderLayout(4, 4));
        add(topPanel, BorderLayout.NORTH);
        add(centre, BorderLayout.CENTER);
        add(btnPanel, BorderLayout.EAST);
        setSize(520, 460);
        setLocationRelativeTo(owner);
    }

    private void findNext(boolean forward) {
        resultModel.setRowCount(0);
        String query = searchBox.getText();
        if (query.isBlank()) { statusLabel.setText("Enter a search term."); return; }

        String q = matchCaseCheck.isSelected() ? query : query.toLowerCase(Locale.ROOT);

        for (int i = 0; i < entries.size(); i++) {
            PodArchive.Entry e = entries.get(i);
            boolean nameMatch = searchNamesCheck.isSelected() && matches(e.name(), q);
            boolean sizeMatch = searchSizesCheck.isSelected() && matches(String.valueOf(e.length()), q);
            if (nameMatch || sizeMatch) {
                resultModel.addRow(new Object[]{e.name(), e.length()});
            }
        }

        statusLabel.setText(resultModel.getRowCount() == 0
                ? "No matches." : resultModel.getRowCount() + " match(es).");
    }

    private boolean matches(String value, String query) {
        String v = matchCaseCheck.isSelected() ? value : value.toLowerCase(Locale.ROOT);
        return v.contains(query);
    }

    private void jumpToSelected() {
        int row = resultTable.getSelectedRow();
        if (row < 0) return;
        String name = (String) resultModel.getValueAt(row, 0);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).name().equals(name)) {
                selectCallback.accept(i);
                break;
            }
        }
    }
}
