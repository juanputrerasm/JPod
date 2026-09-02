package com.mtm2.jpod.ui;

import com.mtm2.jpod.io.RawImageDecoder;
import com.mtm2.jpod.io.pod.PodArchive;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Floating preview window that dispatches display mode based on the entry's file extension.
 *
 * <table>
 *   <tr><th>Extension(s)</th><th>Display mode</th></tr>
 *   <tr><td>{@code .raw}, {@code .clr}</td>
 *       <td>8-bit paletted image (64×64 art texture or 256×256 heightmap / CLR).
 *           Palette resolved via {@link #resolvePalette}: the palette named in the
 *           entry's own POD directory field, then same-name {@code .act} →
 *           directory sibling → {@code VGA.ACT} → {@code METALCR2.ACT} in archive →
 *           classpath resource → greyscale.</td></tr>
 *   <tr><td>{@code .act}</td><td>256-colour VGA palette swatch grid (16×16).</td></tr>
 *   <tr><td>{@code .wav}</td><td>Delegates to {@link AudioPlayerDialog}.</td></tr>
 *   <tr><td>{@code .bmp}, {@code .png}, {@code .jpg}, …</td>
 *       <td>Decoded by {@link javax.imageio.ImageIO}.</td></tr>
 *   <tr><td>{@code .txt}, {@code .def}, {@code .lvl}, {@code .sit}, …</td>
 *       <td>Scrollable plain-text viewer (ISO-8859-1).</td></tr>
 *   <tr><td>anything else</td><td>Hex dump of the first 4 096 bytes.</td></tr>
 * </table>
 */
public final class PreviewWindow extends JDialog {

    private static final int MAX_PREVIEW_W = 1024;
    private static final int MAX_PREVIEW_H = 768;
    private boolean previewCancelled;

    /**
     * Creates and lays out the preview window for the given archive entry.
     *
     * @param owner      parent frame used for relative positioning
     * @param entryName  POD entry name (path separators may be {@code \} or {@code /});
     *                   the extension determines which preview mode is used
     * @param data       raw byte content of the entry
     * @param archive    the open archive used to resolve a paired {@code .act} palette
     *                   for {@code .raw} / {@code .clr} files; may be {@code null}
     */
    public PreviewWindow(Frame owner, String entryName, byte[] data, PodArchive archive) {
        this(owner, entryName, data, archive, null);
    }

    /**
     * @param nameField the entry's POD directory field, when the caller has it;
     *                  it is what lets the stored palette name be used
     */
    public PreviewWindow(Frame owner, String entryName, byte[] data, PodArchive archive,
            byte[] nameField) {
        super(owner, "Preview - " + entryName, true);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        String upper = entryName.toUpperCase(Locale.ROOT);

        if (RawImageDecoder.isRawImage(upper)) {
            buildRawImagePanel(entryName, data, archive, nameField);
        } else if (RawImageDecoder.isActPalette(upper)) {
            buildActPalettePanel(entryName, data);
        } else if (RawImageDecoder.isTextFile(upper)) {
            buildTextPanel(data);
        } else {
            // Try ImageIO first, fall back to hex dump
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
                if (img != null) {
                    buildImagePanel(img);
                } else {
                    buildHexPanel(data);
                }
            } catch (IOException ex) {
                buildHexPanel(data);
            }
        }

        setLocationRelativeTo(owner);
    }

    /**
     * Opens the right window for an entry and blocks until it is closed.
     *
     * <p>A {@code .wav} goes straight to {@link AudioPlayerDialog}. Routing it
     * here rather than inside the constructor is what lets this window be modal:
     * the old placeholder frame disposed itself from an {@code invokeLater} that
     * a modal dialog's nested event loop would have run at the wrong moment.
     */
    public static void open(Frame owner, String entryName, byte[] data, PodArchive archive,
            byte[] nameField) {
        if (entryName.toUpperCase(Locale.ROOT).endsWith(".WAV")) {
            new AudioPlayerDialog(owner, entryName, data).setVisible(true);
            return;
        }

        PreviewWindow window = new PreviewWindow(owner, entryName, data, archive, nameField);
        if (window.isPreviewCancelled()) {
            window.dispose();
            return;
        }

        window.setVisible(true);
    }

    public boolean isPreviewCancelled() {
        return previewCancelled;
    }

    // -------------------------------------------------------------------------
    // RAW / CLR image
    // -------------------------------------------------------------------------

    private void buildRawImagePanel(String entryName, byte[] data, PodArchive archive,
            byte[] nameField) {
        int[] dims = RawImageDecoder.detectDimensions(data.length);
        int[] palette = resolvePalette(entryName, archive, nameField);
        if (dims == null) {
            RawPreviewOptions options = promptForRawDimensions(entryName, data.length, archive, nameField);
            if (options == null) {
                previewCancelled = true;
                return;
            }
            dims = options.dimensions();
            palette = options.palette();
        }

        BufferedImage img = RawImageDecoder.decodeRaw(data, palette, dims[0], dims[1]);

        // Scale up small textures so they're easy to see
        if (dims[0] <= 64) img = scaleNearest(img, 4);

        buildImagePanel(img);
    }

    private RawPreviewOptions promptForRawDimensions(String entryName, int byteCount,
            PodArchive archive, byte[] nameField) {
        List<int[]> suggestions = RawImageDecoder.suggestDimensions(byteCount);
        int[] preferredDims = preferredDimensions(byteCount, suggestions);
        PaletteChoices palettes = resolvePaletteChoices(entryName, archive, nameField);

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel("<html>Select dimensions for <b>" + entryName + "</b><br>"
                + "RAW payload size: " + byteCount + " bytes</html>"), BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.anchor = GridBagConstraints.WEST;

        JComboBox<DimensionChoice> choices = new JComboBox<>();
        for (int[] suggestion : suggestions) {
            choices.addItem(new DimensionChoice(suggestion[0], suggestion[1]));
            if (choices.getItemCount() == 12) {
                break;
            }
        }

        JSpinner widthSpinner = new JSpinner(new SpinnerNumberModel(
                preferredDims[0], 1, Math.max(1, byteCount), 1));
        JSpinner heightSpinner = new JSpinner(new SpinnerNumberModel(
                preferredDims[1], 1, Math.max(1, byteCount), 1));
        JButton swapButton = new JButton("Swap");
        JComboBox<PaletteChoice> paletteCombo = new JComboBox<>(palettes.choices().toArray(PaletteChoice[]::new));
        paletteCombo.setSelectedIndex(Math.max(0, palettes.defaultIndex()));

        choices.addActionListener(event -> {
            DimensionChoice selected = (DimensionChoice) choices.getSelectedItem();
            if (selected != null) {
                widthSpinner.setValue(selected.width());
                heightSpinner.setValue(selected.height());
            }
        });
        swapButton.addActionListener(event -> {
            Object width = widthSpinner.getValue();
            widthSpinner.setValue(heightSpinner.getValue());
            heightSpinner.setValue(width);
        });

        gbc.gridx = 0;
        gbc.gridy = 0;
        form.add(new JLabel("Suggested sizes:"), gbc);
        gbc.gridx = 1;
        form.add(choices, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        form.add(new JLabel("Width:"), gbc);
        gbc.gridx = 1;
        form.add(widthSpinner, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        form.add(new JLabel("Height:"), gbc);
        gbc.gridx = 1;
        form.add(heightSpinner, gbc);

        gbc.gridx = 1;
        gbc.gridy = 3;
        form.add(swapButton, gbc);

        gbc.gridx = 0;
        gbc.gridy = 4;
        form.add(new JLabel("Palette:"), gbc);
        gbc.gridx = 1;
        form.add(paletteCombo, gbc);

        panel.add(form, BorderLayout.CENTER);

        while (true) {
            int result = JOptionPane.showConfirmDialog(
                    this,
                    panel,
                    "RAW Dimensions",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                return null;
            }
            int width = ((Number) widthSpinner.getValue()).intValue();
            int height = ((Number) heightSpinner.getValue()).intValue();
            long required = (long) width * height;
            if (width <= 0 || height <= 0) {
                JOptionPane.showMessageDialog(
                        this,
                        "Width and height must be positive.",
                        "RAW Dimensions",
                        JOptionPane.WARNING_MESSAGE);
                continue;
            }
            if (required != byteCount) {
                JOptionPane.showMessageDialog(
                        this,
                        "Width × height must exactly match the RAW size.\n"
                                + width + " × " + height + " = " + required
                                + " bytes, expected " + byteCount + ".",
                        "RAW Dimensions",
                        JOptionPane.WARNING_MESSAGE);
                continue;
            }
            PaletteChoice paletteChoice = (PaletteChoice) paletteCombo.getSelectedItem();
            return new RawPreviewOptions(
                    new int[]{width, height},
                    paletteChoice != null ? paletteChoice.palette() : RawImageDecoder.loadResourcePalette());
        }
    }

    private PaletteChoices resolvePaletteChoices(String entryName, PodArchive archive,
            byte[] nameField) {
        List<PaletteChoice> choices = new ArrayList<>();
        int defaultIndex = -1;
        if (archive != null) {
            Optional<PodArchive.Entry> stored = findStoredPalette(archive, nameField);
            if (stored.isPresent()) {
                choices.add(new PaletteChoice(
                        "Stored in the archive: " + stored.get().name(),
                        tryDecodeAct(archive.getEntryBytes(stored.get()))));
                defaultIndex = 0;
            }

            int dot = entryName.lastIndexOf('.');
            if (dot > 0) {
                String sameNameAct = entryName.substring(0, dot) + ".act";
                Optional<PodArchive.Entry> sameNameEntry = archive.findEntry(sameNameAct);
                if (sameNameEntry.isPresent()) {
                    choices.add(new PaletteChoice(
                            "Same-name ACT: " + sameNameEntry.get().name(),
                            tryDecodeAct(archive.getEntryBytes(sameNameEntry.get()))));
                    if (defaultIndex < 0) {
                        defaultIndex = choices.size() - 1;
                    }
                }
            }

            Optional<PodArchive.Entry> vga = findArchivePalette(archive, "VGA.ACT");
            if (vga.isPresent()) {
                choices.add(new PaletteChoice("VGA.ACT", tryDecodeAct(archive.getEntryBytes(vga.get()))));
                if (defaultIndex < 0) {
                    defaultIndex = choices.size() - 1;
                }
            }

            for (PodArchive.Entry entry : archive.getEntries()) {
                String upperName = entry.name().toUpperCase(Locale.ROOT);
                if (!upperName.endsWith(".ACT")) {
                    continue;
                }
                if (upperName.endsWith("METALCR2.ACT") || upperName.endsWith("VGA.ACT")) {
                    continue;
                }
                choices.add(new PaletteChoice("Archive ACT: " + entry.name(), tryDecodeAct(archive.getEntryBytes(entry))));
            }

            Optional<PodArchive.Entry> metalcr = findArchivePalette(archive, "METALCR2.ACT");
            if (metalcr.isPresent()) {
                choices.add(new PaletteChoice("METALCR2.ACT", tryDecodeAct(archive.getEntryBytes(metalcr.get()))));
            }
        }
        int greyscaleIndex = choices.size();
        choices.add(new PaletteChoice("Greyscale", RawImageDecoder.greyscalePalette()));
        if (defaultIndex < 0) {
            defaultIndex = greyscaleIndex;
        }
        if (choices.stream().noneMatch(choice -> choice.label().equals("METALCR2.ACT"))) {
            choices.add(new PaletteChoice("Bundled METALCR2.ACT", RawImageDecoder.loadResourcePalette()));
        }
        return new PaletteChoices(List.copyOf(choices), defaultIndex);
    }

    private int[] preferredDimensions(int byteCount, List<int[]> suggestions) {
        if (byteCount == 256000) {
            return new int[]{640, 400};
        }
        if (byteCount == 64000) {
            return new int[]{320, 200};
        }
        if (byteCount == 307200) {
            return new int[]{640, 480};
        }
        if (!suggestions.isEmpty()) {
            return suggestions.get(0);
        }
        return new int[]{byteCount, 1};
    }

    /**
     * Resolves the palette for a .raw / .clr file, in priority order:
     *
     * 1. Same base-name .act in the archive  (demo1.raw  → demo1.act,
     *                                          ART\W01.RAW → ART\W01.ACT)
     * 2. Any other .act in the same archive directory
     * 3. VGA.ACT anywhere in the archive
     * 4. METALCR2.ACT anywhere in the archive (MTM1 default)
     * 5. metalcr2.act bundled as a classpath resource  (src/main/resources/palettes/)
     * 6. Greyscale fallback
     */
    private static int[] resolvePalette(String entryName, PodArchive archive, byte[] nameField) {

        if (archive != null) {
            // 0. The palette the packer recorded for this very entry. Where it
            //    exists it beats every guess below: on MTM1, TV, Fury3 and
            //    Hellbender the same-directory rule picks whichever ACT happens
            //    to come first in the archive, which is almost never the right one.
            Optional<PodArchive.Entry> stored = findStoredPalette(archive, nameField);
            if (stored.isPresent()) return tryDecodeAct(archive.getEntryBytes(stored.get()));

            // 1. Same base-name .act  (strip extension, append .act)
            int dot = entryName.lastIndexOf('.');
            if (dot > 0) {
                String sameNameAct = entryName.substring(0, dot) + ".act";
                Optional<PodArchive.Entry> exact = archive.findEntry(sameNameAct);
                if (exact.isPresent()) return tryDecodeAct(archive.getEntryBytes(exact.get()));
            }

            // 2. Any .act in the same directory
            int slash = Math.max(entryName.lastIndexOf('\\'), entryName.lastIndexOf('/'));
            String dirPrefix = slash >= 0
                    ? entryName.substring(0, slash + 1).toUpperCase(Locale.ROOT)
                    : "";
            for (PodArchive.Entry e : archive.getEntries()) {
                String eName = e.name().toUpperCase(Locale.ROOT);
                if (eName.startsWith(dirPrefix) && eName.endsWith(".ACT")) {
                    return tryDecodeAct(archive.getEntryBytes(e));
                }
            }

            // 3. VGA.ACT anywhere in the archive
            Optional<PodArchive.Entry> vga = findArchivePalette(archive, "VGA.ACT");
            if (vga.isPresent()) return tryDecodeAct(archive.getEntryBytes(vga.get()));

            // 4. METALCR2.ACT anywhere in the archive
            Optional<PodArchive.Entry> metalcr = findArchivePalette(archive, "METALCR2.ACT");
            if (metalcr.isPresent()) return tryDecodeAct(archive.getEntryBytes(metalcr.get()));
        }

        // 5. Classpath resource (src/main/resources/palettes/metalcr2.act)
        return RawImageDecoder.loadResourcePalette();
    }

    /**
     * Returns the archive entry for the palette named in a directory field, or
     * empty when the field names none or the archive does not hold it.
     */
    private static Optional<PodArchive.Entry> findStoredPalette(PodArchive archive, byte[] nameField) {
        String palette = PodArchive.Entry.secondString(nameField);
        if (palette == null || !palette.toUpperCase(Locale.ROOT).endsWith(".ACT")) {
            return Optional.empty();
        }
        return findArchivePalette(archive, palette);
    }

    private static Optional<PodArchive.Entry> findArchivePalette(PodArchive archive, String fileName) {
        Optional<PodArchive.Entry> entry = archive.findEntry(fileName);
        if (entry.isPresent()) {
            return entry;
        }
        return archive.findEntryByTitle(fileName);
    }

    private static int[] tryDecodeAct(byte[] bytes) {
        try { return RawImageDecoder.decodeAct(bytes); }
        catch (Exception e) { return RawImageDecoder.greyscalePalette(); }
    }

    // -------------------------------------------------------------------------
    // ACT palette
    // -------------------------------------------------------------------------

    private void buildActPalettePanel(String entryName, byte[] data) {
        int[] palette;
        try {
            palette = RawImageDecoder.decodeAct(data);
        } catch (IllegalArgumentException ex) {
            buildUnsupported("Invalid ACT file: " + ex.getMessage());
            return;
        }

        // Draw 16 × 16 grid of colour swatches (each 20 × 20 px)
        final int SWATCH = 20;
        final int COLS = 16;
        final int ROWS = 16;
        final int PANEL_W = COLS * SWATCH;
        final int PANEL_H = ROWS * SWATCH;
        final int[] pal = palette;

        JPanel swatchPanel = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                for (int i = 0; i < 256; i++) {
                    int col = i % COLS;
                    int row = i / COLS;
                    g.setColor(new Color(pal[i], true));
                    g.fillRect(col * SWATCH, row * SWATCH, SWATCH, SWATCH);
                    g.setColor(Color.DARK_GRAY);
                    g.drawRect(col * SWATCH, row * SWATCH, SWATCH - 1, SWATCH - 1);
                }
            }
            @Override public Dimension getPreferredSize() { return new Dimension(PANEL_W, PANEL_H); }
        };
        swatchPanel.setToolTipText("256-colour VGA palette from " + entryName);

        // Show hex value in a tooltip on hover
        swatchPanel.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                int col = e.getX() / SWATCH;
                int row = e.getY() / SWATCH;
                int idx = row * COLS + col;
                if (idx >= 0 && idx < 256) {
                    swatchPanel.setToolTipText(String.format(
                            "Index %d → #%06X", idx, pal[idx] & 0xFFFFFF));
                }
            }
        });

        JLabel header = new JLabel("256-colour VGA palette: " + entryName, SwingConstants.CENTER);
        header.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        setLayout(new BorderLayout(0, 0));
        add(header, BorderLayout.NORTH);
        add(new JScrollPane(swatchPanel), BorderLayout.CENTER);
        setSize(PANEL_W + 40, PANEL_H + 60);
    }

    // -------------------------------------------------------------------------
    // Text
    // -------------------------------------------------------------------------

    private void buildTextPanel(byte[] data) {
        String text = new String(data, StandardCharsets.ISO_8859_1);
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setCaretPosition(0);
        setLayout(new BorderLayout());
        add(new JScrollPane(area));
        setSize(680, 520);
    }

    // -------------------------------------------------------------------------
    // ImageIO image
    // -------------------------------------------------------------------------

    private void buildImagePanel(BufferedImage img) {
        JLabel label = new JLabel(new ImageIcon(img));
        label.setHorizontalAlignment(SwingConstants.CENTER);
        setLayout(new BorderLayout());
        add(new JScrollPane(label));
        setSize(Math.min(img.getWidth() + 40, MAX_PREVIEW_W),
                Math.min(img.getHeight() + 60, MAX_PREVIEW_H));
    }

    // -------------------------------------------------------------------------
    // Audio
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Hex dump fallback
    // -------------------------------------------------------------------------

    private void buildHexPanel(byte[] data) {
        int limit = Math.min(data.length, 4096);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "Binary data - first %d of %d bytes%n%n", limit, data.length));
        for (int i = 0; i < limit; i += 16) {
            sb.append(String.format("%06X  ", i));
            for (int j = i; j < Math.min(i + 16, limit); j++) {
                sb.append(String.format("%02X ", data[j] & 0xFF));
                if (j == i + 7) sb.append(' ');
            }
            // Pad short last line
            int lineLen = Math.min(16, limit - i);
            int pad = (16 - lineLen) * 3 + (lineLen <= 8 ? 1 : 0);
            sb.append(" ".repeat(pad));
            sb.append(" |");
            for (int j = i; j < Math.min(i + 16, limit); j++) {
                char c = (char) (data[j] & 0xFF);
                sb.append(Character.isISOControl(c) ? '.' : c);
            }
            sb.append("|\n");
        }
        JTextArea area = new JTextArea(sb.toString());
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        area.setCaretPosition(0);
        setLayout(new BorderLayout());
        add(new JScrollPane(area));
        setSize(700, 500);
    }

    // -------------------------------------------------------------------------
    // Unsupported
    // -------------------------------------------------------------------------

    private void buildUnsupported(String message) {
        JLabel lbl = new JLabel("<html><i>" + message + "</i></html>", SwingConstants.CENTER);
        setLayout(new BorderLayout());
        add(lbl);
        setSize(400, 120);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Nearest-neighbour scale-up for small textures. */
    private static BufferedImage scaleNearest(BufferedImage src, int factor) {
        int w = src.getWidth() * factor;
        int h = src.getHeight() * factor;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private record DimensionChoice(int width, int height) {
        @Override
        public String toString() {
            return width + " x " + height;
        }
    }

    private record PaletteChoice(String label, int[] palette) {
        @Override
        public String toString() {
            return label;
        }
    }

    private record RawPreviewOptions(int[] dimensions, int[] palette) {
    }

    private record PaletteChoices(List<PaletteChoice> choices, int defaultIndex) {
    }
}
