package gui.newui;

import logic.probability.TurnRecord;
import gui.newui.UIUtils;

import javax.swing.*;
import java.awt.*;

/**
 * A custom JPanel for a single turn history entry.
 *
 * <p>Displays: player name (colored), die face(s) for the roll value, optional DOUBLES badge,
 * per-player coin deltas (green/red), and purchase info. Replaces the previous HTML JLabel
 * approach to allow embedded {@link DiceFacePanel} components.
 */
public class TurnEntryPanel extends JPanel {

    private static final int DIE_SIZE = 20;

    /**
     * @param t         the turn record to display
     * @param names     player display names indexed by player index
     * @param nPlayers  number of players (for bounding coinDeltas)
     * @param pColor    color for the active player's name label
     * @param even      alternating row background flag
     */
    public TurnEntryPanel(TurnRecord t, String[] names, int nPlayers, Color pColor, boolean even) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(even ? Color.WHITE : new Color(0xF5F5F5));
        setOpaque(true);
        setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        row1.setOpaque(false);

        JLabel nameLabel = new JLabel(names[t.playerIndex]);
        nameLabel.setFont(new Font("Arial", Font.BOLD, 11));
        nameLabel.setForeground(pColor);
        row1.add(nameLabel);

        JLabel rolledLabel = new JLabel("rolled");
        rolledLabel.setFont(new Font("Arial", Font.PLAIN, 11));
        row1.add(rolledLabel);

        // Render die faces for the roll value
        addDiceFaces(row1, t.roll);

        if (t.isDoubles) {
            JLabel doublesLabel = new JLabel(" DOUBLES!");
            doublesLabel.setFont(new Font("Arial", Font.BOLD, 11));
            doublesLabel.setForeground(new Color(0x7030A0));
            row1.add(doublesLabel);
        }

        row1.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(row1);

        // ── Row 2: coin deltas per player ─────────────────────────────────────────
        if (t.coinDeltas != null) {
            JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            row2.setOpaque(false);
            for (int p = 0; p < Math.min(t.coinDeltas.length, nPlayers); p++) {
                int d = t.coinDeltas[p];
                String sign = d >= 0 ? "+" : "";
                Color col = d > 0 ? new Color(0x007700) : (d < 0 ? new Color(0xAA0000) : new Color(0x888888));
                JLabel dl = new JLabel(names[p] + ": " + sign + d + "¢");
                dl.setFont(new Font("Arial", Font.BOLD, 10));
                dl.setForeground(col);
                row2.add(dl);
            }
            row2.setAlignmentX(Component.LEFT_ALIGNMENT);
            add(row2);
        }

        // ── Row 3: purchase (if any) ──────────────────────────────────────────────
        if (t.bought != null) {
            String gpMark = t.bought.isIs_grossprojekt() ? " [GP]" : "";
            JPanel row3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
            row3.setOpaque(false);
            JLabel buyLabel = new JLabel("→ bought "
                    + UIUtils.capitalize(t.bought.getId()) + gpMark
                    + " (−" + t.bought.getCost() + "¢)");
            buyLabel.setFont(new Font("Arial", Font.PLAIN, 10));
            buyLabel.setForeground(new Color(0x444444));
            row3.add(buyLabel);
            row3.setAlignmentX(Component.LEFT_ALIGNMENT);
            add(row3);
        }
        // No "→ saved" text when nothing was bought — silence is fine.
    }

    /**
     * Caps the maximum height to the preferred height so BoxLayout does not stretch this
     * panel vertically when the history scroll pane has more space than entries fill.
     */
    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /**
     * Appends die face panels to a row for the given combined roll value.
     * For values 1–6 (one die): one face. For 7–12 (two dice): show the two possible splits
     * as the face closest to equal split (e.g. 7 → 3+4, 8 → 4+4, etc.).
     */
    private static void addDiceFaces(JPanel row, int roll) {
        if (roll <= 6) {
            row.add(new DiceFacePanel(roll, DIE_SIZE));
        } else {
            // Two-die roll: show canonical split (round up for d1, remainder for d2)
            int d1 = (roll + 1) / 2;  // e.g. 7→4, 8→4, 9→5, 10→5, 11→6, 12→6
            int d2 = roll - d1;        // e.g. 7→3, 8→4, 9→4, 10→5, 11→5, 12→6
            row.add(new DiceFacePanel(d1, DIE_SIZE));
            JLabel plus = new JLabel("+");
            plus.setFont(new Font("Arial", Font.BOLD, 10));
            row.add(plus);
            row.add(new DiceFacePanel(d2, DIE_SIZE));
        }
    }
}
