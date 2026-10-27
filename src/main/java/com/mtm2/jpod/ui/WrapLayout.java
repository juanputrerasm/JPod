package com.mtm2.jpod.ui;

import javax.swing.*;
import java.awt.*;

/**
 * A {@link FlowLayout} that reports the height its wrapped rows actually need, so a
 * row of controls in a {@code BorderLayout.NORTH} wraps instead of clipping when the
 * window is narrow.
 */
final class WrapLayout extends FlowLayout {

    WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return layoutSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension minimum = layoutSize(target, false);
        minimum.width -= getHgap() + 1;
        return minimum;
    }

    private Dimension layoutSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            Container sizing = target;
            while (sizing.getSize().width == 0 && sizing.getParent() != null) {
                sizing = sizing.getParent();
            }
            int targetWidth = sizing.getSize().width;
            if (targetWidth == 0) targetWidth = Integer.MAX_VALUE;

            Insets insets = target.getInsets();
            int horizontal = insets.left + insets.right + getHgap() * 2;
            int maxWidth = targetWidth - horizontal;

            Dimension size = new Dimension(0, 0);
            int rowWidth = 0;
            int rowHeight = 0;
            for (Component component : target.getComponents()) {
                if (!component.isVisible()) continue;
                Dimension d = preferred ? component.getPreferredSize() : component.getMinimumSize();
                if (rowWidth + d.width > maxWidth) {
                    addRow(size, rowWidth, rowHeight);
                    rowWidth = 0;
                    rowHeight = 0;
                }
                if (rowWidth != 0) rowWidth += getHgap();
                rowWidth += d.width;
                rowHeight = Math.max(rowHeight, d.height);
            }
            addRow(size, rowWidth, rowHeight);
            size.width += horizontal;
            size.height += insets.top + insets.bottom + getVgap() * 2;

            Container scroll = SwingUtilities.getAncestorOfClass(JScrollPane.class, target);
            if (scroll != null && target.isValid()) {
                size.width -= getHgap() + 1;
            }
            return size;
        }
    }

    private void addRow(Dimension size, int rowWidth, int rowHeight) {
        size.width = Math.max(size.width, rowWidth);
        if (size.height > 0) size.height += getVgap();
        size.height += rowHeight;
    }
}
