package com.mtm2.jpod.ui;

import com.mtm2.jpod.io.pod.PodArchive;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
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
    private final List<Integer> matchIndices = new ArrayList<>();

    /**
     * The query and options the current result list was built from. Find Next
     * re-runs the search when this no longer matches the dialog, and otherwise
     * steps through the results it already has.
     */
    private String resultsKey;

    public SearchDialog(Frame owner, List<PodArchive.Entry> entries, IntConsumer selectCallback) {
        super(owner, "Search", true);
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

        findNextBtn.addActionListener(e -> step(true));
        findPrevBtn.addActionListener(e -> step(false));
        jumpBtn.addActionListener(e -> jumpToSelected());
        closeBtn.addActionListener(e -> dispose());

        searchBox.addActionListener(e -> step(true));

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

    /** Identifies the search the current results belong to. */
    private String currentKey() {
        return searchBox.getText() + " " + matchCaseCheck.isSelected()
                + " " + searchNamesCheck.isSelected()
                + " " + searchSizesCheck.isSelected();
    }

    /**
     * Runs the search if the query or options changed, then moves one match in the
     * requested direction, wrapping at either end, and jumps the main window's
     * table to it.
     */
    private void step(boolean forward) {
        if (searchBox.getText().isBlank()) {
            resultModel.setRowCount(0);
            matchIndices.clear();
            resultsKey = null;
            statusLabel.setText("Enter a search term.");
            return;
        }

        boolean rebuilt = false;
        if (!currentKey().equals(resultsKey)) {
            find();
            rebuilt = true;
        }
        if (matchIndices.isEmpty()) return;

        int current = resultTable.getSelectedRow();
        int next;
        if (rebuilt || current < 0) {
            // A fresh search starts at the first match going forward, and at the
            // last one going backward.
            next = forward ? 0 : matchIndices.size() - 1;
        } else {
            next = forward
                    ? (current + 1) % matchIndices.size()
                    : (current - 1 + matchIndices.size()) % matchIndices.size();
        }

        resultTable.setRowSelectionInterval(next, next);
        resultTable.scrollRectToVisible(resultTable.getCellRect(next, 0, true));
        statusLabel.setText("Match " + (next + 1) + " of " + matchIndices.size() + ".");
        selectCallback.accept(matchIndices.get(next));
    }

    private void find() {
        resultModel.setRowCount(0);
        matchIndices.clear();

        String query = searchBox.getText();
        String q = matchCaseCheck.isSelected() ? query : query.toLowerCase(Locale.ROOT);

        for (int i = 0; i < entries.size(); i++) {
            PodArchive.Entry e = entries.get(i);
            boolean nameMatch = searchNamesCheck.isSelected() && matches(e.name(), q);
            boolean sizeMatch = searchSizesCheck.isSelected() && matches(String.valueOf(e.length()), q);
            if (nameMatch || sizeMatch) {
                resultModel.addRow(new Object[]{e.name(), e.length()});
                matchIndices.add(i);
            }
        }

        resultsKey = currentKey();
        statusLabel.setText(resultModel.getRowCount() == 0
                ? "No matches." : resultModel.getRowCount() + " match(es).");
    }

    private boolean matches(String value, String query) {
        String v = matchCaseCheck.isSelected() ? value : value.toLowerCase(Locale.ROOT);
        return v.contains(query);
    }

    private void jumpToSelected() {
        int row = resultTable.getSelectedRow();
        if (row < 0 || row >= matchIndices.size()) return;
        selectCallback.accept(matchIndices.get(row));
    }
}
