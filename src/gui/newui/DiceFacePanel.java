package gui.newui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;

/**
 * A custom JPanel that paints a single die face with dot pips, like a physical die.
 *
 * <p>Use cases:
 * <ul>
 *   <li>Display-only (history, card details activation): construct with {@link #DiceFacePanel(int, int)}
 *       where the second argument is the pixel size.</li>
 *   <li>Selectable (roll input strip): {@link #setSelectable(boolean)} to enable click interaction;
 *       use {@link #isSelected()} / {@link #setSelected(boolean)} to track state.</li>
 * </ul>
 *
 * <p>Visual states:
 * <ul>
 *   <li>Display / selected: white face, dark dots, rounded border, slight drop shadow.</li>
 *   <li>Unselected (selectable mode): light-grey face, dim dots, dashed border.</li>
 *   <li>Hover (selectable mode): slightly brighter face to indicate interactivity.</li>
 * </ul>
 */
public class DiceFacePanel extends JPanel {

    // Dot layout for each face value.
    // Each entry is an array of {row, col} pairs on a 3x3 grid (0=top, 1=mid, 2=bot; 0=left, 1=ctr, 2=right).
    private static final int[][][] DOT_POSITIONS = {
        {},                                                               // 0 — unused
        {{1, 1}},                                                         // 1
        {{0, 0}, {2, 2}},                                                 // 2
        {{0, 0}, {1, 1}, {2, 2}},                                         // 3
        {{0, 0}, {0, 2}, {2, 0}, {2, 2}},                                 // 4
        {{0, 0}, {0, 2}, {1, 1}, {2, 0}, {2, 2}},                         // 5
        {{0, 0}, {0, 2}, {1, 0}, {1, 2}, {2, 0}, {2, 2}},                 // 6
    };

    private int value;        // 1–6
    private final int size;   // pixel size of the die face (width == height)
    private boolean selectable = false;
    private boolean selected   = false;
    private boolean hovered    = false;

    /** Creates a fixed display-only die face. */
    public DiceFacePanel(int value, int size) {
        this.value = value;
        this.size = size;
        setOpaque(false);
        setPreferredSize(new Dimension(size, size));
        setMinimumSize(new Dimension(size, size));
        setMaximumSize(new Dimension(size, size));
    }

    /** Creates a selectable die face for use in a roll-input strip. */
    public DiceFacePanel(int value, int size, boolean selectable) {
        this(value, size);
        setSelectable(selectable);
    }

    public int getValue() { return value; }
    public void setValue(int v) { this.value = v; repaint(); }

    public boolean isSelected() { return selected; }

    public void setSelected(boolean s) {
        this.selected = s;
        repaint();
    }

    public void setSelectable(boolean s) {
        this.selectable = s;
        setCursor(s ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
        if (s) {
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseEntered(java.awt.event.MouseEvent e) {
                    hovered = true; repaint();
                }
                @Override public void mouseExited(java.awt.event.MouseEvent e) {
                    hovered = false; repaint();
                }
            });
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int pad = 2;
        int w = size - pad * 2;
        int h = size - pad * 2;
        int arc = Math.max(4, w / 4);

        // Shadow
        g2.setColor(new Color(0, 0, 0, 30));
        g2.fillRoundRect(pad + 1, pad + 1, w, h, arc, arc);

        // Face background
        Color face;
        if (selectable && !selected) {
            face = hovered ? new Color(0xE8E8E8) : new Color(0xD8D8D8);
        } else {
            face = hovered && selectable ? new Color(0xFFFAE6) : Color.WHITE;
        }
        g2.setColor(face);
        g2.fillRoundRect(pad, pad, w, h, arc, arc);

        // Border
        if (selectable && selected) {
            g2.setColor(new Color(0x1565C0));
            g2.setStroke(new BasicStroke(2f));
        } else if (selectable) {
            g2.setColor(new Color(0xAAAAAA));
            g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    1f, new float[]{3, 3}, 0));
        } else {
            g2.setColor(new Color(0x555555));
            g2.setStroke(new BasicStroke(1.5f));
        }
        g2.drawRoundRect(pad, pad, w, h, arc, arc);

        // Dots
        if (value >= 1 && value <= 6) {
            paintDots(g2, pad, pad, w, h, selectable && !selected);
        }

        g2.dispose();
    }

    private void paintDots(Graphics2D g2, int x, int y, int w, int h, boolean dimmed) {
        int dotD = Math.max(3, w / 7);  // dot diameter
        int margin = dotD + 2;

        // 3×3 grid cell centers
        int[] xs = {x + margin, x + w / 2, x + w - margin};
        int[] ys = {y + margin, y + h / 2, y + h - margin};

        Color dotColor = dimmed ? new Color(0xBBBBBB) : new Color(0x222222);
        g2.setColor(dotColor);

        int[][] dots = DOT_POSITIONS[value];
        for (int[] d : dots) {
            int cx = xs[d[1]] - dotD / 2;
            int cy = ys[d[0]] - dotD / 2;
            g2.fill(new Ellipse2D.Float(cx, cy, dotD, dotD));
        }
    }
}
