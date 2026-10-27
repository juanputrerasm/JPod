package com.mtm2.jpod.ui;

import com.mtm2.jpod.io.image.PaletteResolver;
import com.mtm2.jpod.io.model.BinFrameResolver;
import com.mtm2.jpod.io.model.BinModel;
import com.mtm2.jpod.io.model.BinModelDecoder;
import com.mtm2.jpod.io.model.BinTextureResolver;
import com.mtm2.jpod.io.model.SmfModelDecoder;
import com.mtm2.jpod.io.pod.PodArchive;
import com.mtm2.jpod.ui.model.LoadedTexture;
import com.mtm2.jpod.ui.model.ModelRenderer;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Archive-aware preview for classic, animated and Extended {@code .BIN} models and
 * 4x4 Evolution {@code .SMF} models, drawn by JPod's software renderer.
 *
 * <p>Drag to orbit, use the wheel to zoom, the left and right arrows to strafe, and
 * Reset view to refit. An animated BIN opens on frame 1 with a banner naming the frame
 * and where it resolved from; Play or the A key steps through the frames.
 */
public final class ModelPreviewWindow extends JDialog {

    private final String entryName;
    private final PodArchive archive;
    private final BinModel animation;
    private final List<BinFrameResolver.Frame> frames;
    /** Every frame decoded once, so stepping never re-reads the archive. */
    private final List<BinModel> frameModels;
    private int frameIndex;

    private final ModelRenderer renderer = new ModelRenderer();
    private final Viewport viewport = new Viewport();
    private final JCheckBox texturesToggle = toggle("Textures", true);
    private final JCheckBox wireframeToggle = toggle("Wireframe", false);
    private final JCheckBox gridToggle = toggle("Grid", true);
    private final JCheckBox smoothToggle = toggle("Smoothing", false);
    private final JCheckBox lightingToggle = toggle("Lighting", false);
    private final JComboBox<String> lightDirection = new JComboBox<>(
            new String[] {"Top", "Front left", "Front right", "Rear left", "Rear right"});
    private final JComboBox<PaletteResolver.Choice> paletteCombo = new JComboBox<>();
    private final JLabel paletteLabel = new JLabel("RAW palette:");
    private final JButton playButton = new JButton("Play (A)");
    private final JLabel statistics = new JLabel(" ");
    private final JLabel frameBanner = new JLabel(" ");
    private final JTextArea diagnostics = new JTextArea(3, 80);
    private final JPanel textureStrip = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
    /**
     * One second a frame, the rate the stock REX animation is documented to run at. It
     * is not read from the file: there is no rate field in the animated BIN header.
     */
    private final Timer frameTimer = new Timer(1000, event -> showFrame(frameIndex + 1));
    private PaletteResolver.Choices paletteChoices = new PaletteResolver.Choices(List.of(), 0);
    private List<List<LoadedTexture>> loaded = List.of();
    private Color background = new Color(0x30, 0x30, 0x34);
    private Point dragPoint;

    public ModelPreviewWindow(Window owner, String entryName, byte[] data, PodArchive archive) {
        super(owner, "Preview - " + entryName, ModalityType.APPLICATION_MODAL);
        this.entryName = entryName;
        this.archive = archive;
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        // .SMF is detected by its magic rather than its extension, so a mis-named entry
        // still opens and a .BIN is never handed to the text parser.
        BinModel decoded;
        try {
            decoded = SmfModelDecoder.isSmfModel(data)
                    ? SmfModelDecoder.decode(data, entryName)
                    : BinModelDecoder.decode(data, entryName);
        } catch (IllegalArgumentException ex) {
            decoded = new BinModel(entryName, "SMF", 65536, 0, null, 0, 0, List.of(), List.of(),
                    List.of(ex.getMessage()), List.of());
        }
        animation = decoded;
        frames = BinFrameResolver.resolve(archive, animation);
        // An animated BIN has no geometry of its own, so it opens on frame 1.
        List<BinModel> models = new ArrayList<>();
        if (frames.isEmpty()) {
            models.add(animation);
        } else {
            for (int i = 0; i < frames.size(); i++) {
                BinModel frame = loadFrame(i);
                models.add(frame != null ? frame : animation);
            }
        }
        frameModels = models;
        setTitle((animation.format().startsWith("SMF") ? "SMF Preview - " : "BIN Preview - ") + entryName);

        buildLayout();
        buildPaletteChoices();
        loadTexturesAndPrepare(true);
        updateStatistics();
        if (!frames.isEmpty()) {
            frameBanner.setVisible(true);
            playButton.setVisible(true);
            updateFrameBanner();
        }

        setSize(1000, 720);
        setMinimumSize(new Dimension(700, 500));
        setLocationRelativeTo(owner);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent event) {
                frameTimer.stop();
            }
        });
    }

    private void buildLayout() {
        JButton resetButton = new JButton("Reset view");
        resetButton.addActionListener(event -> { renderer.resetView(); viewport.repaint(); });
        JButton backgroundButton = new JButton("Background...");
        backgroundButton.addActionListener(event -> chooseBackground());
        playButton.setVisible(false);
        playButton.addActionListener(event -> togglePlayback());
        paletteCombo.addActionListener(event -> {
            if (paletteCombo.getSelectedIndex() >= 0) {
                loadTexturesAndPrepare(false);
                viewport.repaint();
            }
        });
        lightDirection.addActionListener(event -> viewport.repaint());
        for (JCheckBox toggle : new JCheckBox[] {texturesToggle, wireframeToggle, gridToggle,
                smoothToggle, lightingToggle}) {
            toggle.addActionListener(event -> viewport.repaint());
        }

        JPanel controls = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 4));
        controls.add(resetButton);
        controls.add(texturesToggle);
        controls.add(wireframeToggle);
        controls.add(gridToggle);
        controls.add(smoothToggle);
        controls.add(lightingToggle);
        controls.add(new JLabel("Light:"));
        controls.add(lightDirection);
        controls.add(backgroundButton);
        controls.add(paletteLabel);
        controls.add(paletteCombo);
        controls.add(playButton);

        statistics.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        frameBanner.setOpaque(true);
        frameBanner.setBackground(new Color(48, 44, 24));
        frameBanner.setForeground(new Color(0xFF, 0xD7, 0x00));
        frameBanner.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        frameBanner.setVisible(false);

        JPanel north = new JPanel(new BorderLayout());
        north.add(controls, BorderLayout.NORTH);
        north.add(statistics, BorderLayout.SOUTH);

        JPanel centre = new JPanel(new BorderLayout());
        centre.add(frameBanner, BorderLayout.NORTH);
        centre.add(viewport, BorderLayout.CENTER);

        diagnostics.setEditable(false);
        diagnostics.setLineWrap(true);
        diagnostics.setWrapStyleWord(true);
        textureStrip.setBackground(new Color(35, 35, 38));
        JScrollPane stripScroll = new JScrollPane(textureStrip,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        stripScroll.setPreferredSize(new Dimension(100, 122));
        JPanel south = new JPanel(new BorderLayout());
        south.add(new JScrollPane(diagnostics), BorderLayout.NORTH);
        south.add(stripScroll, BorderLayout.SOUTH);

        JPanel content = new JPanel(new BorderLayout());
        content.add(north, BorderLayout.NORTH);
        content.add(centre, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);

        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)) {
                    dragPoint = event.getPoint();
                    viewport.requestFocusInWindow();
                }
            }
            @Override public void mouseReleased(MouseEvent event) {
                dragPoint = null;
            }
            @Override public void mouseDragged(MouseEvent event) {
                if (dragPoint == null) return;
                renderer.orbit(event.getX() - dragPoint.x, event.getY() - dragPoint.y);
                dragPoint = event.getPoint();
                viewport.repaint();
            }
            @Override public void mouseWheelMoved(java.awt.event.MouseWheelEvent event) {
                renderer.zoom(event.getWheelRotation());
                viewport.repaint();
            }
        };
        viewport.addMouseListener(mouse);
        viewport.addMouseMotionListener(mouse);
        viewport.addMouseWheelListener(mouse);

        JRootPane root = getRootPane();
        root.registerKeyboardAction(event -> strafe(-1), KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        root.registerKeyboardAction(event -> strafe(1), KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        root.registerKeyboardAction(event -> togglePlayback(), KeyStroke.getKeyStroke(KeyEvent.VK_A, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        root.registerKeyboardAction(event -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private static JCheckBox toggle(String text, boolean value) {
        JCheckBox box = new JCheckBox(text, value);
        box.setFocusable(false);
        return box;
    }

    private void strafe(int direction) {
        renderer.strafe(direction);
        viewport.repaint();
    }

    private void updateStatistics() {
        BinModel model = currentModel();
        statistics.setText(String.format(Locale.ROOT,
                "%s · %,d vertices · %,d polygons · %,d meshes (%,d transparent) · %,d textures"
                        + " · magnification %d · base Z %.3f",
                model.format(), model.vertexCount(), model.polygonCount(), model.meshes().size(),
                model.transparentMeshCount(), model.textureNames().size(), model.magnifyPower(), model.baseZ()));
    }

    private BinModel currentModel() {
        return frameModels.get(Math.min(frameIndex, frameModels.size() - 1));
    }

    /** Decodes one animation frame, or null when it is not in this archive. */
    private BinModel loadFrame(int index) {
        if (archive == null) return null;
        PodArchive.Entry entry = frames.get(index).entry();
        if (entry == null) return null;
        try {
            byte[] bytes = archive.getEntryBytes(entry);
            return SmfModelDecoder.isSmfModel(bytes)
                    ? SmfModelDecoder.decode(bytes, entry.name())
                    : BinModelDecoder.decode(bytes, entry.name());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void showFrame(int index) {
        if (frames.isEmpty()) return;
        frameIndex = ((index % frames.size()) + frames.size()) % frames.size();
        renderer.selectFrame(frameIndex);
        updateFrameBanner();
        updateStatistics();
        viewport.repaint();
    }

    private void updateFrameBanner() {
        BinFrameResolver.Frame frame = frames.get(frameIndex);
        frameBanner.setText(String.format(Locale.ROOT, "Animated BIN · frame %d of %d · %s %s",
                frameIndex + 1, frames.size(), frame.name(),
                frame.entry() == null ? "(not in this archive)" : "from " + frame.entry().name()));
    }

    private void togglePlayback() {
        if (frames.isEmpty()) return;
        if (frameTimer.isRunning()) {
            frameTimer.stop();
            playButton.setText("Play (A)");
        } else {
            frameTimer.start();
            playButton.setText("Stop (A)");
        }
    }

    /**
     * The selector supplies the fallback for RAW textures that have no same-name ACT, so
     * it is ranked from the point of view of such a texture: another texture's palette is
     * a guess, METALCR2 is not.
     */
    private void buildPaletteChoices() {
        PodArchive.Entry firstRawWithoutPalette = null;
        if (archive != null) {
            Set<String> names = new LinkedHashSet<>();
            for (BinModel frame : frameModels) {
                for (String name : frame.textureNames()) names.add(name.toUpperCase(Locale.ROOT));
            }
            for (String name : names) {
                BinTextureResolver.Entries resolved = BinTextureResolver.resolve(archive, name);
                if (resolved.diffuse() != null
                        && resolved.diffuse().name().toUpperCase(Locale.ROOT).endsWith(".RAW")
                        && resolved.sameNamePalette() == null) {
                    firstRawWithoutPalette = resolved.diffuse();
                    break;
                }
            }
        }
        paletteChoices = PaletteResolver.resolveChoices(
                firstRawWithoutPalette != null ? firstRawWithoutPalette.name() : entryName, archive,
                firstRawWithoutPalette != null ? firstRawWithoutPalette.nameField() : null);
        DefaultComboBoxModel<PaletteResolver.Choice> model = new DefaultComboBoxModel<>();
        for (PaletteResolver.Choice choice : paletteChoices.choices()) model.addElement(choice);
        paletteCombo.setModel(model);
        paletteCombo.setSelectedIndex(Math.max(0, Math.min(paletteChoices.defaultIndex(), model.getSize() - 1)));
    }

    private void loadTexturesAndPrepare(boolean resetView) {
        int selected = Math.max(0, paletteCombo.getSelectedIndex());
        int[] palette = paletteChoices.choices().isEmpty()
                ? com.mtm2.jpod.io.RawImageDecoder.loadResourcePalette()
                : paletteChoices.choices().get(Math.min(selected, paletteChoices.choices().size() - 1)).palette();
        List<List<LoadedTexture>> replacement = new ArrayList<>();
        List<ModelRenderer.FrameGeometry> geometry = new ArrayList<>();
        for (BinModel frame : frameModels) {
            List<LoadedTexture> textures = LoadedTexture.load(frame, archive, palette);
            replacement.add(textures);
            geometry.add(new ModelRenderer.FrameGeometry(frame, textures));
        }
        renderer.setFrames(geometry, resetView);
        renderer.selectFrame(frameIndex);
        loaded = replacement;
        boolean usesFallback = replacement.stream().flatMap(List::stream).anyMatch(LoadedTexture::usedFallback);
        paletteCombo.setVisible(usesFallback);
        paletteLabel.setVisible(usesFallback);
        buildTextureStrip();
        updateDiagnostics();
    }

    /** The textures of every frame, one per name: frames of an animation share art. */
    private List<LoadedTexture> distinctTextures() {
        Set<String> seen = new LinkedHashSet<>();
        List<LoadedTexture> distinct = new ArrayList<>();
        for (List<LoadedTexture> frame : loaded) {
            for (LoadedTexture texture : frame) {
                if (seen.add(texture.name().toUpperCase(Locale.ROOT))) distinct.add(texture);
            }
        }
        return distinct;
    }

    private void buildTextureStrip() {
        textureStrip.removeAll();
        for (LoadedTexture texture : distinctTextures()) {
            JPanel item = new JPanel(new BorderLayout(0, 2));
            item.setOpaque(false);
            item.setPreferredSize(new Dimension(92, 96));
            JLabel picture = new JLabel(thumbnail(texture.diffuse()));
            picture.setHorizontalAlignment(SwingConstants.CENTER);
            String caption = texture.entry() != null ? texture.entry().name() : texture.name() + " (missing)";
            JLabel label = new JLabel(caption, SwingConstants.CENTER);
            label.setForeground(Color.WHITE);
            label.setFont(label.getFont().deriveFont(10f));
            String tip = texture.warning() != null ? texture.warning()
                    : texture.entry() != null ? texture.entry().name() : "Missing texture";
            item.setToolTipText(tip);
            picture.setToolTipText(tip);
            label.setToolTipText(tip);
            item.add(picture, BorderLayout.CENTER);
            item.add(label, BorderLayout.SOUTH);
            if (texture.entry() != null) {
                Cursor hand = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
                MouseAdapter open = new MouseAdapter() {
                    @Override public void mouseClicked(MouseEvent event) {
                        openTexture(texture);
                    }
                };
                for (JComponent component : new JComponent[] {item, picture, label}) {
                    component.setCursor(hand);
                    component.addMouseListener(open);
                }
            }
            textureStrip.add(item);
        }
        textureStrip.revalidate();
        textureStrip.repaint();
    }

    /** A 64x64 thumbnail over magenta, so missing or transparent art is obvious. */
    private static Icon thumbnail(BufferedImage image) {
        BufferedImage thumb = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = thumb.createGraphics();
        g.setColor(Color.MAGENTA);
        g.fillRect(0, 0, 64, 64);
        if (image != null) {
            double scale = Math.min(64.0 / image.getWidth(), 64.0 / image.getHeight());
            int w = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(image.getHeight() * scale));
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(image, (64 - w) / 2, (64 - h) / 2, w, h, null);
        }
        g.setColor(Color.DARK_GRAY);
        g.drawRect(0, 0, 63, 63);
        g.dispose();
        return new ImageIcon(thumb);
    }

    private void openTexture(LoadedTexture texture) {
        if (archive == null || texture.entry() == null) return;
        PreviewWindow.open(this, texture.entry().name(), archive.getEntryBytes(texture.entry()),
                archive, texture.entry().nameField());
    }

    private void updateDiagnostics() {
        Set<String> messages = new LinkedHashSet<>();
        for (BinModel frame : frameModels) messages.addAll(frame.warnings());
        for (LoadedTexture texture : distinctTextures()) {
            if (texture.warning() != null) messages.add(texture.warning());
        }
        for (BinFrameResolver.Frame frame : frames) {
            if (frame.entry() == null) {
                // The animation plays for whoever built the pod and stops dead for anyone
                // without the pod the frame came from.
                messages.add("Animation frame " + frame.name() + " resolves from outside this archive.");
            }
        }
        diagnostics.setText(messages.isEmpty() ? "No parser or texture warnings."
                : String.join("\n", messages));
        diagnostics.setCaretPosition(0);
    }

    private void chooseBackground() {
        Color chosen = JColorChooser.showDialog(this, "Background", background);
        if (chosen != null) {
            background = chosen;
            viewport.repaint();
        }
    }

    private float[] lightPosition() {
        return switch (lightDirection.getSelectedIndex()) {
            case 1 -> new float[] {-2, 2, 2};
            case 2 -> new float[] {2, 2, 2};
            case 3 -> new float[] {-2, 2, -2};
            case 4 -> new float[] {2, 2, -2};
            default -> new float[] {0, 3, 0};
        };
    }

    private ModelRenderer.Options currentOptions() {
        return new ModelRenderer.Options(texturesToggle.isSelected(), wireframeToggle.isSelected(),
                gridToggle.isSelected(), smoothToggle.isSelected(), lightingToggle.isSelected(),
                lightPosition(), background);
    }

    /** Renders into an offscreen image the size of the panel and blits it. */
    private final class Viewport extends JComponent {
        private BufferedImage frame;

        private Viewport() {
            setFocusable(true);
            setPreferredSize(new Dimension(800, 480));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            int w = Math.max(1, getWidth());
            int h = Math.max(1, getHeight());
            if (frame == null || frame.getWidth() != w || frame.getHeight() != h) {
                frame = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            }
            renderer.render(frame, currentOptions());
            graphics.drawImage(frame, 0, 0, null);
        }
    }
}
