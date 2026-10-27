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
import com.mtm2.jpod.io.pod.PodArchiveValidator;
import com.mtm2.jpod.io.pod.PodDataSource;
import com.mtm2.jpod.io.pod.PodEntryDescriber;
import com.mtm2.jpod.io.pod.PodEntryDisabling;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.*;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
    private final Set<String> explicitFolderPaths = new HashSet<>();

    /** 80-char archive comment written into the POD header. */
    private String archiveComment = "";

    /** True if editableEntries have been modified since last open/save. */
    private boolean dirty = false;

    /** The last POD archive that was opened from disk; null for new/manifest archives. */
    private PodArchive openedArchive = null;
    /**
     * Set when the archive on disk already repeated an entry name. Those archives
     * predate JPod and must stay savable, so the duplicate rule is relaxed for them
     * while still applying to anything the user adds.
     */
    private boolean openedWithDuplicateNames = false;
    private Path currentArchivePath = null;
    private PodArchive.Format outputFormat = PodArchive.Format.POD1;
    private final List<PodArchive.AuditEntry> auditEntries = new ArrayList<>();
    private boolean auditEnabled = true;
    private String auditAuthor = System.getProperty("user.name", "user");

    private final PodSession session = new PodSession();
    private final PodArchiveReader reader = new PodArchiveReader();
    /**
     * Stored preferences, read on first use. Nothing needs them until a menu or a
     * file chooser opens, so reading them in the constructor only delayed the first
     * paint.
     */
    private AppConfig configCache;
    private JMenu recentFilesMenu;

    /**
     * What the status bar says when nothing is loaded. The counters are hidden then
     * rather than shown as zeroes: the projected size of nothing is the 84-byte POD1
     * header, which is arithmetic rather than information.
     */
    private static final String EMPTY_STATE_HINT =
            "No archive open. Open a POD file, or drag files in to start a new one.";

    private final Timer selectionCountTimer = new Timer(50, e -> updateSelectedCount());
    private boolean suppressSelectionEvents;

    // --- UI components ---
    private final BrowserTableModel tableModel;
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

    /**
     * One entry in the editor. {@code nameField} is the directory field the entry
     * was read from, carried so a re-save reproduces it byte for byte; it is null
     * for entries added from disk or from a manifest.
     *
     * <p>An entry read from an archive references its payload in the archive rather
     * than holding a copy. Browsing, sorting and filtering need only names and
     * lengths, so the bytes stay on disk until a preview, an extract or a save asks
     * for them.
     */
    private static final class EditableEntry {
        private final String name;
        private final byte[] bytes;
        private final PodDataSource source;
        private final long offset;
        private final int length;
        private final byte[] nameField;
        private final String embeddedPaletteName;
        private final long timestamp;

        /** An entry whose bytes are held in memory: added from disk or a manifest. */
        private EditableEntry(String name, byte[] data, byte[] nameField,
                String embeddedPaletteName, long timestamp) {
            this(name, data, null, 0, data.length, nameField, embeddedPaletteName, timestamp);
        }

        private EditableEntry(String name, byte[] bytes, PodDataSource source, long offset,
                int length, byte[] nameField, String embeddedPaletteName, long timestamp) {
            this.name = name;
            this.bytes = bytes;
            this.source = source;
            this.offset = offset;
            this.length = length;
            this.nameField = nameField;
            this.embeddedPaletteName = embeddedPaletteName;
            this.timestamp = timestamp;
        }

        /** An entry that reads its payload from an open archive when asked. */
        private static EditableEntry fromArchive(PodArchive archive, PodArchive.Entry entry) {
            return new EditableEntry(entry.name(), null, archive.getData(), entry.offset(),
                    Math.toIntExact(entry.length()), entry.nameField(), entry.embeddedPaletteName(),
                    entry.timestamp());
        }

        String name() { return name; }

        int length() { return length; }

        byte[] nameField() { return nameField; }

        String embeddedPaletteName() { return embeddedPaletteName; }

        long timestamp() { return timestamp; }

        /** The payload as one array, read from the archive if it is not held. */
        byte[] data() {
            return bytes != null ? bytes : source.readExact(offset, length);
        }

        EditableEntry withName(String newName) {
            return new EditableEntry(newName, bytes, source, offset, length, nameField,
                    embeddedPaletteName, timestamp);
        }

        /** Streams the payload without holding it in memory. */
        void copyTo(OutputStream target, byte[] scratch) throws IOException {
            if (bytes != null) {
                target.write(bytes);
            } else {
                source.copyTo(offset, length, target, scratch);
            }
        }

        PodArchiveWriter.Blob toBlob() {
            return bytes != null
                    ? new PodArchiveWriter.Blob(name, bytes, nameField, embeddedPaletteName, timestamp)
                    : new PodArchiveWriter.Blob(name, source, offset, length, nameField,
                            embeddedPaletteName, timestamp);
        }
    }

    private AppConfig config() {
        if (configCache == null) {
            configCache = AppConfig.load();
        }
        return configCache;
    }

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public MainWindow() {
        super(TITLE);
        // Closing asks first when there are unsaved changes, and releases the archive.
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                requestClose();
            }
        });
        setSize(860, 600);
        setLocationRelativeTo(null);

        tableModel = new BrowserTableModel();
        entryTable = new JTable(tableModel);
        entryTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        entryTable.getColumnModel().getColumn(0).setPreferredWidth(300);
        entryTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        entryTable.getColumnModel().getColumn(2).setPreferredWidth(280);
        entryTable.getColumnModel().getColumn(0).setCellRenderer(new BrowserNameCellRenderer());
        DefaultTableCellRenderer sizeRenderer = new DefaultTableCellRenderer();
        sizeRenderer.setHorizontalAlignment(SwingConstants.RIGHT);
        entryTable.getColumnModel().getColumn(1).setCellRenderer(sizeRenderer);
        entryTable.setRowHeight(Math.max(entryTable.getRowHeight(), 22));
        // Dragging or shift-clicking across a large range changes the selection once
        // per row, and each count re-reads the whole selection, so the changes are
        // coalesced into one recompute.
        selectionCountTimer.setRepeats(false);
        entryTable.getSelectionModel().addListSelectionListener(e -> {
            if (!suppressSelectionEvents) selectionCountTimer.restart();
        });
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
        entryTable.setDragEnabled(true);
        entryTable.setDropMode(DropMode.ON);
        entryTable.setTransferHandler(new ArchiveEntryTransferHandler());

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
        browserBar.add(new JLabel("File filter: "), BorderLayout.WEST);
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
        refreshTable();
        progressLabel.setText(EMPTY_STATE_HINT);
    }

    /** Opens a POD, EPD or response list given on the command line. */
    public void openAtStartup(Path path) {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".lst") || lower.endsWith(".rsp")) {
            openManifestPath(path);
        } else {
            openPodPath(path);
        }
    }

    /** Asks about unsaved changes, then releases the archive and exits. */
    private void requestClose() {
        if (!confirmDiscardChanges()) return;
        // The open archive holds a file handle for as long as it lives.
        closeOpenedArchive();
        selectionCountTimer.stop();
        dispose();
        System.exit(0);
    }

    // -------------------------------------------------------------------------
    // Toolbar / Menu
    // -------------------------------------------------------------------------

    /**
     * The toolbar: the actions worth a label carry one, the rest are icon-only with a
     * tooltip, and the ones that come in families sit behind a split button whose
     * face runs the most common of them. Everything here is also in the menus, so
     * nothing is reachable only through a dropdown.
     */
    private JToolBar buildToolBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setRollover(true);

        addSplitButton(bar, "New", "Start a new archive", ToolbarIcons.NEW, this::onNew, () -> List.of(
                menuItem("New Archive", this::onNew),
                menuItem("Open Response List File\u2026", this::onOpenManifest)));
        addSplitButton(bar, "Open", "Open a POD or EPD archive", ToolbarIcons.OPEN, this::onOpen,
                this::openButtonMenu);
        bar.add(labelledButton("Save", "Save over the open archive", ToolbarIcons.SAVE, this::onSave));
        bar.add(labelledButton("Save As", "Write the entry list to a new archive", ToolbarIcons.SAVE_AS,
                this::onSaveAs));
        bar.addSeparator();
        addSplitButton(bar, "Add", "Add files to the archive", ToolbarIcons.ADD, this::onAddFiles, () -> List.of(
                menuItem("Add Files\u2026", this::onAddFiles),
                menuItem("Add Folder\u2026", this::onAddFolder),
                menuItem("Create Folder\u2026", this::onCreateFolder)));
        addSplitButton(bar, "Extract", "Extract the selected entries", ToolbarIcons.EXTRACT,
                this::onExtractSelected, () -> List.of(
                        menuItem("Extract Selected\u2026", this::onExtractSelected),
                        menuItem("Extract All\u2026", this::onExtractAll)));
        bar.add(iconButton("Remove the selected entries", ToolbarIcons.REMOVE, this::onRemoveSelected));
        bar.addSeparator();
        bar.add(iconButton("Open every folder", ToolbarIcons.EXPAND,
                () -> withBusy("Expanding folders\u2026", this::expandAllFolders)));
        bar.add(iconButton("Close every folder", ToolbarIcons.COLLAPSE,
                () -> withBusy("Collapsing folders\u2026", this::collapseAllFolders)));
        bar.add(iconButton("Find entries by name or size", ToolbarIcons.SEARCH, this::onSearch));
        bar.add(Box.createHorizontalGlue());
        bar.add(iconButton("About JPod", ToolbarIcons.ABOUT, this::onAbout));
        return bar;
    }

    /** Open's dropdown: the open commands, then the recent files, built as it opens. */
    private List<JMenuItem> openButtonMenu() {
        List<JMenuItem> items = new ArrayList<>();
        items.add(menuItem("Open POD\u2026", this::onOpen));
        items.add(menuItem("Open Response List File\u2026", this::onOpenManifest));
        items.add(null);
        List<Path> recents = config().recentOpenedFiles();
        if (recents.isEmpty()) {
            JMenuItem none = new JMenuItem("(no recent files)");
            none.setEnabled(false);
            items.add(none);
        }
        for (Path path : recents) {
            JMenuItem item = menuItem(path.getFileName() != null ? path.getFileName().toString()
                    : path.toString(), () -> openRecentFile(path));
            item.setToolTipText(path.toString());
            items.add(item);
        }
        return items;
    }

    private void buildMenuBar() {
        JMenuBar mb = new JMenuBar();

        JMenu fileMenu = new JMenu("File");
        fileMenu.add(menuItem("Open POD\u2026",        this::onOpen));
        fileMenu.add(menuItem("New Archive",       this::onNew));
        fileMenu.add(menuItem("Open Response List File\u2026",   this::onOpenManifest));
        recentFilesMenu = new JMenu("Open Recent");
        // Built as it opens, so the preferences are not read until something needs them.
        recentFilesMenu.addMenuListener(new javax.swing.event.MenuListener() {
            @Override public void menuSelected(javax.swing.event.MenuEvent e) { refreshRecentFilesMenu(); }
            @Override public void menuDeselected(javax.swing.event.MenuEvent e) { }
            @Override public void menuCanceled(javax.swing.event.MenuEvent e) { }
        });
        fileMenu.add(recentFilesMenu);
        fileMenu.addSeparator();
        fileMenu.add(menuItem("Add Files\u2026",        this::onAddFiles));
        fileMenu.add(menuItem("Add Folder\u2026",       this::onAddFolder));
        fileMenu.add(menuItem("Create Folder\u2026",    this::onCreateFolder));
        fileMenu.add(menuItem("Remove Selected",   this::onRemoveSelected));
        fileMenu.add(menuItem("Save",              this::onSave));
        fileMenu.add(menuItem("Save As\u2026",          this::onSaveAs));
        fileMenu.addSeparator();
        fileMenu.add(menuItem("Extract All\u2026",      this::onExtractAll));
        fileMenu.add(menuItem("Extract Selected\u2026", this::onExtractSelected));
        fileMenu.addSeparator();
        fileMenu.add(menuItem("Save .inf Report\u2026", this::onExportInfo));
        fileMenu.add(menuItem("Save .lst List\u2026",   this::onExportList));
        fileMenu.addSeparator();
        fileMenu.add(menuItem("Exit", this::requestClose));

        JMenu viewMenu = new JMenu("View");
        viewMenu.add(menuItem("Expand All Folders", () -> withBusy("Expanding folders\u2026", this::expandAllFolders)));
        viewMenu.add(menuItem("Collapse All Folders", () -> withBusy("Collapsing folders\u2026", this::collapseAllFolders)));

        JMenu toolMenu = new JMenu("Tools");
        toolMenu.add(menuItem("Mount in pod.ini\u2026", this::onMount));
        toolMenu.add(menuItem("Search\u2026",           this::onSearch));
        toolMenu.add(menuItem("Validate Archive\u2026", this::onValidate));
        toolMenu.addSeparator();
        toolMenu.add(menuItem("Disable in Game", () -> applyDisabling(true)));
        toolMenu.add(menuItem("Enable in Game", () -> applyDisabling(false)));
        toolMenu.addSeparator();
        JCheckBoxMenuItem auditItem = new JCheckBoxMenuItem("Record POD2 audit history", auditEnabled);
        auditItem.addActionListener(e -> auditEnabled = auditItem.isSelected());
        toolMenu.add(auditItem);
        toolMenu.add(menuItem("Set Audit Author\u2026", this::onSetAuditAuthor));
        toolMenu.add(menuItem("View POD2 Audit History\u2026", this::onViewAuditHistory));

        JMenu helpMenu = new JMenu("Help");
        helpMenu.add(menuItem("About JPod\u2026", this::onAbout));

        mb.add(fileMenu);
        mb.add(viewMenu);
        mb.add(toolMenu);
        mb.add(helpMenu);
        setJMenuBar(mb);
    }

    /** A toolbar button showing its icon and its label. */
    private static JButton labelledButton(String label, String tooltip, Icon icon, Runnable action) {
        JButton button = new JButton(label, icon);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.addActionListener(e -> action.run());
        return button;
    }

    /** A toolbar button showing only its icon; the tooltip says what it does. */
    private static JButton iconButton(String tooltip, Icon icon, Runnable action) {
        JButton button = new JButton(icon);
        button.setToolTipText(tooltip);
        button.getAccessibleContext().setAccessibleName(tooltip);
        button.setFocusable(false);
        button.addActionListener(e -> action.run());
        return button;
    }

    /**
     * A split button: the face runs {@code defaultAction}, the arrow beside it opens the
     * rest. A {@code null} item in the menu is a separator.
     */
    private static void addSplitButton(JToolBar bar, String label, String tooltip, Icon icon,
            Runnable defaultAction, java.util.function.Supplier<List<JMenuItem>> items) {
        JButton face = labelledButton(label, tooltip, icon, defaultAction);
        JButton arrow = new JButton(ToolbarIcons.ARROW);
        arrow.setToolTipText(label + " options");
        arrow.getAccessibleContext().setAccessibleName(label + " options");
        arrow.setFocusable(false);
        arrow.setMargin(new Insets(arrow.getMargin().top, 1, arrow.getMargin().bottom, 1));
        arrow.addActionListener(e -> {
            JPopupMenu menu = new JPopupMenu();
            for (JMenuItem item : items.get()) {
                if (item == null) menu.addSeparator();
                else menu.add(item);
            }
            menu.show(face, 0, face.getHeight());
        });
        bar.add(face);
        bar.add(arrow);
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

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.add(archiveSizeLabel);
        left.add(archiveCountLabel);
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
        int[] selection = selectedSourceRows();
        boolean anyEnabled = false;
        boolean anyDisabled = false;
        for (int index : selection) {
            String name = editableEntries.get(index).name();
            anyEnabled |= PodEntryDisabling.canDisable(name);
            anyDisabled |= PodEntryDisabling.canEnable(name);
        }
        JPopupMenu menu = new JPopupMenu();
        if (browserRow != null && browserRow.folder()) {
            menu.add(menuItem(browserRow.collapsed() ? "Expand" : "Collapse", () -> toggleFolderRow(row)));
            menu.addSeparator();
            menu.add(menuItem("Rename Folder…", () -> onRenameSelected(row)));
            menu.add(menuItem("Move To Folder…", this::onMoveSelected));
            menu.add(menuItem("Remove", this::onRemoveSelected));
            addDisablingItems(menu, anyEnabled, anyDisabled);
            menu.add(menuItem("Extract Selected", this::onExtractSelected));
        } else {
            menu.add(menuItem("Preview",            this::onPreview));
            menu.addSeparator();
            menu.add(menuItem("Rename…",            () -> onRenameSelected(row)));
            menu.add(menuItem("Move To Folder…",    this::onMoveSelected));
            menu.add(menuItem("Replace with File…", this::onReplaceEntry));
            menu.add(menuItem("Remove",             this::onRemoveSelected));
            addDisablingItems(menu, anyEnabled, anyDisabled);
            menu.addSeparator();
            menu.add(menuItem("Extract Selected",   this::onExtractSelected));
        }
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    /**
     * Offers the CommPatch 26 disable and enable renames, but only for a selection that
     * has something to rename. A pod of art has neither item.
     */
    private void addDisablingItems(JPopupMenu menu, boolean anyEnabled, boolean anyDisabled) {
        if (!anyEnabled && !anyDisabled) return;
        menu.addSeparator();
        if (anyEnabled) menu.add(menuItem("Disable in Game", () -> applyDisabling(true)));
        if (anyDisabled) menu.add(menuItem("Enable in Game", () -> applyDisabling(false)));
    }

    /**
     * Renames the selected tracks and trucks between the extensions the game reads and
     * the ones it passes over. The payload is untouched, so a disabled addon still
     * costs its space in the pod and comes back byte for byte.
     */
    private void applyDisabling(boolean disable) {
        int[] indices = selectedSourceRows();
        if (indices.length == 0) {
            JOptionPane.showMessageDialog(this, "Select the track or truck entries to "
                    + (disable ? "disable." : "enable."), TITLE, JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<EditableEntry> changed = new ArrayList<>(editableEntries);
        List<String[]> renamed = new ArrayList<>();
        for (int index : indices) {
            EditableEntry entry = changed.get(index);
            String newName = disable ? PodEntryDisabling.disable(entry.name())
                    : PodEntryDisabling.enable(entry.name());
            if (newName == null) continue;
            changed.set(index, renamedEntry(entry, newName));
            renamed.add(new String[] {entry.name(), newName, Integer.toString(index)});
        }
        if (renamed.isEmpty()) {
            progressLabel.setText("Nothing in the selection to " + (disable ? "disable." : "enable."));
            return;
        }
        if (!validateEditedEntries(changed)) return;
        editableEntries.clear();
        editableEntries.addAll(changed);
        for (String[] rename : renamed) {
            addMoveAudit(rename[0], rename[1], editableEntries.get(Integer.parseInt(rename[2])));
        }
        markDirty();
        refreshTable();
        // Worth saying every time: the rename is invisible in single player, but the
        // other end of a multiplayer game sees a pod that no longer matches theirs.
        progressLabel.setText((disable ? "Disabled " : "Enabled ") + renamed.size()
                + (renamed.size() == 1 ? " entry" : " entries")
                + ". Changing a track pod's status can trigger the multiplayer Different Version message.");
    }

    // -------------------------------------------------------------------------
    // Actions - open / new / manifest
    // -------------------------------------------------------------------------

    private void onOpen() {
        if (!confirmDiscardChanges()) return;
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Open POD Archive");
        fc.setFileFilter(new FileNameExtensionFilter("POD Archive files (*.pod, *.epd)", "pod", "POD", "epd", "EPD"));
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
        closeOpenedArchive();
        openedWithDuplicateNames = false;
        currentArchivePath = null;
        outputFormat = PodArchive.Format.POD1;
        auditEntries.clear();
        explicitFolderPaths.clear();
        dirty = false;
        session.reset();
        refreshTable();
        setTitle(TITLE + " - New Archive");
        progressLabel.setText("New archive. Add files or drag them in, then use Save As.");
    }

    /**
     * Opens a .lst manifest file and resolves each listed filename from the
     * manifest's directory (with parent fallback), then populates the editor.
     */
    private void onOpenManifest() {
        if (!confirmDiscardChanges()) return;
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Open Response List File (.lst)");
        fc.setFileFilter(new FileNameExtensionFilter("Response files (*.lst, *.rsp)", "lst", "rsp"));
        fc.setAcceptAllFileFilterUsed(true);
        applyRecentFolder(fc);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        openManifestPath(fc.getSelectedFile().toPath());
    }

    // -------------------------------------------------------------------------
    // Actions - editing
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

    /** Recursively imports a directory, retaining its top-level folder name. */
    private void onAddFolder() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Add Folder to Archive");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        applyRecentFolder(fc);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path directory = fc.getSelectedFile().toPath();
        try {
            List<File> files;
            try (var paths = Files.walk(directory)) {
                files = paths.filter(Files::isRegularFile).map(Path::toFile).toList();
            }
            addFilesToEntries(files, directory.getParent());
        } catch (IOException ex) {
            showError("Folder import failed", ex);
        }
    }

    private void onCreateFolder() {
        String parent = selectedFolderPath();
        String name = JOptionPane.showInputDialog(this, "Folder name:", "Create Folder",
                JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) return;
        String path = parent.isBlank() ? name.strip() : parent + "\\" + name.strip();
        try {
            PodArchiveWriter.validatePath(path);
            explicitFolderPaths.add(normalizeArchivePath(path));
            markDirty();
            refreshTable();
        } catch (IOException ex) {
            showError("Invalid folder", ex);
        }
    }

    private void onRenameSelected(int viewRow) {
        if (viewRow < 0 || viewRow >= displayedRows.size()) return;
        BrowserRow row = displayedRows.get(viewRow);
        String oldLabel = row.folder() ? row.displayName()
                : baseName(editableEntries.get(row.sourceIndex()).name());
        String replacement = JOptionPane.showInputDialog(this, "New name:", oldLabel);
        if (replacement == null || replacement.isBlank() || replacement.equals(oldLabel)) return;
        if (replacement.indexOf('/') >= 0 || replacement.indexOf('\\') >= 0) {
            JOptionPane.showMessageDialog(this, "Enter one file or folder name, not a path.",
                    TITLE, JOptionPane.WARNING_MESSAGE);
            return;
        }
        String oldPrefix = row.folder() ? row.folderPath().replace('/', '\\') : null;
        List<EditableEntry> changed = new ArrayList<>(editableEntries);
        for (int i = 0; i < changed.size(); i++) {
            EditableEntry entry = changed.get(i);
            String name = entry.name();
            String newName;
            if (row.folder()) {
                if (!normalizeArchivePath(name).startsWith(row.folderPath() + "/")) continue;
                int slash = oldPrefix.lastIndexOf('\\');
                String parent = slash >= 0 ? oldPrefix.substring(0, slash + 1) : "";
                String renamedPrefix = parent + replacement.strip();
                newName = renamedPrefix + name.substring(oldPrefix.length());
            } else {
                if (i != row.sourceIndex()) continue;
                int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
                newName = (slash >= 0 ? name.substring(0, slash + 1) : "") + replacement.strip();
            }
            changed.set(i, renamedEntry(entry, newName));
            addMoveAudit(name, newName, entry);
        }
        if (!validateEditedEntries(changed)) return;
        editableEntries.clear();
        editableEntries.addAll(changed);
        if (row.folder()) {
            explicitFolderPaths.remove(row.folderPath());
        }
        markDirty();
        refreshTable();
    }

    private void onMoveSelected() {
        int[] indices = selectedSourceRows();
        if (indices.length == 0) return;
        String sourceFolder = selectedFolderRoot();
        List<String> folders = new ArrayList<>();
        folders.add("(archive root)");
        knownFolderPaths.stream().sorted(String.CASE_INSENSITIVE_ORDER).forEach(folders::add);
        Object chosen = JOptionPane.showInputDialog(this, "Destination folder:", "Move Entries",
                JOptionPane.PLAIN_MESSAGE, null, folders.toArray(), folders.get(0));
        if (chosen == null) return;
        String destination = chosen.equals(folders.get(0)) ? "" : chosen.toString().replace('/', '\\');
        moveEntries(indices, destination, sourceFolder);
    }

    private void moveEntries(int[] indices, String destination) {
        moveEntries(indices, destination, null);
    }

    private void moveEntries(int[] indices, String destination, String sourceFolder) {
        String sourcePrefix = sourceFolder == null ? null : sourceFolder.replace('/', '\\');
        if (sourcePrefix != null && (destination.equalsIgnoreCase(sourcePrefix)
                || destination.regionMatches(true, 0, sourcePrefix + "\\", 0, sourcePrefix.length() + 1))) {
            JOptionPane.showMessageDialog(this, "A folder cannot be moved into itself.", TITLE,
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<EditableEntry> changed = new ArrayList<>(editableEntries);
        List<String[]> moved = new ArrayList<>();
        for (int index : indices) {
            EditableEntry entry = changed.get(index);
            String newName;
            if (sourcePrefix != null && entry.name().regionMatches(true, 0,
                    sourcePrefix + "\\", 0, sourcePrefix.length() + 1)) {
                String targetPrefix = destination.isBlank() ? baseName(sourcePrefix)
                        : destination + "\\" + baseName(sourcePrefix);
                newName = targetPrefix + entry.name().substring(sourcePrefix.length());
            } else {
                newName = destination.isBlank() ? baseName(entry.name())
                        : destination + "\\" + baseName(entry.name());
            }
            changed.set(index, renamedEntry(entry, newName));
            moved.add(new String[] {entry.name(), newName, Integer.toString(index)});
        }
        if (!validateEditedEntries(changed)) return;
        editableEntries.clear();
        editableEntries.addAll(changed);
        for (String[] move : moved) {
            addMoveAudit(move[0], move[1], editableEntries.get(Integer.parseInt(move[2])));
        }
        markDirty();
        refreshTable();
    }

    private String selectedFolderRoot() {
        int[] rows = entryTable.getSelectedRows();
        if (rows.length != 1 || rows[0] < 0 || rows[0] >= displayedRows.size()) return null;
        BrowserRow row = displayedRows.get(rows[0]);
        return row.folder() ? row.folderPath() : null;
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
            EditableEntry removed = editableEntries.get(sourceRows[i]);
            addAudit(PodArchive.AuditAction.REMOVE, removed.name(), removed.timestamp(),
                    removed.length(), 0, 0);
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
            // Replace swaps the payload and keeps the archive name, so the
            // original directory field still describes this entry.
            long now = Files.getLastModifiedTime(fc.getSelectedFile().toPath()).toInstant().getEpochSecond();
            editableEntries.set(row, new EditableEntry(old.name(), newData, old.nameField(),
                    old.embeddedPaletteName(), now));
            addAudit(PodArchive.AuditAction.CHANGE, old.name(), old.timestamp(),
                    old.length(), now, newData.length);
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
            JOptionPane.showMessageDialog(this, "Nothing to save - add files first.", TITLE,
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        PodArchive.Format selectedFormat = chooseOutputFormat();
        if (selectedFormat == null) return;
        Path target = chooseSaveFile("Save Archive As", "pod");
        if (target == null) return;
        saveTo(target, selectedFormat);
    }

    private void onSave() {
        if (currentArchivePath == null) {
            onSaveAs();
            return;
        }
        saveTo(currentArchivePath, outputFormat);
    }

    private void saveTo(Path target, PodArchive.Format requestedFormat) {
        archiveComment = commentField.getText();
        String savedComment = archiveComment;
        List<EditableEntry> snapshot = List.copyOf(editableEntries);
        List<PodArchiveWriter.Blob> blobs = toBlobs(snapshot);
        PodArchiveValidator.Result validation = PodArchiveValidator.validateForSave(
                savedComment, blobs, requestedFormat);
        if (!validation.isValid()) {
            showValidationResult(validation);
            return;
        }
        if (openedArchive != null && openedArchive.getFormat() == PodArchive.Format.POD2
                && validation.outputFormat() != PodArchive.Format.POD2) {
            int answer = JOptionPane.showConfirmDialog(this,
                    "Saving as " + validation.outputFormat().displayName()
                    + " discards POD2 timestamps and audit history. Continue?",
                    TITLE, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "Write " + validation.outputFormat().displayName() + " to:\n" + target + "?",
                "Confirm Archive Format", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;

        PodArchive.Format actual = validation.outputFormat();
        setBusy(true, "Saving " + target.getFileName() + "\u2026");
        // The blobs stream their payloads out of the archive being read, which may be
        // the very file being written. Its handle is released only once the new archive
        // is complete beside the target and every payload has been read.
        PodArchive reading = openedArchive;
        boolean[] released = {false};
        byte[] rawComment = openedArchive != null && openedArchive.isPod1Family()
                ? openedArchive.getCommentField() : null;
        List<PodArchive.AuditEntry> audits = actual == PodArchive.Format.POD2 && auditEnabled
                ? List.copyOf(auditEntries) : List.of();
        boolean allowDuplicates = openedWithDuplicateNames;
        new SwingWorker<PodArchive, Void>() {
            @Override protected PodArchive doInBackground() throws Exception {
                new PodArchiveWriter().write(target, savedComment, blobs,
                        new PodArchiveWriter.WriteOptions(actual, rawComment, audits, allowDuplicates),
                        () -> {
                            released[0] = true;
                            if (reading != null) reading.close();
                        });
                // The entry list referenced the archive that was just replaced, and any
                // entry added from disk still held its bytes. Reading back what is now
                // on disk fixes both, and costs one directory read.
                return reader.read(target);
            }
            @Override protected void done() {
                setBusy(false, null);
                try {
                    PodArchive written = get();
                    rebindTo(target, written);
                    dirty = false;
                    currentArchivePath = target;
                    outputFormat = written.getFormat();
                    updateDirtyLabel();
                    session.setTargetFolderPath(target.getParent());
                    session.setTargetFileName(target.getFileName().toString());
                    progressLabel.setText("Saved: " + target.getFileName()
                            + " (" + written.getFormat().displayName() + ")");
                    setTitle(TITLE + " - " + target.getFileName());
                } catch (Exception ex) {
                    showError("Save failed", ex);
                    // The source handle was already released, so the entries point at
                    // bytes that are no longer reachable. Leaving them on display would
                    // fail on the next preview or extract.
                    if (released[0]) {
                        openedArchive = null;
                        session.setOpenArchive(null);
                        editableEntries.clear();
                        resetFolderBrowserState();
                        commentField.setText("");
                        archiveComment = "";
                        currentArchivePath = null;
                        dirty = false;
                        refreshTable();
                        progressLabel.setText("Save failed and the open archive was closed. Reopen it to continue.");
                    }
                }
            }
        }.execute();
    }

    // -------------------------------------------------------------------------
    // Actions - extract
    // -------------------------------------------------------------------------

    private void onExtractAll() {
        if (editableEntries.isEmpty()) { showNoArchive(); return; }
        Path dest = chooseFolder("Extract All - Choose Destination");
        if (dest == null) return;
        extractEntries(indexRange(editableEntries.size()), dest);
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
        extractEntries(selectedSourceRows(), dest);
    }

    /** Writes the given entry indices to {@code destRoot}. */
    private void extractEntries(int[] indices, Path destRoot) {
        List<EditableEntry> toExtract = new ArrayList<>();
        for (int i : indices) toExtract.add(editableEntries.get(i));
        int total = toExtract.size();
        boolean preserveFolders = session.isPreserveExtractFolderStructure();

        setBusy(true, "Extracting " + total + (total == 1 ? " entry\u2026" : " entries\u2026"));
        new SwingWorker<Void, Integer>() {
            @Override protected Void doInBackground() throws Exception {
                // Streamed through one buffer rather than materialized: extracting a
                // whole archive should not need the whole archive in memory.
                byte[] scratch = PodDataSource.newScratch();
                for (int i = 0; i < toExtract.size(); i++) {
                    publish(i + 1);
                    EditableEntry e = toExtract.get(i);
                    Path dest = resolveEntryDest(destRoot, e.name(), preserveFolders);
                    if (dest.getParent() != null) {
                        Files.createDirectories(dest.getParent());
                    }
                    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(dest),
                            PodDataSource.SCRATCH_SIZE)) {
                        e.copyTo(out, scratch);
                    }
                }
                return null;
            }
            @Override protected void process(List<Integer> chunks) {
                progressLabel.setText("Extracting " + chunks.get(chunks.size() - 1) + " of " + total + "…");
            }
            @Override protected void done() {
                setBusy(false, "Extraction complete.");
                try { get(); } catch (Exception ex) { showError("Extraction failed", ex); }
            }
        }.execute();
    }

    /** Converts a POD entry name (backslash separators) to an OS path under {@code root}. */
    private static Path resolveEntryDest(Path root, String entryName, boolean preserveFolders) {
        String clean = entryName.replace('\0', ' ').strip();
        String[] parts = clean.split("[/\\\\]");
        if (!preserveFolders) {
            return root.resolve(parts[parts.length - 1]);
        }
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
    // Actions - reports / export
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
    // Actions - tools
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
            synth.add(new PodArchive.Entry(e.name(), e.length(), 0));
        }
        new SearchDialog(this, synth, this::selectTableRow).setVisible(true);
    }

    private void onValidate() {
        PodArchiveValidator.Result result = openedArchive != null && !dirty
                ? PodArchiveValidator.validateOpened(openedArchive)
                : PodArchiveValidator.validateForSave(commentField.getText(),
                        toBlobs(editableEntries), outputFormat);
        showValidationResult(result);
    }

    /** True when the archive as read already collides two entry names. */
    private static boolean hasDuplicateNames(PodArchive archive) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (PodArchive.Entry entry : archive.getEntries()) {
            if (!seen.add(PodArchiveWriter.normalizeName(entry.name())
                    .toUpperCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private void onSetAuditAuthor() {
        String value = JOptionPane.showInputDialog(this, "POD2 audit author:", auditAuthor);
        if (value != null && !value.isBlank()) auditAuthor = value.strip();
    }

    private void onViewAuditHistory() {
        String[] columns = {"Time", "User", "Action", "Entry", "Old size", "New size"};
        Object[][] rows = new Object[auditEntries.size()][columns.length];
        for (int i = 0; i < auditEntries.size(); i++) {
            PodArchive.AuditEntry audit = auditEntries.get(i);
            rows[i] = new Object[] {Instant.ofEpochSecond(audit.timestamp()), audit.user(),
                    audit.action(), audit.entryPath(), audit.oldSize(), audit.newSize()};
        }
        JTable table = new JTable(rows, columns);
        table.setAutoCreateRowSorter(true);
        JScrollPane pane = new JScrollPane(table);
        pane.setPreferredSize(new Dimension(760, 360));
        JOptionPane.showMessageDialog(this, pane, "POD2 Audit History",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private PodArchive.Format chooseOutputFormat() {
        // EPD is read-only, so POD1 and POD2 are the writable formats.
        PodArchive.Format[] formats = {PodArchive.Format.POD1, PodArchive.Format.POD2};
        return (PodArchive.Format) JOptionPane.showInputDialog(this, "Archive format:",
                "Save Archive As", JOptionPane.PLAIN_MESSAGE, null, formats, outputFormat);
    }

    private void showValidationResult(PodArchiveValidator.Result result) {
        StringBuilder message = new StringBuilder("Output format: ")
                .append(result.outputFormat().displayName()).append('\n');
        if (result.errors().isEmpty() && result.warnings().isEmpty()) {
            message.append("No problems found.");
        } else {
            for (String error : result.errors()) message.append("\nERROR: ").append(error);
            for (String warning : result.warnings()) message.append("\nWarning: ").append(warning);
        }
        JOptionPane.showMessageDialog(this, message.toString(), "Archive Validation",
                result.errors().isEmpty() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE);
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
        PreviewWindow.open(this, e.name(), e.data(), openedArchive, e.nameField());
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
        addFilesToEntries(files, null);
    }

    private void addFilesToEntries(List<File> files, Path relativeRoot) {
        int added = 0;
        for (File f : files) {
            if (f.isDirectory()) {
                try (var paths = Files.walk(f.toPath())) {
                    List<File> nested = paths.filter(Files::isRegularFile).map(Path::toFile).toList();
                    addFilesToEntries(nested, f.toPath().getParent());
                } catch (IOException ex) {
                    showError("Could not import folder " + f.getName(), ex);
                }
                continue;
            }
            if (!f.isFile()) continue;
            try {
                byte[] data = Files.readAllBytes(f.toPath());
                String name = relativeRoot == null ? f.getName()
                        : relativeRoot.relativize(f.toPath()).toString();
                name = PodArchiveWriter.normalizeName(name);
                int existing = findEntryIndex(name);
                long timestamp = Files.getLastModifiedTime(f.toPath()).toInstant().getEpochSecond();
                EditableEntry addedEntry = new EditableEntry(name, data, null, null, timestamp);
                if (existing >= 0) {
                    int answer = JOptionPane.showConfirmDialog(this,
                            "Entry already exists: " + name + "\nReplace it?", TITLE,
                            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (answer != JOptionPane.YES_OPTION) continue;
                    EditableEntry old = editableEntries.set(existing, addedEntry);
                    addAudit(PodArchive.AuditAction.CHANGE, name, old.timestamp(),
                            old.length(), timestamp, data.length);
                } else {
                    editableEntries.add(addedEntry);
                    addAudit(PodArchive.AuditAction.ADD, name, 0, 0, timestamp, data.length);
                }
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
        refreshTable(true);
    }

    /**
     * @param preserveSelection false when the caller sets the selection itself straight
     *        afterwards. Expanding a folder is the case that matters: its heading stands
     *        for every entry under it, so restoring that selection would re-select a
     *        thousand rows only to replace them.
     */
    private void refreshTable(boolean preserveSelection) {
        int[] selectedSourceRows = preserveSelection ? selectedSourceRows() : new int[0];
        displayedRows.clear();
        displayedRows.addAll(buildDisplayedRows());
        suppressSelectionEvents = true;
        try {
            tableModel.fireTableDataChanged();
            restoreSelection(selectedSourceRows);
        } finally {
            suppressSelectionEvents = false;
        }
        long offset = POD_ITEM_COUNT_BYTES + POD_COMMENT_BYTES
                + (long) editableEntries.size() * POD_ENTRY_BYTES;
        for (EditableEntry e : editableEntries) {
            offset += e.length();
        }
        boolean empty = editableEntries.isEmpty();
        archiveCountLabel.setText(empty ? "" : "Files: " + editableEntries.size());
        archiveSizeLabel.setText(empty ? "" : String.format("Size: %,d", offset));
        selectionCountTimer.stop();
        updateSelectedCount();
        updateDirtyLabel();
    }

    private String describeEntry(EditableEntry entry) {
        return PodEntryDescriber.describe(entry.name(), entry.length());
    }

    private void updateSelectedCount() {
        selectedCountLabel.setText(editableEntries.isEmpty() ? ""
                : "Selected: " + selectedSourceRows().length);
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

    /**
     * Turns the activity light red, shows the wait cursor, and says what is running.
     *
     * @param message what to show while busy; null leaves the status text alone,
     *                which is what a finishing call wants so its result is not
     *                overwritten
     */
    private void setBusy(boolean busy, String message) {
        activityIndicator.setBackground(busy ? COLOR_BUSY : COLOR_IDLE);
        activityIndicator.setToolTipText(busy ? "Working" : "Idle");
        setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
        if (message != null) {
            progressLabel.setText(message);
            // Paint now: otherwise the status bar only repaints once the operation lets
            // go of the event thread, which is exactly when the message stops mattering.
            progressLabel.paintImmediately(progressLabel.getVisibleRect());
            activityIndicator.paintImmediately(activityIndicator.getVisibleRect());
        }
    }

    /** Runs a synchronous view change with the activity light on. */
    private void withBusy(String message, Runnable action) {
        setBusy(true, message);
        try {
            action.run();
        } finally {
            setBusy(false, " ");
        }
    }

    /**
     * Fills the entry list from an open archive, referencing its payloads rather than
     * copying them.
     */
    private void loadEntriesFrom(PodArchive archive) {
        for (PodArchive.Entry entry : archive.getEntries()) {
            editableEntries.add(EditableEntry.fromArchive(archive, entry));
        }
    }

    /**
     * Releases the open archive's file handle. The entry list references that archive's
     * bytes, so nothing may read an entry's payload after this until the list is
     * rebuilt.
     */
    private void closeOpenedArchive() {
        if (openedArchive != null) {
            openedArchive.close();
        }
        openedArchive = null;
        session.setOpenArchive(null);
    }

    /**
     * Points the entry list at the archive just written, dropping whatever it
     * referenced before. The collapse state and the selection belong to the view and
     * survive.
     */
    private void rebindTo(Path path, PodArchive archive) {
        closeOpenedArchive();
        openedArchive = archive;
        session.setOpenArchive(archive);
        openedWithDuplicateNames = hasDuplicateNames(archive);
        session.setSourceFolderPath(path.getParent());
        session.setSourceFileName(path.getFileName().toString());
        session.setArchiveByteSize(archive.getData().length());
        archiveComment = archive.getComment();
        session.setArchiveComment(archiveComment);
        editableEntries.clear();
        loadEntriesFrom(archive);
        refreshTable();
    }

    private void showNoArchive() {
        JOptionPane.showMessageDialog(this, "No archive entries loaded.", TITLE,
                JOptionPane.WARNING_MESSAGE);
    }

    private void showError(String context, Exception ex) {
        JOptionPane.showMessageDialog(this, context + ":\n" + ex.getMessage(),
                TITLE, JOptionPane.ERROR_MESSAGE);
    }

    private List<PodArchiveWriter.Blob> toBlobs(List<EditableEntry> entries) {
        List<PodArchiveWriter.Blob> blobs = new ArrayList<>(entries.size());
        for (EditableEntry e : entries) {
            blobs.add(e.toBlob());
        }
        return blobs;
    }

    private int findEntryIndex(String name) {
        for (int i = 0; i < editableEntries.size(); i++) {
            if (editableEntries.get(i).name().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    private EditableEntry renamedEntry(EditableEntry entry, String newName) {
        // Keeps the payload where it is, so renaming in a large archive costs nothing
        // beyond the name.
        return entry.withName(PodArchiveWriter.normalizeName(newName));
    }

    private boolean validateEditedEntries(List<EditableEntry> entries) {
        PodArchiveValidator.Result validation = PodArchiveValidator.validateForSave(
                commentField.getText(), toBlobs(entries), outputFormat);
        if (!validation.isValid()) showValidationResult(validation);
        return validation.isValid();
    }

    private String selectedFolderPath() {
        int row = entryTable.getSelectedRow();
        if (row < 0 || row >= displayedRows.size()) return "";
        BrowserRow selected = displayedRows.get(row);
        if (selected.folder()) return selected.folderPath().replace('/', '\\');
        String name = editableEntries.get(selected.sourceIndex()).name();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return slash >= 0 ? name.substring(0, slash) : "";
    }

    private void addMoveAudit(String oldName, String newName, EditableEntry entry) {
        addAudit(PodArchive.AuditAction.REMOVE, oldName, entry.timestamp(),
                entry.length(), 0, 0);
        addAudit(PodArchive.AuditAction.ADD, newName, 0, 0,
                entry.timestamp(), entry.length());
    }

    private void addAudit(PodArchive.AuditAction action, String path,
            long oldTimestamp, long oldSize, long newTimestamp, long newSize) {
        if (!auditEnabled) return;
        auditEntries.add(new PodArchive.AuditEntry(auditAuthor, Instant.now().getEpochSecond(),
                action, path, oldTimestamp, oldSize, newTimestamp, newSize));
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

        setBusy(true, "Opening " + selected.getFileName() + "\u2026");
        new SwingWorker<PodArchive, Void>() {
            @Override protected PodArchive doInBackground() throws Exception {
                return reader.read(selected);
            }
            @Override protected void done() {
                setBusy(false, null);
                try {
                    PodArchive archive = get();
                    closeOpenedArchive();
                    openedArchive = archive;
                    openedWithDuplicateNames = hasDuplicateNames(archive);
                    currentArchivePath = selected;
                    outputFormat = archive.getFormat() == PodArchive.Format.POD2
                            ? PodArchive.Format.POD2 : PodArchive.Format.POD1;
                    auditEntries.clear();
                    auditEntries.addAll(archive.getAuditEntries());
                    explicitFolderPaths.clear();
                    session.setOpenArchive(archive);
                    archiveComment = archive.getComment();
                    session.setArchiveComment(archiveComment);
                    commentField.setText(archiveComment);
                    editableEntries.clear();
                    resetFolderBrowserState();
                    loadEntriesFrom(archive);
                    session.setArchiveByteSize(archive.getData().length());
                    dirty = false;
                    refreshTable();
                    rememberOpenedFile(selected);
                    setTitle(TITLE + " - " + selected.getFileName()  + " (" + archive.getFormat().displayName() + ")");
                    progressLabel.setText("Opened " + selected.getFileName() + ": "
                            + archive.getEntries().size() + " entries, "
                            + archive.getFormat().displayName() + ".");
                } catch (Exception ex) {
                    showError("Failed to open archive", ex);
                }
            }
        }.execute();
    }

    private void openManifestPath(Path manifestPath) {
        Path sourceFolder = manifestPath.getParent();

        setBusy(true, "Reading " + manifestPath.getFileName() + "\u2026");
        new SwingWorker<PodManifestParser.Manifest, Void>() {
            @Override protected PodManifestParser.Manifest doInBackground() throws Exception {
                return new PodManifestParser().parseManifest(manifestPath, sourceFolder);
            }
            @Override protected void done() {
                setBusy(false, null);
                try {
                    PodManifestParser.Manifest manifest = get();
                    List<PodArchiveWriter.Blob> blobs = manifest.blobs();
                    editableEntries.clear();
                    resetFolderBrowserState();
                    for (PodArchiveWriter.Blob b : blobs) {
                        editableEntries.add(new EditableEntry(b.name(), b.data(), b.nameField(),
                                b.embeddedPaletteName(), b.timestamp()));
                    }
                    archiveComment = manifest.volumeName();
                    commentField.setText(archiveComment);
                    closeOpenedArchive();
                    currentArchivePath = null;
                    outputFormat = PodArchive.Format.POD1;
                    auditEntries.clear();
                    explicitFolderPaths.clear();
                    session.setSourceFolderPath(sourceFolder);
                    session.setTargetFileName(manifest.podFileName());
                    dirty = true;
                    refreshTable();
                    rememberOpenedFile(manifestPath);
                    setTitle(TITLE + " - " + manifestPath.getFileName() + " (manifest)");
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
        List<Path> recents = config().recentOpenedFiles();
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
            configCache = config().withoutRecentOpenedFile(path);
            saveConfigQuietly();
            refreshRecentFilesMenu();
            return;
        }
        if (!confirmDiscardChanges()) {
            return;
        }
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pod") || lower.endsWith(".epd")) {
            openPodPath(path);
            return;
        }
        if (lower.endsWith(".lst") || lower.endsWith(".rsp")) {
            openManifestPath(path);
            return;
        }
        JOptionPane.showMessageDialog(
                this,
                "Recent file type is not supported anymore:\n" + path,
                TITLE,
                JOptionPane.WARNING_MESSAGE);
        configCache = config().withoutRecentOpenedFile(path);
        saveConfigQuietly();
        refreshRecentFilesMenu();
    }

    private void rememberOpenedFile(Path path) {
        configCache = config().withRecentOpenedFile(path);
        saveConfigQuietly();
        refreshRecentFilesMenu();
    }

    private void saveConfigQuietly() {
        try {
            config().save();
        } catch (IOException ex) {
            progressLabel.setText("Could not save recent files config.");
        }
    }

    private void applyRecentFolder(JFileChooser chooser) {
        Path base = null;
        if (session.getSourceFolderPath() != null && Files.isDirectory(session.getSourceFolderPath())) {
            base = session.getSourceFolderPath();
        } else if (!config().recentOpenedFiles().isEmpty()) {
            Path recent = config().recentOpenedFiles().get(0);
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
        if (sourceRows.length == 0) return;
        // One pass to index the view, rather than a scan of it per selected entry.
        int[] viewRowOf = new int[editableEntries.size()];
        java.util.Arrays.fill(viewRowOf, -1);
        for (int displayedRow = 0; displayedRow < displayedRows.size(); displayedRow++) {
            BrowserRow row = displayedRows.get(displayedRow);
            if (!row.folder() && row.sourceIndex() < viewRowOf.length) {
                viewRowOf[row.sourceIndex()] = displayedRow;
            }
        }
        ListSelectionModel selection = entryTable.getSelectionModel();
        selection.setValueIsAdjusting(true);
        for (int sourceRow : sourceRows) {
            int displayedRow = sourceRow < viewRowOf.length ? viewRowOf[sourceRow] : -1;
            if (displayedRow >= 0) {
                selection.addSelectionInterval(displayedRow, displayedRow);
            }
        }
        selection.setValueIsAdjusting(false);
    }

    private Comparator<Integer> comparatorForColumn(int column) {
        return switch (column) {
            case 0 -> Comparator.comparing(i -> editableEntries.get(i).name().toLowerCase(Locale.ROOT));
            case 1 -> Comparator.comparingInt(i -> editableEntries.get(i).length());
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
        refreshTable(false);
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
        for (String folderPath : explicitFolderPaths) {
            FolderNode cursor = root;
            String[] parts = folderPath.split("[/\\\\]+");
            for (String part : parts) {
                if (!part.isBlank()) cursor = cursor.childFolder(part, Integer.MAX_VALUE);
            }
        }
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
                        entry.length(),
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

    /** Internal table drag moves selected entries into the folder under the pointer. */
    private final class ArchiveEntryTransferHandler extends TransferHandler {
        @Override protected Transferable createTransferable(JComponent component) {
            int[] indices = selectedSourceRows();
            String root = selectedFolderRoot();
            String encodedIndices = java.util.Arrays.stream(indices)
                    .mapToObj(String::valueOf).collect(java.util.stream.Collectors.joining(","));
            return new StringSelection((root == null ? "" : root) + "\n" + encodedIndices);
        }

        @Override public int getSourceActions(JComponent component) { return MOVE; }

        @Override public boolean canImport(TransferSupport support) {
            return support.isDrop() && support.isDataFlavorSupported(DataFlavor.stringFlavor);
        }

        @Override public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            try {
                String text = (String) support.getTransferable().getTransferData(DataFlavor.stringFlavor);
                String[] payload = text.split("\\n", 2);
                String sourceFolder = payload[0].isBlank() ? null : payload[0];
                String encodedIndices = payload.length > 1 ? payload[1] : payload[0];
                int[] indices = java.util.Arrays.stream(encodedIndices.split(","))
                        .filter(value -> !value.isBlank()).mapToInt(Integer::parseInt).toArray();
                JTable.DropLocation location = (JTable.DropLocation) support.getDropLocation();
                int rowIndex = location.getRow();
                String destination = "";
                if (rowIndex >= 0 && rowIndex < displayedRows.size()) {
                    BrowserRow row = displayedRows.get(rowIndex);
                    if (row.folder()) destination = row.folderPath().replace('/', '\\');
                    else {
                        String name = editableEntries.get(row.sourceIndex()).name();
                        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
                        destination = slash >= 0 ? name.substring(0, slash) : "";
                    }
                }
                moveEntries(indices, destination, sourceFolder);
                return true;
            } catch (Exception ex) {
                return false;
            }
        }
    }

    /** The table reads straight from {@link #displayedRows}; no copy per refresh. */
    private final class BrowserTableModel extends javax.swing.table.AbstractTableModel {
        @Override public int getRowCount() { return displayedRows.size(); }

        @Override public int getColumnCount() { return baseColumnNames.length; }

        @Override public String getColumnName(int column) { return baseColumnNames[column]; }

        @Override public Object getValueAt(int rowIndex, int columnIndex) {
            BrowserRow row = displayedRows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.nameText();
                case 1 -> row.sizeText();
                default -> row.description();
            };
        }
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
            return folder != null ? 0L : editableEntries.get(fileIndex).length();
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
