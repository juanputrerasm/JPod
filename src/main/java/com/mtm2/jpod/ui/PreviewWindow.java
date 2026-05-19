package com.mtm2.jpod.ui;

import com.mtm2.jpod.io.RawImageDecoder;
import com.mtm2.jpod.io.pod.PodArchive;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Floating preview window that dispatches display mode based on the entry's file extension.
 *
 * <table>
 *   <tr><th>Extension(s)</th><th>Display mode</th></tr>
 *   <tr><td>{@code .raw}, {@code .clr}</td>
 *       <td>8-bit paletted image (64×64 art texture or 256×256 heightmap / CLR).
 *           Palette resolved via {@link #resolvePalette}: same-name {@code .act} →
 *           directory sibling → {@code METALCR2.ACT} in archive →
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
public final class PreviewWindow extends JFrame {

    private static final int MAX_PREVIEW_W = 1024;
    private static final int MAX_PREVIEW_H = 768;

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
        super("Preview — " + entryName);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        String upper = entryName.toUpperCase(Locale.ROOT);

        if (upper.endsWith(".WAV")) {
            buildAudioPanel(owner, entryName, data);
        } else if (RawImageDecoder.isRawImage(upper)) {
            buildRawImagePanel(entryName, data, archive);
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

    // -------------------------------------------------------------------------
    // RAW / CLR image
    // -------------------------------------------------------------------------

    private void buildRawImagePanel(String entryName, byte[] data, PodArchive archive) {
        int[] dims = RawImageDecoder.detectDimensions(data.length);
        if (dims == null) {
            buildUnsupported("Cannot determine dimensions for RAW file (size=" + data.length + ").");
            return;
        }

        int[] palette = resolvePalette(entryName, archive);
        BufferedImage img = RawImageDecoder.decodeRaw(data, palette, dims[0], dims[1]);

        // Scale up small textures so they're easy to see
        if (dims[0] <= 64) img = scaleNearest(img, 4);

        buildImagePanel(img);
    }

    /**
     * Resolves the palette for a .raw / .clr file, in priority order:
     *
     * 1. Same base-name .act in the archive  (demo1.raw  → demo1.act,
     *                                          ART\W01.RAW → ART\W01.ACT)
     * 2. Any other .act in the same archive directory
     * 3. METALCR2.ACT anywhere in the archive (MTM1 default)
     * 4. metalcr2.act bundled as a classpath resource  (src/main/resources/palettes/)
     * 5. Greyscale fallback
     */
    private static int[] resolvePalette(String entryName, PodArchive archive) {

        if (archive != null) {
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

            // 3. METALCR2.ACT anywhere in the archive
            Optional<PodArchive.Entry> metalcr = archive.findEntry("METALCR2.ACT");
            if (metalcr.isPresent()) return tryDecodeAct(archive.getEntryBytes(metalcr.get()));
        }

        // 4. Classpath resource (src/main/resources/palettes/metalcr2.act)
        return RawImageDecoder.loadResourcePalette();
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

    private void buildAudioPanel(Frame owner, String entryName, byte[] data) {
        // Delegate to the dedicated player dialog and show a minimal placeholder here
        setLayout(new BorderLayout());
        JLabel lbl = new JLabel("Launching audio player…", SwingConstants.CENTER);
        lbl.setFont(lbl.getFont().deriveFont(Font.ITALIC));
        add(lbl);
        setSize(280, 80);

        SwingUtilities.invokeLater(() -> {
            dispose(); // close the placeholder
            new AudioPlayerDialog(owner, entryName, data).setVisible(true);
        });
    }

    // -------------------------------------------------------------------------
    // Hex dump fallback
    // -------------------------------------------------------------------------

    private void buildHexPanel(byte[] data) {
        int limit = Math.min(data.length, 4096);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "Binary data — first %d of %d bytes%n%n", limit, data.length));
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
}
