package com.mtm2.jpod.ui;

import com.mtm2.jpod.io.RawImageDecoder;
import com.mtm2.jpod.io.image.PaletteResolver;
import com.mtm2.jpod.io.image.TgaImageDecoder;
import com.mtm2.jpod.io.image.TiffImageDecoder;
import com.mtm2.jpod.io.model.SmfModelDecoder;
import com.mtm2.jpod.io.pod.PodArchive;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Floating preview window whose display mode comes from the entry's extension.
 *
 * <table>
 *   <caption>Preview modes</caption>
 *   <tr><th>Extension(s)</th><th>Display mode</th></tr>
 *   <tr><td>{@code .raw}, {@code .clr}</td>
 *       <td>8-bit paletted image with a live palette selector and Save as BMP. The
 *           default palette follows {@link PaletteResolver}.</td></tr>
 *   <tr><td>{@code .act}</td><td>256-colour palette swatch grid (16x16).</td></tr>
 *   <tr><td>{@code .wav}, {@code .mod}</td><td>Handed to {@link AudioPlayerDialog}.</td></tr>
 *   <tr><td>{@code .bin}, {@code .smf}</td><td>Handed to {@link ModelPreviewWindow}.</td></tr>
 *   <tr><td>{@code .png}, {@code .tga}, {@code .tif}</td>
 *       <td>Alpha-preserving image over a transparency checkerboard.</td></tr>
 *   <tr><td>{@code .bmp}, {@code .jpg}, ...</td><td>Decoded by ImageIO.</td></tr>
 *   <tr><td>{@code .txt}, {@code .def}, {@code .lvl}, {@code .sit}, ...</td>
 *       <td>Scrollable plain-text viewer (ISO-8859-1).</td></tr>
 *   <tr><td>anything else</td><td>Hex dump of the first 4 096 bytes.</td></tr>
 * </table>
 */
public final class PreviewWindow extends JDialog {

    private static final int MAX_PREVIEW_W = 1024;
    private static final int MAX_PREVIEW_H = 768;
    private static final int HEX_DUMP_LIMIT = 4096;

    /**
     * The palette last chosen in any RAW preview. Picked again when another RAW offers
     * the same option, so working through an archive needs the choice made once.
     */
    private static String savedRawPaletteLabel;

    private boolean previewCancelled;

    private PreviewWindow(Window owner, String entryName) {
        super(owner, "Preview - " + entryName, ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        getRootPane().registerKeyboardAction(event -> dispose(),
                KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    /**
     * Opens the right window for an entry and blocks until it is closed. Audio goes
     * to the player, models to the model viewer, and a cancelled RAW dimension prompt
     * opens nothing at all.
     *
     * @param nameField the entry's POD directory field, when the caller has it; it is
     *                  what lets the stored palette name be used
     */
    public static void open(Window owner, String entryName, byte[] data, PodArchive archive,
            byte[] nameField) {
        String upper = entryName.toUpperCase(Locale.ROOT);
        if (upper.endsWith(".WAV") || upper.endsWith(".MOD")) {
            new AudioPlayerDialog(owner, entryName, data).setVisible(true);
            return;
        }
        // The magic is checked too, so an entry whose extension is missing or wrong
        // still opens as the model it is rather than falling through to a hex dump.
        if (upper.endsWith(".BIN") || upper.endsWith(".SMF") || SmfModelDecoder.isSmfModel(data)) {
            new ModelPreviewWindow(owner, entryName, data, archive).setVisible(true);
            return;
        }

        PreviewWindow window = new PreviewWindow(owner, entryName);
        window.build(entryName, data, archive, nameField);
        if (window.previewCancelled) {
            window.dispose();
            return;
        }
        window.setLocationRelativeTo(owner);
        window.setVisible(true);
    }

    private void build(String entryName, byte[] data, PodArchive archive, byte[] nameField) {
        String upper = entryName.toUpperCase(Locale.ROOT);
        if (RawImageDecoder.isRawImage(upper)) {
            buildRawImagePanel(entryName, data, archive, nameField);
        } else if (RawImageDecoder.isActPalette(upper)) {
            buildActPalettePanel(entryName, data);
        } else if (upper.endsWith(".TGA")) {
            try {
                buildImagePanel(TgaImageDecoder.decode(data, entryName), true);
            } catch (IllegalArgumentException ex) {
                buildMessage(ex.getMessage());
            }
        } else if (upper.endsWith(".TIF") || upper.endsWith(".TIFF")) {
            buildTiff(entryName, data);
        } else if (upper.endsWith(".PNG")) {
            BufferedImage image = readImageQuietly(data);
            if (image != null) buildImagePanel(image, true);
            else buildMessage("Invalid PNG image.");
        } else if (RawImageDecoder.isTextFile(upper)) {
            buildTextPanel(data);
        } else {
            // Not a format of ours; ImageIO may still know it, and if not, hex it is.
            BufferedImage image = readImageQuietly(data);
            if (image != null) buildImagePanel(image, false);
            else buildHexPanel(data);
        }
    }

    /**
     * Evo 2's palette TIFFs go through JPod's own decoder, which treats the second
     * sample as opacity. Anything else it refuses is offered to ImageIO.
     */
    private void buildTiff(String entryName, byte[] data) {
        try {
            buildImagePanel(TiffImageDecoder.decode(data, entryName), true);
        } catch (IllegalArgumentException ex) {
            BufferedImage image = readImageQuietly(data);
            if (image != null) buildImagePanel(image, true);
            else buildMessage(ex.getMessage());
        }
    }

    private static BufferedImage readImageQuietly(byte[] data) {
        try {
            return ImageIO.read(new ByteArrayInputStream(data));
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // RAW / CLR image
    // -------------------------------------------------------------------------

    private void buildRawImagePanel(String entryName, byte[] data, PodArchive archive, byte[] nameField) {
        PaletteResolver.Choices palettes = PaletteResolver.resolveChoices(entryName, archive, nameField);
        int selectedIndex = palettes.defaultIndex();
        if (savedRawPaletteLabel != null) {
            int saved = palettes.indexOf(savedRawPaletteLabel);
            if (saved >= 0) selectedIndex = saved;
        }

        int[] dims = RawImageDecoder.detectDimensions(data.length);
        if (dims == null) {
            RawPreviewOptions options = promptForRawDimensions(entryName, data.length, palettes, selectedIndex);
            if (options == null) {
                previewCancelled = true;
                return;
            }
            dims = options.dimensions();
            selectedIndex = options.paletteIndex();
        }
        final int width = dims[0];
        final int height = dims[1];

        JComboBox<PaletteResolver.Choice> palettePicker =
                new JComboBox<>(palettes.choices().toArray(PaletteResolver.Choice[]::new));
        palettePicker.setSelectedIndex(Math.max(0, Math.min(selectedIndex, palettes.choices().size() - 1)));
        JButton save = new JButton("Save as BMP...");

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        controls.add(new JLabel("Palette:"));
        controls.add(palettePicker);
        controls.add(save);

        ImageCanvas canvas = new ImageCanvas(false);
        BufferedImage[] original = new BufferedImage[1];
        Runnable repaint = () -> {
            PaletteResolver.Choice choice = (PaletteResolver.Choice) palettePicker.getSelectedItem();
            if (choice == null) return;
            savedRawPaletteLabel = choice.label();
            original[0] = RawImageDecoder.decodeRaw(data, choice.palette(), width, height);
            // Art textures are tiny, so they are drawn at 4x with no smoothing.
            canvas.setImage(width <= 64 ? scaleNearest(original[0], 4) : original[0]);
        };
        palettePicker.addActionListener(event -> repaint.run());
        save.addActionListener(event -> saveAsBmp(entryName, original[0]));
        repaint.run();

        setLayout(new BorderLayout());
        add(controls, BorderLayout.NORTH);
        add(centered(canvas), BorderLayout.CENTER);
        sizeToContent(canvas.getPreferredSize(), controls.getPreferredSize());
    }

    private void saveAsBmp(String entryName, BufferedImage image) {
        if (image == null) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save as BMP");
        chooser.setFileFilter(new FileNameExtensionFilter("Bitmap image (*.bmp)", "bmp"));
        String base = entryName.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        int dot = base.lastIndexOf('.');
        chooser.setSelectedFile(new File((dot > 0 ? base.substring(0, dot) : base) + ".bmp"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File target = chooser.getSelectedFile();
        if (!target.getName().toLowerCase(Locale.ROOT).endsWith(".bmp")) {
            target = new File(target.getParentFile(), target.getName() + ".bmp");
        }
        // BMP has no alpha, so the image is flattened to 24-bit first.
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        try {
            ImageIO.write(rgb, "bmp", target);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not save the bitmap:\n" + ex.getMessage(),
                    "JPod", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Asks for width, height and palette when a RAW payload is not a recognized size.
     * Width times height must match the payload exactly, so a wrong guess is rejected
     * rather than drawn as garbage.
     */
    private RawPreviewOptions promptForRawDimensions(String entryName, int byteCount,
            PaletteResolver.Choices palettes, int selectedPalette) {
        List<int[]> suggestions = RawImageDecoder.suggestDimensions(byteCount);
        int[] preferredDims = RawImageDecoder.preferredDimensions(byteCount);

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel("<html>Select dimensions for <b>" + entryName + "</b><br>"
                + "RAW payload size: " + byteCount + " bytes</html>"), BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.anchor = GridBagConstraints.WEST;

        JComboBox<DimensionChoice> choices = new JComboBox<>();
        // Twelve is enough to cover the plausible shapes without a scroll marathon.
        for (int[] suggestion : suggestions) {
            choices.addItem(new DimensionChoice(suggestion[0], suggestion[1]));
            if (choices.getItemCount() == 12) break;
        }

        JSpinner widthSpinner = new JSpinner(new SpinnerNumberModel(
                preferredDims[0], 1, Math.max(1, byteCount), 1));
        JSpinner heightSpinner = new JSpinner(new SpinnerNumberModel(
                preferredDims[1], 1, Math.max(1, byteCount), 1));
        JButton swapButton = new JButton("Swap");
        JComboBox<PaletteResolver.Choice> paletteCombo =
                new JComboBox<>(palettes.choices().toArray(PaletteResolver.Choice[]::new));
        paletteCombo.setSelectedIndex(Math.max(0, Math.min(selectedPalette, palettes.choices().size() - 1)));

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

        addRow(form, gbc, 0, "Suggested sizes:", choices);
        addRow(form, gbc, 1, "Width:", widthSpinner);
        addRow(form, gbc, 2, "Height:", heightSpinner);
        gbc.gridx = 1;
        gbc.gridy = 3;
        form.add(swapButton, gbc);
        addRow(form, gbc, 4, "Palette:", paletteCombo);
        panel.add(form, BorderLayout.CENTER);

        while (true) {
            int result = JOptionPane.showConfirmDialog(getOwner(), panel, "RAW Dimensions",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                return null;
            }
            int width = ((Number) widthSpinner.getValue()).intValue();
            int height = ((Number) heightSpinner.getValue()).intValue();
            long required = (long) width * height;
            if (required != byteCount) {
                JOptionPane.showMessageDialog(getOwner(),
                        "Width x height must exactly match the RAW size.\n"
                                + width + " x " + height + " = " + required
                                + " bytes, expected " + byteCount + ".",
                        "RAW Dimensions", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            return new RawPreviewOptions(new int[] {width, height}, paletteCombo.getSelectedIndex());
        }
    }

    private static void addRow(JPanel form, GridBagConstraints gbc, int row, String caption, JComponent field) {
        gbc.gridx = 0;
        gbc.gridy = row;
        form.add(new JLabel(caption), gbc);
        gbc.gridx = 1;
        form.add(field, gbc);
    }

    // -------------------------------------------------------------------------
    // ACT palette
    // -------------------------------------------------------------------------

    private void buildActPalettePanel(String entryName, byte[] data) {
        int[] palette;
        try {
            palette = RawImageDecoder.decodeAct(data);
        } catch (IllegalArgumentException ex) {
            buildMessage("Invalid ACT file: " + ex.getMessage());
            return;
        }

        final int swatch = 20;
        final int columns = 16;
        final int side = columns * swatch;
        JPanel swatchPanel = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                for (int i = 0; i < 256; i++) {
                    int col = i % columns;
                    int row = i / columns;
                    g.setColor(new Color(palette[i], true));
                    g.fillRect(col * swatch, row * swatch, swatch, swatch);
                    g.setColor(Color.DARK_GRAY);
                    g.drawRect(col * swatch, row * swatch, swatch - 1, swatch - 1);
                }
            }
            @Override public Dimension getPreferredSize() { return new Dimension(side, side); }
        };
        swatchPanel.setToolTipText("256-colour palette from " + entryName);
        // Hovering a swatch is the only practical way to read an index off the grid.
        swatchPanel.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                int col = e.getX() / swatch;
                int row = e.getY() / swatch;
                int idx = row * columns + col;
                if (col < columns && idx >= 0 && idx < 256) {
                    swatchPanel.setToolTipText(String.format(Locale.ROOT,
                            "Index %d - #%06X", idx, palette[idx] & 0xFFFFFF));
                }
            }
        });

        JLabel header = new JLabel("256-colour VGA palette: " + entryName, SwingConstants.CENTER);
        header.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        setLayout(new BorderLayout());
        add(header, BorderLayout.NORTH);
        add(new JScrollPane(swatchPanel), BorderLayout.CENTER);
        setSize(side + 40, side + 70);
    }

    // -------------------------------------------------------------------------
    // Text, images, hex
    // -------------------------------------------------------------------------

    private void buildTextPanel(byte[] data) {
        JTextArea area = new JTextArea(new String(data, StandardCharsets.ISO_8859_1));
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setCaretPosition(0);
        setLayout(new BorderLayout());
        add(new JScrollPane(area));
        setSize(680, 520);
    }

    private void buildImagePanel(BufferedImage image, boolean checkerboard) {
        ImageCanvas canvas = new ImageCanvas(checkerboard);
        canvas.setImage(image);
        setLayout(new BorderLayout());
        add(centered(canvas), BorderLayout.CENTER);
        sizeToContent(canvas.getPreferredSize(), null);
    }

    private void buildHexPanel(byte[] data) {
        JTextArea area = new JTextArea(hexDump(data));
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        area.setCaretPosition(0);
        setLayout(new BorderLayout());
        add(new JScrollPane(area));
        setSize(700, 500);
    }

    private void buildMessage(String message) {
        JLabel label = new JLabel("<html><i>" + message + "</i></html>", SwingConstants.CENTER);
        setLayout(new BorderLayout());
        add(label);
        setSize(420, 140);
    }

    /**
     * Classic 16-bytes-per-line dump with an ASCII gutter, capped so a 40 MB entry
     * does not become a 40 MB string.
     */
    static String hexDump(byte[] data) {
        int limit = Math.min(data.length, HEX_DUMP_LIMIT);
        StringBuilder sb = new StringBuilder(limit * 4 + 64);
        sb.append("Binary data - first ").append(limit).append(" of ").append(data.length)
                .append(" bytes\n\n");
        for (int i = 0; i < limit; i += 16) {
            sb.append(String.format(Locale.ROOT, "%06X  ", i));
            int lineLen = Math.min(16, limit - i);
            for (int j = 0; j < lineLen; j++) {
                sb.append(String.format(Locale.ROOT, "%02X ", data[i + j] & 0xFF));
                if (j == 7) sb.append(' ');
            }
            sb.append(" ".repeat((16 - lineLen) * 3 + (lineLen <= 8 ? 1 : 0)));
            sb.append(" |");
            for (int j = 0; j < lineLen; j++) {
                char c = (char) (data[i + j] & 0xFF);
                sb.append(Character.isISOControl(c) ? '.' : c);
            }
            sb.append("|\n");
        }
        return sb.toString();
    }

    /**
     * Sizes the window to hold the image and the toolbar above it. Sizing from the
     * image alone would push the toolbar's buttons off a small texture's window.
     */
    private void sizeToContent(Dimension content, Dimension toolbar) {
        int barWidth = toolbar == null ? 0 : toolbar.width;
        int barHeight = toolbar == null ? 0 : toolbar.height;
        int width = Math.max(content.width + 40, barWidth + 24);
        int height = content.height + barHeight + 60;
        setSize(Math.min(width, MAX_PREVIEW_W), Math.min(height, MAX_PREVIEW_H));
        if (toolbar != null) {
            setMinimumSize(new Dimension(Math.min(barWidth + 24, MAX_PREVIEW_W), 200));
        }
    }

    /** Keeps an image smaller than its viewport in the middle of it. */
    private static JScrollPane centered(JComponent canvas) {
        JPanel holder = new JPanel(new GridBagLayout());
        holder.add(canvas);
        return new JScrollPane(holder);
    }

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

    /** Draws an image unscaled, optionally over a transparency checkerboard. */
    private static final class ImageCanvas extends JComponent {
        private final boolean checkerboard;
        private BufferedImage image;

        private ImageCanvas(boolean checkerboard) {
            this.checkerboard = checkerboard;
        }

        private void setImage(BufferedImage replacement) {
            image = replacement;
            setPreferredSize(new Dimension(replacement.getWidth(), replacement.getHeight()));
            revalidate();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (image == null) return;
            if (checkerboard) {
                final int tile = 12;
                for (int y = 0; y < image.getHeight(); y += tile) {
                    for (int x = 0; x < image.getWidth(); x += tile) {
                        g.setColor(((x / tile) + (y / tile)) % 2 == 0 ? Color.WHITE : Color.LIGHT_GRAY);
                        g.fillRect(x, y, Math.min(tile, image.getWidth() - x), Math.min(tile, image.getHeight() - y));
                    }
                }
            }
            g.drawImage(image, 0, 0, null);
        }
    }

    private record DimensionChoice(int width, int height) {
        @Override
        public String toString() {
            return width + " x " + height;
        }
    }

    private record RawPreviewOptions(int[] dimensions, int paletteIndex) {}
}
