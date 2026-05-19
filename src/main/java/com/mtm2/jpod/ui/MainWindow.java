package com.mtm2.jpod.ui;

import com.mtm2.jpod.PodSession;
import com.mtm2.jpod.io.PodIniMounter;
import com.mtm2.jpod.io.PodManifestParser;
import com.mtm2.jpod.io.PodReportExporter;
import com.mtm2.jpod.io.pod.PodArchive;
import com.mtm2.jpod.io.pod.PodArchiveReader;
import com.mtm2.jpod.io.pod.PodArchiveWriter;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    /** 80-char archive comment written into the POD header. */
    private String archiveComment = "";

    /** True if editableEntries have been modified since last open/save. */
    private boolean dirty = false;

    /** The last POD archive that was opened from disk; null for new/manifest archives. */
    private PodArchive openedArchive = null;

    private final PodSession session = new PodSession();
    private final PodArchiveReader reader = new PodArchiveReader();

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

        tableModel = new DefaultTableModel(new String[]{"Name", "Size", "Offset"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        entryTable = new JTable(tableModel);
        entryTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        entryTable.getColumnModel().getColumn(0).setPreferredWidth(380);
        entryTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        entryTable.getColumnModel().getColumn(2).setPreferredWidth(100);
        entryTable.getSelectionModel().addListSelectionListener(e -> updateSelectedCount());
        entryTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) showContextMenu(e);
            }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) showContextMenu(e);
            }
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && !e.isPopupTrigger()) onPreview();
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

        JPanel centre = new JPanel(new BorderLayout(0, 2));
        centre.add(commentBar, BorderLayout.NORTH);
        centre.add(tableScroll, BorderLayout.CENTER);

        setLayout(new BorderLayout(4, 4));
        add(buildToolBar(), BorderLayout.NORTH);
        add(centre, BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        buildMenuBar();
    }

    // -------------------------------------------------------------------------
    // Toolbar / Menu
    // -------------------------------------------------------------------------

    private JToolBar buildToolBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);

        bar.add(toolButton("Open…",           this::onOpen));
        bar.add(toolButton("New",             this::onNew));
        bar.add(toolButton("Open Manifest…",  this::onOpenManifest));
        bar.addSeparator();
        bar.add(toolButton("Add Files…",      this::onAddFiles));
        bar.add(toolButton("Remove",          this::onRemoveSelected));
        bar.add(toolButton("Save As…",        this::onSaveAs));
        bar.addSeparator();
        bar.add(toolButton("Extract All",     this::onExtractAll));
        bar.add(toolButton("Extract Sel.",    this::onExtractSelected));
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
        fileMenu.add(menuItem("Open Manifest…",   this::onOpenManifest));
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
        JPopupMenu menu = new JPopupMenu();
        menu.add(menuItem("Preview",            this::onPreview));
        menu.addSeparator();
        menu.add(menuItem("Replace with File…", this::onReplaceEntry));
        menu.add(menuItem("Remove",             this::onRemoveSelected));
        menu.addSeparator();
        menu.add(menuItem("Extract Selected",   this::onExtractSelected));
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
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        Path selected = fc.getSelectedFile().toPath();
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
                    for (PodArchive.Entry entry : archive.getEntries()) {
                        editableEntries.add(new EditableEntry(entry.name(),
                                archive.getEntryBytes(entry)));
                    }
                    dirty = false;
                    refreshTable();
                    setTitle(TITLE + " — " + selected.getFileName());
                } catch (Exception ex) {
                    showError("Failed to open archive", ex);
                }
            }
        }.execute();
    }

    /** Clears the editor for a brand-new empty archive. */
    private void onNew() {
        if (!confirmDiscardChanges()) return;
        editableEntries.clear();
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
        fc.setDialogTitle("Open Manifest (.lst)");
        fc.setFileFilter(new FileNameExtensionFilter("Manifest files (*.lst)", "lst"));
        fc.setAcceptAllFileFilterUsed(true);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        Path manifestPath = fc.getSelectedFile().toPath();
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
                    for (PodArchiveWriter.Blob b : blobs) {
                        editableEntries.add(new EditableEntry(b.name(), b.data()));
                    }
                    archiveComment = "";
                    commentField.setText("");
                    openedArchive = null;
                    session.setSourceFolderPath(sourceFolder);
                    dirty = true;
                    refreshTable();
                    setTitle(TITLE + " — " + manifestPath.getFileName() + " (manifest)");
                    progressLabel.setText(blobs.size() + " entries loaded from manifest.");
                } catch (Exception ex) {
                    showError("Failed to load manifest", ex);
                }
            }
        }.execute();
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
        // Remove in reverse order so indices stay stable
        for (int i = rows.length - 1; i >= 0; i--) {
            editableEntries.remove(rows[i]);
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
        int row = entryTable.getSelectedRow();
        if (row < 0 || entryTable.getSelectedRowCount() != 1) {
            JOptionPane.showMessageDialog(this, "Select exactly one entry to replace.", TITLE,
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Replace '" + editableEntries.get(row).name() + "' with…");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        try {
            byte[] newData = Files.readAllBytes(fc.getSelectedFile().toPath());
            EditableEntry old = editableEntries.get(row);
            editableEntries.set(row, new EditableEntry(old.name(), newData));
            markDirty();
            refreshTable();
            entryTable.setRowSelectionInterval(row, row);
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
        extractEntries(rows, dest, session.isExtractToSingleOutputFile());
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
            new PodIniMounter().mount(podName, searchRoot);
            JOptionPane.showMessageDialog(this, "POD mounted successfully.", TITLE,
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (PodIniMounter.AlreadyMountedException ex) {
            JOptionPane.showMessageDialog(this, "POD already mounted.", TITLE,
                    JOptionPane.WARNING_MESSAGE);
        } catch (PodIniMounter.MaxPodsExceededException ex) {
            JOptionPane.showMessageDialog(this, "Maximum Pods already mounted!", TITLE,
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
        if (row < 0 || row >= editableEntries.size()) return;
        EditableEntry e = editableEntries.get(row);
        new PreviewWindow(this, e.name(), e.data(), openedArchive).setVisible(true);
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
        tableModel.setRowCount(0);
        long offset = POD_ITEM_COUNT_BYTES + POD_COMMENT_BYTES
                + (long) editableEntries.size() * POD_ENTRY_BYTES;
        for (EditableEntry e : editableEntries) {
            tableModel.addRow(new Object[]{e.name(), e.data().length, offset});
            offset += e.data().length;
        }
        archiveCountLabel.setText(String.valueOf(editableEntries.size()));
        archiveSizeLabel.setText(String.format("%,d", offset));
        updateSelectedCount();
        updateDirtyLabel();
    }

    private void updateSelectedCount() {
        selectedCountLabel.setText(String.valueOf(entryTable.getSelectedRowCount()));
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
        if (index >= 0 && index < tableModel.getRowCount()) {
            entryTable.setRowSelectionInterval(index, index);
            entryTable.scrollRectToVisible(entryTable.getCellRect(index, 0, true));
        }
    }

    private Path chooseFolder(String title) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(title);
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        return fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION
                ? fc.getSelectedFile().toPath() : null;
    }

    private Path chooseSaveFile(String title, String ext) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(title);
        fc.setFileFilter(new FileNameExtensionFilter(ext.toUpperCase() + " files", ext));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return null;
        Path p = fc.getSelectedFile().toPath();
        if (!p.getFileName().toString().toLowerCase().endsWith("." + ext)) {
            p = p.resolveSibling(p.getFileName() + "." + ext);
        }
        return p;
    }
}
