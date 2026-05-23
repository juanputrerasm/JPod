package com.mtm2.jpod.ui;

import com.mtm2.jpod.AppConfig;
import com.mtm2.jpod.PodSession;
import com.mtm2.jpod.io.RawImageDecoder;
import com.mtm2.jpod.io.PodIniMounter;
import com.mtm2.jpod.io.PodManifestParser;
import com.mtm2.jpod.io.PodReportExporter;
import com.mtm2.jpod.io.pod.PodArchive;
import com.mtm2.jpod.io.pod.PodArchiveReader;
import com.mtm2.jpod.io.pod.PodArchiveWriter;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Main application window for JPod.
 *
 * <p>Maintains a mutable {@code editableEntries} list as the single source of
 * truth for the currently loaded archive state. Changes (add / remove / replace
 * entries) are held in memory until the user saves via {@code Save As…}.
 *
 * <p>Accepts drag-and-dropped files directly onto the entry table.
 */
public final class MainWindow extends JFrame {

    private static final String TITLE = "JPod";
    private static final Color COLOR_IDLE = new Color(0x00, 0xCC, 0x00);
    private static final Color COLOR_BUSY = new Color(0xFF, 0x00, 0x00);

    // POD binary layout constants
    private static final int POD_ITEM_COUNT_BYTES = 4;
    private static final int POD_COMMENT_BYTES    = 80;
    private static final int POD_ENTRY_BYTES      = 40;

    /** Holds every entry currently displayed and available for save/preview/extract. */
    private final List<EditableEntry> editableEntries = new ArrayList<>();
    private final List<BrowserRow> displayedRows = new ArrayList<>();
    private final Set<String> collapsedFolderPaths = new HashSet<>();
    private final Set<String> knownFolderPaths = new HashSet<>();

    /** 80-char archive comment written into the POD header. */
    private String archiveComment = "";

    /** True if editableEntries have been modified since last open/save. */
    private boolean dirty = false;

    /** The last POD archive that was opened from disk; null for new/manifest archives. */
    private PodArchive openedArchive = null;

    private final PodSession session = new PodSession();
    private final PodArchiveReader reader = new PodArchiveReader();
    private AppConfig config = AppConfig.load();
    private JMenu recentFilesMenu;

    // --- UI components ---
    private final DefaultTableModel tableModel;
    private final JTable entryTable;
    private final JLabel progressLabel      = new JLabel(" ");
    private final JLabel activityIndicator  = new JLabel("  ");
    private final JLabel archiveSizeLabel   = new JLabel("0");
    private final JLabel archiveCountLabel  = new JLabel("0");
    private final JLabel selectedCountLabel = new JLabel("0");
    private final JLabel dirtyLabel         = new JLabel(" ");
    /** Editable comment field shown below the toolbar; written into the POD header on Save As. */
    private final JTextField commentField   = new JTextField();
    private final JTextField quickSearchField = new JTextField();
    private final String[] baseColumnNames = {"Name", "Size", "Description"};
    private int sortColumn = -1;
    private SortDirection sortDirection = SortDirection.NONE;
    private final Icon folderIcon = systemFolderIcon();
    private final Icon fileIcon = systemFileIcon();

    // -------------------------------------------------------------------------
    // Inner record: one mutable entry
    // -------------------------------------------------------------------------

    private record EditableEntry(String name, byte[] data) {}

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public MainWindow() {
        super(TITLE);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(760, 560);
        setLocationRelativeTo(null);

        tableModel = new DefaultTableModel(new String[]{"Name", "Size", "Description"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        entryTable = new JTable(tableModel);
        entryTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        entryTable.getColumnModel().getColumn(0).setPreferredWidth(300);
        entryTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        entryTable.getColumnModel().getColumn(2).setPreferredWidth(280);
        entryTable.getColumnModel().getColumn(0).setCellRenderer(new BrowserNameCellRenderer());
        entryTable.setRowHeight(Math.max(entryTable.getRowHeight(), 22));
        entryTable.getSelectionModel().addListSelectionListener(e -> updateSelectedCount());
        entryTable.getTableHeader().addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                int column = entryTable.columnAtPoint(e.getPoint());
                if (column >= 0) {
                    cycleSort(column);
                }
            }
        });
        entryTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) showContextMenu(e);
            }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) showContextMenu(e);
            }
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && !e.isPopupTrigger()) handlePrimaryActivation(entryTable.rowAtPoint(e.getPoint()));
            }
        });

        activityIndicator.setOpaque(true);
        activityIndicator.setBackground(COLOR_IDLE);
        activityIndicator.setPreferredSize(new Dimension(16, 16));
        activityIndicator.setToolTipText("Activity indicator");

        dirtyLabel.setForeground(Color.RED);
        dirtyLabel.setFont(dirtyLabel.getFont().deriveFont(Font.BOLD));

        JScrollPane tableScroll = new JScrollPane(entryTable);
        installDropTarget(tableScroll);

        // Comment field – thin strip between toolbar and table
        commentField.setFont(commentField.getFont().deriveFont(Font.PLAIN, 11f));
        commentField.setToolTipText("POD archive comment (up to 80 characters, written on Save As)");
        commentField.setColumns(80);
        commentField.addActionListener(e -> {
            archiveComment = commentField.getText();
            markDirty();
        });
        commentField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) {
                archiveComment = commentField.getText();
            }
        });

        JPanel commentBar = new JPanel(new BorderLayout(4, 0));
        commentBar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        commentBar.add(new JLabel("Comment:"), BorderLayout.WEST);
        commentBar.add(commentField, BorderLayout.CENTER);

        quickSearchField.getDocument().addDocumentListener(new SimpleDocumentListener(this::refreshTable));

        JPanel browserBar = new JPanel(new BorderLayout(8, 0));
        browserBar.setBorder(BorderFactory.createEmptyBorder(0, 4, 2, 4));
        browserBar.add(new JLabel("Quick Search:"), BorderLayout.WEST);
        browserBar.add(quickSearchField, BorderLayout.CENTER);
        JButton advancedSearchButton = new JButton("Advanced");
        advancedSearchButton.addActionListener(event -> onSearch());
        browserBar.add(advancedSearchButton, BorderLayout.EAST);

        JPanel topPanel = new JPanel(new BorderLayout(0, 2));
        topPanel.add(commentBar, BorderLayout.NORTH);
        topPanel.add(browserBar, BorderLayout.CENTER);

        JPanel centre = new JPanel(new BorderLayout(0, 2));
        centre.add(topPanel, BorderLayout.NORTH);
        centre.add(tableScroll, BorderLayout.CENTER);

        setLayout(new BorderLayout(4, 4));
        add(buildToolBar(), BorderLayout.NORTH);
        add(centre, BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        updateColumnHeaders();
        buildMenuBar();
        refreshRecentFilesMenu();
    }

    // -------------------------------------------------------------------------
    // Toolbar / Menu
    // -------------------------------------------------------------------------

    private JToolBar buildToolBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);

        bar.add(toolButton("Open…",           this::onOpen));
        bar.add(toolButton("Save As…",        this::onSaveAs));
        bar.add(toolButton("Expand +",        this::expandAllFolders));
        bar.add(toolButton("Collapse -",      this::collapseAllFolders));
        bar.addSeparator();
        bar.add(toolButton("Add Files…",      this::onAddFiles));
        bar.add(toolButton("Extract Sel.",    this::onExtractSelected));
        bar.add(toolButton("Extract All",     this::onExtractAll));
        bar.addSeparator();
        bar.add(toolButton("Remove",          this::onRemoveSelected));
        bar.addSeparator();
        bar.add(toolButton("Search",          this::onSearch));
        bar.add(toolButton("About",           this::onAbout));
        return bar;
    }

    private void buildMenuBar() {
        JMenuBar mb = new JMenuBar();

        JMenu fileMenu = new JMenu("File");
        fileMenu.add(menuItem("Open POD…",        this::onOpen));
        fileMenu.add(menuItem("New Archive",       this::onNew));
        fileMenu.add(menuItem("Open Response List File…",   this::onOpenManifest));
        recentFilesMenu = new JMenu("Open Recent");
        fileMenu.add(recentFilesMenu);
        fileMenu.addSeparator();
        fileMenu.add(menuItem("Add Files…",        this::onAddFiles));
        fileMenu.add(menuItem("Remove Selected",   this::onRemoveSelected));
        fileMenu.add(menuItem("Save As…",          this::onSaveAs));
        fileMenu.addSeparator();
        fileMenu.add(menuItem("Extract All…",      this::onExtractAll));
        fileMenu.add(menuItem("Extract Selected…", this::onExtractSelected));
        fileMenu.addSeparator();
        fileMenu.add(menuItem("Save .inf Report…", this::onExportInfo));
        fileMenu.add(menuItem("Save .lst List…",   this::onExportList));
        fileMenu.addSeparator();
        fileMenu.add(menuItem("Exit", () -> System.exit(0)));

        JMenu toolMenu = new JMenu("Tools");
        toolMenu.add(menuItem("Mount in pod.ini…", this::onMount));
        toolMenu.add(menuItem("Search…",           this::onSearch));

        JMenu helpMenu = new JMenu("Help");
        helpMenu.add(menuItem("About JPod…", this::onAbout));

        mb.add(fileMenu);
        mb.add(toolMenu);
        mb.add(helpMenu);
        setJMenuBar(mb);
    }

    private static JButton toolButton(String label, Runnable action) {
        JButton btn = new JButton(label);
        btn.addActionListener(e -> action.run());
        return btn;
    }

    private static JMenuItem menuItem(String label, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(e -> action.run());
        return item;
    }

    // -------------------------------------------------------------------------
    // Status bar
    // -------------------------------------------------------------------------

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout(4, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.add(new JLabel("Size:"));
        left.add(archiveSizeLabel);
        left.add(new JLabel("Files:"));
        left.add(archiveCountLabel);
        left.add(new JLabel("Selected:"));
        left.add(selectedCountLabel);
        left.add(dirtyLabel);

        bar.add(progressLabel, BorderLayout.CENTER);
        bar.add(left, BorderLayout.WEST);
        bar.add(activityIndicator, BorderLayout.EAST);
        return bar;
    }

    // -------------------------------------------------------------------------
    // Context menu
    // -------------------------------------------------------------------------

    private void showContextMenu(java.awt.event.MouseEvent e) {
        int row = entryTable.rowAtPoint(e.getPoint());
        if (row >= 0 && !entryTable.isRowSelected(row)) {
            entryTable.setRowSelectionInterval(row, row);
        }
        BrowserRow browserRow = row >= 0 && row < displayedRows.size() ? displayedRows.get(row) : null;
        JPopupMenu menu = new JPopupMenu();
        if (browserRow != null && browserRow.folder()) {
            menu.add(menuItem(browserRow.collapsed() ? "Expand" : "Collapse", () -> toggleFolderRow(row)));
            menu.addSeparator();
            menu.add(menuItem("Remove", this::onRemoveSelected));
            menu.add(menuItem("Extract Selected", this::onExtractSelected));
        } else {
            menu.add(menuItem("Preview",            this::onPreview));
            menu.addSeparator();
            menu.add(menuItem("Replace with File…", this::onReplaceEntry));
            menu.add(menuItem("Remove",             this::onRemoveSelected));
            menu.addSeparator();
            menu.add(menuItem("Extract Selected",   this::onExtractSelected));
        }
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    // -------------------------------------------------------------------------
    // Actions — open / new / manifest
    // -------------------------------------------------------------------------

    private void onOpen() {
        if (!confirmDiscardChanges()) return;
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Open POD Archive");
        fc.setFileFilter(new FileNameExtensionFilter("POD Archives (*.pod)", "pod", "POD"));
        fc.setAcceptAllFileFilterUsed(true);
        applyRecentFolder(fc);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        openPodPath(fc.getSelectedFile().toPath());
    }

    /** Clears the editor for a brand-new empty archive. */
    private void onNew() {
        if (!confirmDiscardChanges()) return;
        editableEntries.clear();
        resetFolderBrowserState();
        archiveComment = "";
        commentField.setText("");
        openedArchive = null;
        dirty = false;
        session.reset();
        refreshTable();
        setTitle(TITLE + " — New Archive");
        progressLabel.setText("Add files with 'Add Files…' or drag and drop, then 'Save As…'.");
    }

    /**
     * Opens a .lst manifest file and resolves each listed filename from the
     * manifest's directory (with parent fallback), then populates the editor.
     */
    private void onOpenManifest() {
        if (!confirmDiscardChanges()) return;
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Open Response List File (.lst)");
        fc.setFileFilter(new FileNameExtensionFilter("Response list files (*.lst)", "lst"));
        fc.setAcceptAllFileFilterUsed(true);
        applyRecentFolder(fc);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        openManifestPath(fc.getSelectedFile().toPath());
    }

    // -------------------------------------------------------------------------
    // Actions — editing
    // -------------------------------------------------------------------------

    /**
     * Opens a multi-select file chooser and appends each picked file as a new
     * archive entry. The archive name defaults to the bare filename but the user
     * can rename it in the table (future work).
     */
    private void onAddFiles() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Add Files to Archive");
        fc.setMultiSelectionEnabled(true);
        if (session.getSourceFolderPath() != null) {
            fc.setCurrentDirectory(session.getSourceFolderPath().toFile());
        }
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        List<File> files = List.of(fc.getSelectedFiles());
        addFilesToEntries(files);
    }

    /** Removes every currently selected table row from the editable list. */
    private void onRemoveSelected() {
        int[] rows = entryTable.getSelectedRows();
        if (rows.length == 0) {
            JOptionPane.showMessageDialog(this, "Select entries to remove.", TITLE,
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        int[] sourceRows = selectedSourceRows();
        // Remove in reverse order so indices stay stable
        for (int i = sourceRows.length - 1; i >= 0; i--) {
            editableEntries.remove(sourceRows[i]);
        }
        markDirty();
        refreshTable();
        progressLabel.setText(rows.length + " entr" + (rows.length == 1 ? "y" : "ies") + " removed.");
    }

    /**
     * Replaces the single selected entry's bytes with a file chosen from disk,
     * preserving the original archive name.
     */
    private void onReplaceEntry() {
        int selectedRow = entryTable.getSelectedRow();
        if (selectedRow < 0 || entryTable.getSelectedRowCount() != 1) {
            JOptionPane.showMessageDialog(this, "Select exactly one entry to replace.", TITLE,
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (selectedRow >= displayedRows.size() || displayedRows.get(selectedRow).folder()) {
            JOptionPane.showMessageDialog(this, "Select exactly one file to replace.", TITLE,
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        int row = toSourceIndex(selectedRow);
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Replace '" + editableEntries.get(row).name() + "' with…");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        try {
            byte[] newData = Files.readAllBytes(fc.getSelectedFile().toPath());
            EditableEntry old = editableEntries.get(row);
            editableEntries.set(row, new EditableEntry(old.name(), newData));
            markDirty();
            refreshTable();
            selectTableRow(row);
            progressLabel.setText("Entry '" + old.name() + "' replaced (" + newData.length + " bytes).");
        } catch (IOException ex) {
            showError("Replace failed", ex);
        }
    }

    /**
     * Builds a POD file from the current editableEntries and prompts for the
     * output path and optional archive comment.
     */
    private void onSaveAs() {
        if (editableEntries.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Nothing to save — add files first.", TITLE,
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        Path target = chooseSaveFile("Save Archive As", "pod");
        if (target == null) return;

        // Use whatever is currently in the comment field
        archiveComment = commentField.getText();

        String savedComment = archiveComment;
        List<EditableEntry> snapshot = List.copyOf(editableEntries);
        setBusy(true);
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                List<PodArchiveWriter.Blob> blobs = new ArrayList<>(snapshot.size());
                for (EditableEntry e : snapshot) {
                    blobs.add(new PodArchiveWriter.Blob(e.name(), e.data()));
                }
                new PodArchiveWriter().write(target, savedComment, blobs);
                return null;
            }
            @Override protected void done() {
                setBusy(false);
                try {
                    get();
                    dirty = false;
                    updateDirtyLabel();
                    session.setTargetFolderPath(target.getParent());
                    session.setTargetFileName(target.getFileName().toString());
                    progressLabel.setText("Saved: " + target.getFileName());
                    setTitle(TITLE + " — " + target.getFileName());
                } catch (Exception ex) {
                    showError("Save failed", ex);
                }
            }
        }.execute();
    }

    // -------------------------------------------------------------------------
    // Actions — extract
    // -------------------------------------------------------------------------

    private void onExtractAll() {
        if (editableEntries.isEmpty()) { showNoArchive(); return; }
        Path dest = chooseFolder("Extract All — Choose Destination");
        if (dest == null) return;
        extractEntries(indexRange(editableEntries.size()), dest, false);
    }

    private void onExtractSelected() {
        int[] rows = entryTable.getSelectedRows();
        if (rows.length == 0) {
            JOptionPane.showMessageDialog(this, "Select at least one entry.", TITLE,
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        ExtractOptionsDialog dlg = new ExtractOptionsDialog(this, session);
        dlg.setVisible(true);
        if (!dlg.wasConfirmed()) return;

        Path dest = session.getTargetFolderPath();
        extractEntries(selectedSourceRows(), dest, session.isExtractToSingleOutputFile());
    }

    /** Writes the given entry indices to {@code destRoot}. */
    private void extractEntries(int[] indices, Path destRoot, boolean singleFile) {
        List<EditableEntry> toExtract = new ArrayList<>();
        for (int i : indices) toExtract.add(editableEntries.get(i));
        int total = toExtract.size();

        setBusy(true);
        new SwingWorker<Void, Integer>() {
            @Override protected Void doInBackground() throws Exception {
                for (int i = 0; i < toExtract.size(); i++) {
                    publish(i + 1);
                    EditableEntry e = toExtract.get(i);
                    Path dest = singleFile ? destRoot
                            : resolveEntryDest(destRoot, e.name());
                    Files.createDirectories(dest.getParent());
                    Files.write(dest, e.data());
                }
                return null;
            }
            @Override protected void process(List<Integer> chunks) {
                progressLabel.setText("Extracting " + chunks.get(chunks.size() - 1) + " of " + total + "…");
            }
            @Override protected void done() {
                setBusy(false);
                progressLabel.setText("Extraction complete.");
                try { get(); } catch (Exception ex) { showError("Extraction failed", ex); }
            }
        }.execute();
    }

    /** Converts a POD entry name (backslash separators) to an OS path under {@code root}. */
    private static Path resolveEntryDest(Path root, String entryName) {
        String clean = entryName.replace('\0', ' ').strip();
        String[] parts = clean.split("[/\\\\]");
        Path p = root;
        for (String part : parts) p = p.resolve(part);
        return p;
    }

    private static int[] indexRange(int size) {
        int[] r = new int[size];
        for (int i = 0; i < size; i++) r[i] = i;
        return r;
    }

    // -------------------------------------------------------------------------
    // Actions — reports / export
    // -------------------------------------------------------------------------

    private void onExportInfo() {
        if (openedArchive == null) {
            JOptionPane.showMessageDialog(this, "Open a POD file first to export an .inf report.", TITLE,
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        Path out = chooseSaveFile("Save .inf Report", "inf");
        if (out == null) return;
        try {
            new PodReportExporter(session).writeInfoReport(out);
            progressLabel.setText("Report saved: " + out.getFileName());
        } catch (IOException ex) { showError("Export failed", ex); }
    }

    private void onExportList() {
        if (editableEntries.isEmpty()) { showNoArchive(); return; }
        Path out = chooseSaveFile("Save .lst List", "lst");
        if (out == null) return;
        try (java.io.BufferedWriter w = Files.newBufferedWriter(out)) {
            for (EditableEntry e : editableEntries) {
                w.write(e.name());
                w.newLine();
            }
            progressLabel.setText("List saved: " + out.getFileName());
        } catch (IOException ex) { showError("Export failed", ex); }
    }

    // -------------------------------------------------------------------------
    // Actions — tools
    // -------------------------------------------------------------------------

    private void onMount() {
        if (!session.isArchiveOpen()) { showNoArchive(); return; }
        Path searchRoot = session.getSourceFolderPath();
        String podName = session.getSourceFileName();
        try {
            PodIniMounter.MountResult result = new PodIniMounter().mount(podName, searchRoot);
            String message = result.recommendedLimitExceeded()
                    ? "POD mounted successfully.\nWarning: pod.ini now exceeds the recommended 99-entry size."
                    : "POD mounted successfully.";
            JOptionPane.showMessageDialog(
                    this,
                    message,
                    TITLE,
                    result.recommendedLimitExceeded() ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
        } catch (PodIniMounter.AlreadyMountedException ex) {
            JOptionPane.showMessageDialog(this, "POD already mounted.", TITLE,
                    JOptionPane.WARNING_MESSAGE);
        } catch (PodIniMounter.PodIniNotFoundException ex) {
            JOptionPane.showMessageDialog(this, "File POD.INI cannot be located.", TITLE,
                    JOptionPane.WARNING_MESSAGE);
        } catch (IOException ex) {
            showError("Mount failed", ex);
        }
    }

    private void onSearch() {
        if (editableEntries.isEmpty()) { showNoArchive(); return; }
        // Build synthetic PodArchive.Entry list from editableEntries for SearchDialog
        List<PodArchive.Entry> synth = new ArrayList<>(editableEntries.size());
        for (EditableEntry e : editableEntries) {
            synth.add(new PodArchive.Entry(e.name(), e.data().length, 0));
        }
        new SearchDialog(this, synth, this::selectTableRow).setVisible(true);
    }

    private void onPreview() {
        int row = entryTable.getSelectedRow();
        if (row < 0) return;
        if (row >= displayedRows.size() || displayedRows.get(row).folder()) {
            return;
        }
        int sourceIndex = toSourceIndex(row);
        if (sourceIndex < 0 || sourceIndex >= editableEntries.size()) return;
        EditableEntry e = editableEntries.get(sourceIndex);
        PreviewWindow window = new PreviewWindow(this, e.name(), e.data(), openedArchive);
        if (!window.isPreviewCancelled()) {
            window.setVisible(true);
        }
    }

    private void onAbout() {
        new AboutDialog(this).setVisible(true);
    }

    // -------------------------------------------------------------------------
    // Drag-and-drop
    // -------------------------------------------------------------------------

    /**
     * Installs a drop target on {@code component} that accepts lists of dropped
     * files and appends them as new archive entries. (Feature 6)
     */
    private void installDropTarget(JComponent component) {
        new DropTarget(component, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
            @Override
            @SuppressWarnings("unchecked")
            public void drop(DropTargetDropEvent ev) {
                try {
                    ev.acceptDrop(DnDConstants.ACTION_COPY);
                    List<File> files = (List<File>)
                            ev.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    addFilesToEntries(files);
                    ev.dropComplete(true);
                } catch (Exception ex) {
                    ev.rejectDrop();
                }
            }

            @Override public void dragOver(DropTargetDragEvent ev) {
                if (ev.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    ev.acceptDrag(DnDConstants.ACTION_COPY);
                } else {
                    ev.rejectDrag();
                }
            }
        }, true);
    }

    /** Reads each file and appends it to {@code editableEntries}. */
    private void addFilesToEntries(List<File> files) {
        int added = 0;
        for (File f : files) {
            if (!f.isFile()) continue;
            try {
                byte[] data = Files.readAllBytes(f.toPath());
                editableEntries.add(new EditableEntry(f.getName(), data));
                added++;
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this,
                        "Could not read: " + f.getName() + "\n" + ex.getMessage(),
                        TITLE, JOptionPane.WARNING_MESSAGE);
            }
        }
        if (added > 0) {
            markDirty();
            refreshTable();
            progressLabel.setText(added + " file" + (added == 1 ? "" : "s") + " added.");
        }
    }

    // -------------------------------------------------------------------------
    // Table refresh
    // -------------------------------------------------------------------------

    /**
     * Rebuilds the JTable from {@code editableEntries}, computing data offsets
     * on the fly using the standard POD layout:
     * header = 4 + 80 + N × 40 bytes, entries follow sequentially.
     */
    private void refreshTable() {
        int[] selectedSourceRows = selectedSourceRows();
        tableModel.setRowCount(0);
        displayedRows.clear();
        long offset = POD_ITEM_COUNT_BYTES + POD_COMMENT_BYTES
                + (long) editableEntries.size() * POD_ENTRY_BYTES;
        displayedRows.addAll(buildDisplayedRows());
        for (BrowserRow row : displayedRows) {
            tableModel.addRow(new Object[]{row.nameText(), row.sizeText(), row.description()});
        }
        for (EditableEntry e : editableEntries) {
            offset += e.data().length;
        }
        archiveCountLabel.setText(String.valueOf(editableEntries.size()));
        archiveSizeLabel.setText(String.format("%,d", offset));
        restoreSelection(selectedSourceRows);
        updateSelectedCount();
        updateDirtyLabel();
    }

    private String describeEntry(EditableEntry entry) {
        String name = entry.name();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        return switch (ext) {
            case "act" -> "Palette file";
            case "ani" -> "Animation file";
            case "bin" -> "3D model file";
            case "bmp" -> "Bitmap image";
            case "cl0", "cl1", "cl2", "cl3", "cl4", "cl5" -> "Ground-box collision layer";
            case "clr" -> "Terrain color layer";
            case "crs" -> "LVL auxiliary resource";
            case "def" -> "Object placement definition";
            case "dmo" -> "Demo manifest";
            case "gif" -> "GIF image";
            case "glt" -> "Ground/resource file";
            case "jpeg", "jpg" -> "JPEG image";
            case "json" -> "JSON data";
            case "klp" -> "Music metadata";
            case "lte" -> "Extended terrain layer";
            case "lvl" -> "Level manifest";
            case "lwo" -> "LightWave object file";
            case "map" -> "Fog map";
            case "mic" -> "TV/F3 Intro text";
            case "mix", "mod" -> "Music data";
            case "pit" -> "CPR Pits file";
            case "png" -> "PNG image";
            case "pod" -> "POD archive";
            case "ra0", "ra1", "ra2" , "ra3" , "ra4", "ra5" -> "Ground-box layer";
            case "raw" -> rawDescription(entry.data());
            case "sit" -> "Track file";
            case "smk" -> "Smacker video";
            case "tex" -> "Texture manifest";
            case "tnl" -> "Tunnel definition file";
            case "trk" -> isDataSubfolderEntry(name) ? "CPR track definition file" : "Truck definition file";
            case "trn" -> "Tournament definition file";
            case "ttx" -> "CPR Race-track texture table";
            case "txx" -> "Traxx track file";
            case "txp" -> "Texture pattern library";
            case "txt" -> "Text file";
            case "tty" -> "Texture metadata";
            case "tvi" -> "Video file";
            case "wav" -> "Wave audio file";
            case "webp" -> "WebP image";
            case "lst" -> "Response list file";
            case "inf" -> "Info report";
            case "ini", "cfg" -> "Configuration file";
            default -> ext.isBlank() ? "Binary data" : ext.toUpperCase(Locale.ROOT) + " file";
        };
    }

    private String rawDescription(byte[] data) {
        int[] dims = RawImageDecoder.detectDimensions(data.length);
        if (dims == null) {
            return "RAW image data (non-standard size)";
        }
        if ((dims[0] == 64 && dims[1] == 64) || (dims[0] == 256 && dims[1] == 256)) {
            return "RAW image data";
        }
        return "RAW image data (non-standard " + dims[0] + "x" + dims[1] + ")";
    }

    private void updateSelectedCount() {
        selectedCountLabel.setText(String.valueOf(selectedSourceRows().length));
    }

    private void markDirty() {
        dirty = true;
        updateDirtyLabel();
    }

    private void updateDirtyLabel() {
        dirtyLabel.setText(dirty ? "● unsaved" : " ");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void setBusy(boolean busy) {
        activityIndicator.setBackground(busy ? COLOR_BUSY : COLOR_IDLE);
    }

    private void showNoArchive() {
        JOptionPane.showMessageDialog(this, "No archive entries loaded.", TITLE,
                JOptionPane.WARNING_MESSAGE);
    }

    private void showError(String context, Exception ex) {
        JOptionPane.showMessageDialog(this, context + ":\n" + ex.getMessage(),
                TITLE, JOptionPane.ERROR_MESSAGE);
    }

    /** Returns false if the user chose not to discard unsaved changes. */
    private boolean confirmDiscardChanges() {
        if (!dirty) return true;
        int result = JOptionPane.showConfirmDialog(this,
                "You have unsaved changes. Discard them?",
                TITLE, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        return result == JOptionPane.YES_OPTION;
    }

    private void selectTableRow(int index) {
        expandFoldersForSourceIndex(index);
        refreshTable();
        for (int displayedRow = 0; displayedRow < displayedRows.size(); displayedRow++) {
            BrowserRow row = displayedRows.get(displayedRow);
            if (!row.folder() && row.sourceIndex() == index) {
                entryTable.setRowSelectionInterval(displayedRow, displayedRow);
                entryTable.scrollRectToVisible(entryTable.getCellRect(displayedRow, 0, true));
                return;
            }
        }
    }

    private Path chooseFolder(String title) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(title);
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        applyRecentFolder(fc);
        return fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION
                ? fc.getSelectedFile().toPath() : null;
    }

    private Path chooseSaveFile(String title, String ext) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(title);
        fc.setFileFilter(new FileNameExtensionFilter(ext.toUpperCase() + " files", ext));
        applyRecentFolder(fc);
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return null;
        Path p = fc.getSelectedFile().toPath();
        if (!p.getFileName().toString().toLowerCase().endsWith("." + ext)) {
            p = p.resolveSibling(p.getFileName() + "." + ext);
        }
        return p;
    }

    private void openPodPath(Path selected) {
        session.setSourceFolderPath(selected.getParent());
        session.setSourceFileName(selected.getFileName().toString());

        setBusy(true);
        new SwingWorker<PodArchive, Void>() {
            @Override protected PodArchive doInBackground() throws Exception {
                return reader.read(selected);
            }
            @Override protected void done() {
                setBusy(false);
                try {
                    PodArchive archive = get();
                    openedArchive = archive;
                    session.setOpenArchive(archive);
                    archiveComment = archive.getComment();
                    session.setArchiveComment(archiveComment);
                    commentField.setText(archiveComment);
                    editableEntries.clear();
                    resetFolderBrowserState();
                    for (PodArchive.Entry entry : archive.getEntries()) {
                        editableEntries.add(new EditableEntry(entry.name(),
                                archive.getEntryBytes(entry)));
                    }
                    dirty = false;
                    refreshTable();
                    rememberOpenedFile(selected);
                    setTitle(TITLE + " — " + selected.getFileName());
                } catch (Exception ex) {
                    showError("Failed to open archive", ex);
                }
            }
        }.execute();
    }

    private void openManifestPath(Path manifestPath) {
        Path sourceFolder = manifestPath.getParent();

        setBusy(true);
        new SwingWorker<List<PodArchiveWriter.Blob>, Void>() {
            @Override protected List<PodArchiveWriter.Blob> doInBackground() throws Exception {
                return new PodManifestParser().parse(manifestPath, sourceFolder);
            }
            @Override protected void done() {
                setBusy(false);
                try {
                    List<PodArchiveWriter.Blob> blobs = get();
                    editableEntries.clear();
                    resetFolderBrowserState();
                    for (PodArchiveWriter.Blob b : blobs) {
                        editableEntries.add(new EditableEntry(b.name(), b.data()));
                    }
                    archiveComment = "";
                    commentField.setText("");
                    openedArchive = null;
                    session.setSourceFolderPath(sourceFolder);
                    dirty = true;
                    refreshTable();
                    rememberOpenedFile(manifestPath);
                    setTitle(TITLE + " — " + manifestPath.getFileName() + " (manifest)");
                    progressLabel.setText(blobs.size() + " entries loaded from manifest.");
                } catch (Exception ex) {
                    showError("Failed to load manifest", ex);
                }
            }
        }.execute();
    }

    private void refreshRecentFilesMenu() {
        if (recentFilesMenu == null) {
            return;
        }
        recentFilesMenu.removeAll();
        List<Path> recents = config.recentOpenedFiles();
        if (recents.isEmpty()) {
            JMenuItem none = new JMenuItem("(none)");
            none.setEnabled(false);
            recentFilesMenu.add(none);
            return;
        }
        for (Path path : recents) {
            JMenuItem item = new JMenuItem(path.getFileName() != null ? path.getFileName().toString() : path.toString());
            item.setToolTipText(path.toString());
            item.addActionListener(e -> openRecentFile(path));
            recentFilesMenu.add(item);
        }
    }

    private void openRecentFile(Path path) {
        if (!Files.isRegularFile(path)) {
            JOptionPane.showMessageDialog(
                    this,
                    "File no longer exists:\n" + path,
                    TITLE,
                    JOptionPane.WARNING_MESSAGE);
            config = config.withoutRecentOpenedFile(path);
            saveConfigQuietly();
            refreshRecentFilesMenu();
            return;
        }
        if (!confirmDiscardChanges()) {
            return;
        }
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pod")) {
            openPodPath(path);
            return;
        }
        if (lower.endsWith(".lst")) {
            openManifestPath(path);
            return;
        }
        JOptionPane.showMessageDialog(
                this,
                "Recent file type is not supported anymore:\n" + path,
                TITLE,
                JOptionPane.WARNING_MESSAGE);
        config = config.withoutRecentOpenedFile(path);
        saveConfigQuietly();
        refreshRecentFilesMenu();
    }

    private void rememberOpenedFile(Path path) {
        config = config.withRecentOpenedFile(path);
        saveConfigQuietly();
        refreshRecentFilesMenu();
    }

    private void saveConfigQuietly() {
        try {
            config.save();
        } catch (IOException ex) {
            progressLabel.setText("Could not save recent files config.");
        }
    }

    private void applyRecentFolder(JFileChooser chooser) {
        Path base = null;
        if (session.getSourceFolderPath() != null && Files.isDirectory(session.getSourceFolderPath())) {
            base = session.getSourceFolderPath();
        } else if (!config.recentOpenedFiles().isEmpty()) {
            Path recent = config.recentOpenedFiles().get(0);
            Path parent = Files.isDirectory(recent) ? recent : recent.getParent();
            if (parent != null && Files.isDirectory(parent)) {
                base = parent;
            }
        }
        if (base != null) {
            chooser.setCurrentDirectory(base.toFile());
        }
    }

    private List<BrowserRow> buildDisplayedRows() {
        FolderNode root = buildFolderTree();
        List<BrowserRow> rows = new ArrayList<>();
        String filter = quickSearchField.getText() != null
                ? quickSearchField.getText().trim().toLowerCase(Locale.ROOT)
                : "";
        ensureKnownFoldersCollapsed(root);
        appendRows(root, 0, filter, rows);
        return rows;
    }

    private int[] selectedSourceRows() {
        java.util.LinkedHashSet<Integer> sourceSet = new java.util.LinkedHashSet<>();
        for (int selectedRow : entryTable.getSelectedRows()) {
            collectSelectedSourceIndices(selectedRow, sourceSet);
        }
        int[] source = sourceSet.stream().mapToInt(Integer::intValue).toArray();
        java.util.Arrays.sort(source);
        return source;
    }

    private int toSourceIndex(int viewRow) {
        if (viewRow < 0 || viewRow >= displayedRows.size()) {
            return -1;
        }
        BrowserRow row = displayedRows.get(viewRow);
        return row.folder() ? -1 : row.sourceIndex();
    }

    private void restoreSelection(int[] sourceRows) {
        entryTable.clearSelection();
        for (int sourceRow : sourceRows) {
            for (int displayedRow = 0; displayedRow < displayedRows.size(); displayedRow++) {
                BrowserRow row = displayedRows.get(displayedRow);
                if (!row.folder() && row.sourceIndex() == sourceRow) {
                    entryTable.addRowSelectionInterval(displayedRow, displayedRow);
                    break;
                }
            }
        }
    }

    private boolean isDataSubfolderEntry(String entryName) {
        String normalized = entryName.replace('\\', '/').toUpperCase(Locale.ROOT);
        String[] parts = normalized.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("DATA".equals(parts[i])) {
                return true;
            }
        }
        return false;
    }

    private Comparator<Integer> comparatorForColumn(int column) {
        return switch (column) {
            case 0 -> Comparator.comparing(i -> editableEntries.get(i).name().toLowerCase(Locale.ROOT));
            case 1 -> Comparator.comparingInt(i -> editableEntries.get(i).data().length);
            case 2 -> Comparator.comparing(i -> describeEntry(editableEntries.get(i)).toLowerCase(Locale.ROOT));
            default -> null;
        };
    }

    private void cycleSort(int column) {
        if (sortColumn != column) {
            sortColumn = column;
            sortDirection = SortDirection.ASC;
        } else if (sortDirection == SortDirection.ASC) {
            sortDirection = SortDirection.DESC;
        } else if (sortDirection == SortDirection.DESC) {
            sortColumn = -1;
            sortDirection = SortDirection.NONE;
        } else {
            sortDirection = SortDirection.ASC;
        }
        updateColumnHeaders();
        refreshTable();
    }

    private void updateColumnHeaders() {
        for (int i = 0; i < baseColumnNames.length; i++) {
            String label = baseColumnNames[i];
            if (i == sortColumn) {
                if (sortDirection == SortDirection.ASC) {
                    label += " ↑";
                } else if (sortDirection == SortDirection.DESC) {
                    label += " ↓";
                }
            }
            entryTable.getColumnModel().getColumn(i).setHeaderValue(label);
        }
        entryTable.getTableHeader().repaint();
    }

    private void handlePrimaryActivation(int row) {
        if (row < 0 || row >= displayedRows.size()) {
            return;
        }
        if (displayedRows.get(row).folder()) {
            toggleFolderRow(row);
        } else {
            onPreview();
        }
    }

    private void toggleFolderRow(int row) {
        if (row < 0 || row >= displayedRows.size()) {
            return;
        }
        BrowserRow browserRow = displayedRows.get(row);
        if (!browserRow.folder()) {
            return;
        }
        if (collapsedFolderPaths.contains(browserRow.folderPath())) {
            collapsedFolderPaths.remove(browserRow.folderPath());
        } else {
            collapsedFolderPaths.add(browserRow.folderPath());
        }
        refreshTable();
        if (row < entryTable.getRowCount()) {
            entryTable.setRowSelectionInterval(row, row);
        }
    }

    private void expandAllFolders() {
        collapsedFolderPaths.clear();
        refreshTable();
    }

    private void collapseAllFolders() {
        collapsedFolderPaths.clear();
        collapsedFolderPaths.addAll(knownFolderPaths);
        refreshTable();
    }

    private FolderNode buildFolderTree() {
        FolderNode root = new FolderNode("", "", -1);
        for (int i = 0; i < editableEntries.size(); i++) {
            EditableEntry entry = editableEntries.get(i);
            String[] parts = splitEntryName(entry.name());
            FolderNode cursor = root;
            for (int partIndex = 0; partIndex < parts.length - 1; partIndex++) {
                cursor = cursor.childFolder(parts[partIndex], i);
            }
            cursor.fileIndices.add(i);
        }
        return root;
    }

    private String[] splitEntryName(String entryName) {
        return entryName.replace('\0', ' ').strip().split("[/\\\\]+");
    }

    private void ensureKnownFoldersCollapsed(FolderNode node) {
        for (FolderNode child : node.folders.values()) {
            if (knownFolderPaths.add(child.path)) {
                collapsedFolderPaths.add(child.path);
            }
            ensureKnownFoldersCollapsed(child);
        }
    }

    private void appendRows(FolderNode node, int depth, String filter, List<BrowserRow> rows) {
        for (FolderItem item : orderedItems(node, filter)) {
            if (item.folder != null) {
                FolderNode folder = item.folder;
                boolean collapsed = collapsedFolderPaths.contains(folder.path);
                rows.add(BrowserRow.folder(folder.path, folder.name, depth, collapsed));
                if (!collapsed || !filter.isBlank()) {
                    appendRows(folder, depth + 1, filter, rows);
                }
            } else {
                int sourceIndex = item.fileIndex;
                EditableEntry entry = editableEntries.get(sourceIndex);
                rows.add(BrowserRow.file(
                        sourceIndex,
                        item.fileName,
                        depth,
                        entry.data().length,
                        describeEntry(entry)));
            }
        }
    }

    private List<FolderItem> orderedItems(FolderNode node, String filter) {
        List<FolderItem> items = new ArrayList<>();
        for (FolderNode folder : node.folders.values()) {
            if (matchesFolderOrDescendant(folder, filter)) {
                items.add(new FolderItem(folder, -1, null));
            }
        }
        for (int fileIndex : node.fileIndices) {
            EditableEntry entry = editableEntries.get(fileIndex);
            String fileName = baseName(entry.name());
            if (filter.isBlank() || entry.name().toLowerCase(Locale.ROOT).contains(filter)) {
                items.add(new FolderItem(null, fileIndex, fileName));
            }
        }
        Comparator<FolderItem> comparator = folderItemComparator();
        if (comparator != null) {
            items.sort(comparator);
        }
        return items;
    }

    private Comparator<FolderItem> folderItemComparator() {
        if (sortDirection == SortDirection.NONE) {
            return Comparator.comparingInt(FolderItem::orderKey);
        }
        Comparator<FolderItem> comparator = switch (sortColumn) {
            case 0 -> Comparator.comparing(item -> item.sortName().toLowerCase(Locale.ROOT));
            case 1 -> Comparator.comparingLong(FolderItem::sortSize);
            case 2 -> Comparator.comparing(item -> item.sortDescription().toLowerCase(Locale.ROOT));
            default -> null;
        };
        if (comparator == null) {
            return null;
        }
        Comparator<FolderItem> valueComparator = sortDirection == SortDirection.ASC ? comparator : comparator.reversed();
        return Comparator.comparing(FolderItem::folderLastFlag)
                .thenComparing(valueComparator);
    }

    private boolean matchesFolderOrDescendant(FolderNode folder, String filter) {
        if (filter.isBlank()) {
            return true;
        }
        if (folder.name.toLowerCase(Locale.ROOT).contains(filter)) {
            return true;
        }
        for (int fileIndex : folder.fileIndices) {
            if (editableEntries.get(fileIndex).name().toLowerCase(Locale.ROOT).contains(filter)) {
                return true;
            }
        }
        for (FolderNode child : folder.folders.values()) {
            if (matchesFolderOrDescendant(child, filter)) {
                return true;
            }
        }
        return false;
    }

    private String baseName(String entryName) {
        String clean = entryName.replace('\0', ' ').strip();
        int slash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        return slash >= 0 ? clean.substring(slash + 1) : clean;
    }

    private void expandFoldersForSourceIndex(int sourceIndex) {
        if (sourceIndex < 0 || sourceIndex >= editableEntries.size()) {
            return;
        }
        String normalized = normalizeArchivePath(editableEntries.get(sourceIndex).name());
        int slash = normalized.lastIndexOf('/');
        while (slash > 0) {
            collapsedFolderPaths.remove(normalized.substring(0, slash));
            slash = normalized.lastIndexOf('/', slash - 1);
        }
    }

    private void collectSelectedSourceIndices(int selectedRow, java.util.LinkedHashSet<Integer> sourceSet) {
        if (selectedRow < 0 || selectedRow >= displayedRows.size()) {
            return;
        }
        BrowserRow row = displayedRows.get(selectedRow);
        if (!row.folder()) {
            sourceSet.add(row.sourceIndex());
            return;
        }
        for (int i = 0; i < editableEntries.size(); i++) {
            String normalized = normalizeArchivePath(editableEntries.get(i).name());
            if (normalized.startsWith(row.folderPath() + "/")) {
                sourceSet.add(i);
            }
        }
    }

    private String normalizeArchivePath(String path) {
        return path.replace('\\', '/').replace('\0', ' ').strip();
    }

    private void resetFolderBrowserState() {
        displayedRows.clear();
        collapsedFolderPaths.clear();
        knownFolderPaths.clear();
    }

    private static Icon systemFolderIcon() {
        Icon icon = UIManager.getIcon("FileView.directoryIcon");
        return icon != null ? icon : UIManager.getIcon("Tree.closedIcon");
    }

    private static Icon systemFileIcon() {
        Icon icon = UIManager.getIcon("FileView.fileIcon");
        return icon != null ? icon : UIManager.getIcon("Tree.leafIcon");
    }

    private enum SortDirection {
        NONE,
        ASC,
        DESC
    }

    private final class BrowserNameCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean isSelected,
                boolean hasFocus,
                int row,
                int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (row < 0 || row >= displayedRows.size()) {
                return label;
            }
            BrowserRow browserRow = displayedRows.get(row);
            label.setBorder(BorderFactory.createEmptyBorder(0, browserRow.depth() * 18 + 4, 0, 0));
            label.setIcon(browserRow.folder() ? folderIcon : fileIcon);
            label.setText(browserRow.displayName());
            return label;
        }
    }

    private record BrowserRow(
            boolean folder,
            int sourceIndex,
            String folderPath,
            String displayName,
            int depth,
            long size,
            String description,
            boolean collapsed) {
        private static BrowserRow folder(String folderPath, String displayName, int depth, boolean collapsed) {
            return new BrowserRow(true, -1, folderPath, displayName, depth, 0L, "Folder", collapsed);
        }

        private static BrowserRow file(int sourceIndex, String displayName, int depth, long size, String description) {
            return new BrowserRow(false, sourceIndex, "", displayName, depth, size, description, false);
        }

        private String nameText() {
            return displayName;
        }

        private String sizeText() {
            return folder ? "" : String.format("%,d", size);
        }
    }

    private static final class FolderNode {
        private final String name;
        private final String path;
        private int firstSourceIndex;
        private final LinkedHashMap<String, FolderNode> folders = new LinkedHashMap<>();
        private final List<Integer> fileIndices = new ArrayList<>();

        private FolderNode(String name, String path, int firstSourceIndex) {
            this.name = name;
            this.path = path;
            this.firstSourceIndex = firstSourceIndex;
        }

        private FolderNode childFolder(String childName, int sourceIndex) {
            String childPath = path.isEmpty() ? childName : path + "/" + childName;
            FolderNode child = folders.get(childName);
            if (child == null) {
                child = new FolderNode(childName, childPath, sourceIndex);
                folders.put(childName, child);
            } else {
                child.firstSourceIndex = Math.min(child.firstSourceIndex, sourceIndex);
            }
            return child;
        }
    }

    private final class FolderItem {
        private final FolderNode folder;
        private final int fileIndex;
        private final String fileName;

        private FolderItem(FolderNode folder, int fileIndex, String fileName) {
            this.folder = folder;
            this.fileIndex = fileIndex;
            this.fileName = fileName;
        }

        private FolderItem folder(FolderNode folder) {
            return new FolderItem(folder, -1, null);
        }

        private FolderItem file(int fileIndex, String fileName) {
            return new FolderItem(null, fileIndex, fileName);
        }

        private int orderKey() {
            return folder != null ? folder.firstSourceIndex : fileIndex;
        }

        private int folderLastFlag() {
            return folder != null ? 0 : 1;
        }

        private String sortName() {
            return folder != null ? folder.name : fileName;
        }

        private long sortSize() {
            return folder != null ? 0L : editableEntries.get(fileIndex).data().length;
        }

        private String sortDescription() {
            return folder != null ? "Folder" : describeEntry(editableEntries.get(fileIndex));
        }
    }

    private interface DocumentChangeAction {
        void run();
    }

    private static final class SimpleDocumentListener implements javax.swing.event.DocumentListener {
        private final DocumentChangeAction action;

        private SimpleDocumentListener(DocumentChangeAction action) {
            this.action = action;
        }

        @Override
        public void insertUpdate(javax.swing.event.DocumentEvent e) {
            action.run();
        }

        @Override
        public void removeUpdate(javax.swing.event.DocumentEvent e) {
            action.run();
        }

        @Override
        public void changedUpdate(javax.swing.event.DocumentEvent e) {
            action.run();
        }
    }
}
