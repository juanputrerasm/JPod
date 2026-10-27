package com.mtm2.jpod.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * The toolbar's icons, drawn as vector strokes in the label colour of the current
 * look and feel. Swing scales the graphics for the display, so they stay sharp on a
 * HiDPI screen without a bitmap per scale, on every platform.
 */
final class ToolbarIcons {

    static final Icon NEW = new Glyph(16, ToolbarIcons::paintNew);
    static final Icon OPEN = new Glyph(16, ToolbarIcons::paintOpen);
    static final Icon SAVE = new Glyph(16, ToolbarIcons::paintSave);
    static final Icon SAVE_AS = new Glyph(16, ToolbarIcons::paintSaveAs);
    static final Icon ADD = new Glyph(16, ToolbarIcons::paintAdd);
    static final Icon EXTRACT = new Glyph(16, ToolbarIcons::paintExtract);
    static final Icon REMOVE = new Glyph(16, ToolbarIcons::paintRemove);
    static final Icon EXPAND = new Glyph(16, g -> paintFolderSign(g, true));
    static final Icon COLLAPSE = new Glyph(16, g -> paintFolderSign(g, false));
    static final Icon SEARCH = new Glyph(16, ToolbarIcons::paintSearch);
    static final Icon ABOUT = new Glyph(16, ToolbarIcons::paintAbout);
    static final Icon ARROW = new Glyph(8, ToolbarIcons::paintArrow);

    private ToolbarIcons() {}

    private interface Painter {
        void paint(Graphics2D g);
    }

    /** An icon drawn in a 16-unit box by a painter. */
    private static final class Glyph implements Icon {
        private final int width;
        private final Painter painter;

        private Glyph(int width, Painter painter) {
            this.width = width;
            this.painter = painter;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.translate(x, y);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            Color colour = component != null && component.isEnabled()
                    ? UIManager.getColor("Label.foreground")
                    : UIManager.getColor("Label.disabledForeground");
            g.setColor(colour != null ? colour : Color.DARK_GRAY);
            g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            painter.paint(g);
            g.dispose();
        }

        @Override public int getIconWidth() { return width; }

        @Override public int getIconHeight() { return 16; }
    }

    private static void paintPage(Graphics2D g) {
        Path2D page = new Path2D.Float();
        page.moveTo(3.5, 1.5);
        page.lineTo(9.5, 1.5);
        page.lineTo(12.5, 4.5);
        page.lineTo(12.5, 14.5);
        page.lineTo(3.5, 14.5);
        page.closePath();
        g.draw(page);
        g.draw(new Line2D.Float(9.5f, 1.5f, 9.5f, 4.5f));
        g.draw(new Line2D.Float(9.5f, 4.5f, 12.5f, 4.5f));
    }

    private static void paintNew(Graphics2D g) {
        paintPage(g);
        g.draw(new Line2D.Float(8, 7.5f, 8, 12));
        g.draw(new Line2D.Float(5.75f, 9.75f, 10.25f, 9.75f));
    }

    private static void paintOpen(Graphics2D g) {
        Path2D folder = new Path2D.Float();
        folder.moveTo(1.5, 13.5);
        folder.lineTo(1.5, 3.5);
        folder.lineTo(5.5, 3.5);
        folder.lineTo(7, 5);
        folder.lineTo(12.5, 5);
        folder.lineTo(12.5, 7);
        g.draw(folder);
        Path2D flap = new Path2D.Float();
        flap.moveTo(1.5, 13.5);
        flap.lineTo(4, 7.5);
        flap.lineTo(14.5, 7.5);
        flap.lineTo(12, 13.5);
        flap.closePath();
        g.draw(flap);
    }

    private static void paintDisk(Graphics2D g) {
        Path2D disk = new Path2D.Float();
        disk.moveTo(2.5, 2.5);
        disk.lineTo(11.5, 2.5);
        disk.lineTo(13.5, 4.5);
        disk.lineTo(13.5, 13.5);
        disk.lineTo(2.5, 13.5);
        disk.closePath();
        g.draw(disk);
        g.draw(new Line2D.Float(5, 2.5f, 5, 6));
        g.draw(new Line2D.Float(5, 6, 10.5f, 6));
        g.draw(new Line2D.Float(10.5f, 6, 10.5f, 2.5f));
        g.draw(new RoundRectangle2D.Float(5, 9, 6, 4.5f, 1, 1));
    }

    private static void paintSave(Graphics2D g) {
        paintDisk(g);
    }

    private static void paintSaveAs(Graphics2D g) {
        Graphics2D small = (Graphics2D) g.create();
        small.scale(0.8, 0.8);
        paintDisk(small);
        small.dispose();
        // A pencil across the corner.
        g.draw(new Line2D.Float(9, 14.5f, 15, 8.5f));
        g.draw(new Line2D.Float(8.5f, 15, 9, 14.5f));
    }

    private static void paintAdd(Graphics2D g) {
        g.draw(new Ellipse2D.Float(1.5f, 1.5f, 13, 13));
        g.draw(new Line2D.Float(8, 4.5f, 8, 11.5f));
        g.draw(new Line2D.Float(4.5f, 8, 11.5f, 8));
    }

    private static void paintExtract(Graphics2D g) {
        Path2D tray = new Path2D.Float();
        tray.moveTo(1.5, 9.5);
        tray.lineTo(1.5, 14.5);
        tray.lineTo(14.5, 14.5);
        tray.lineTo(14.5, 9.5);
        g.draw(tray);
        g.draw(new Line2D.Float(8, 1.5f, 8, 10.5f));
        g.draw(new Line2D.Float(4.5f, 7, 8, 10.5f));
        g.draw(new Line2D.Float(11.5f, 7, 8, 10.5f));
    }

    private static void paintRemove(Graphics2D g) {
        g.draw(new Line2D.Float(2.5f, 4, 13.5f, 4));
        g.draw(new Line2D.Float(6, 4, 6.5f, 1.75f));
        g.draw(new Line2D.Float(6.5f, 1.75f, 9.5f, 1.75f));
        g.draw(new Line2D.Float(9.5f, 1.75f, 10, 4));
        Path2D bin = new Path2D.Float();
        bin.moveTo(3.75, 4);
        bin.lineTo(4.75, 14.5);
        bin.lineTo(11.25, 14.5);
        bin.lineTo(12.25, 4);
        g.draw(bin);
        g.draw(new Line2D.Float(6.75f, 6.5f, 7, 12));
        g.draw(new Line2D.Float(9.25f, 6.5f, 9, 12));
    }

    private static void paintFolderSign(Graphics2D g, boolean plus) {
        g.draw(new RoundRectangle2D.Float(2, 2, 12, 12, 2, 2));
        g.draw(new Line2D.Float(5, 8, 11, 8));
        if (plus) {
            g.draw(new Line2D.Float(8, 5, 8, 11));
        }
    }

    private static void paintSearch(Graphics2D g) {
        g.draw(new Ellipse2D.Float(1.5f, 1.5f, 9, 9));
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Float(9.5f, 9.5f, 14, 14));
    }

    private static void paintAbout(Graphics2D g) {
        g.draw(new Ellipse2D.Float(1.5f, 1.5f, 13, 13));
        g.draw(new Line2D.Float(8, 7, 8, 11.5f));
        g.fill(new Ellipse2D.Float(7.1f, 4, 1.8f, 1.8f));
    }

    private static void paintArrow(Graphics2D g) {
        Path2D arrow = new Path2D.Float();
        arrow.moveTo(1, 6.5);
        arrow.lineTo(7, 6.5);
        arrow.lineTo(4, 10);
        arrow.closePath();
        g.fill(arrow);
    }
}
