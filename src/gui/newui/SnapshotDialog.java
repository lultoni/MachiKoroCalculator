package gui.newui;

import logic.probability.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.ArrayList;

/**
 * Modal dialog that lets the user edit the current game state as a snapshot,
 * then resume turn-by-turn tracking from that point.
 *
 * <p>Opened via the "Enter Snapshot…" button in {@link MainWindow}.
 * On "Apply", it calls {@link GameSession#fromSnapshot} with the edited state and
 * replaces the session's current state (the session is re-rooted at the new snapshot
 * with an empty history from that point).
 */
public class SnapshotDialog extends JDialog {

    private static final Font LABEL_FONT = new Font("Arial", Font.PLAIN, 13);

    private final GameSession session;
    private final MainWindow parent;

    // One row of controls per player
    private final int numPlayers;
    private final JSpinner[] coinSpinners;
    private final JCheckBox[][] projectChecks;  // [player][projectIndex]
    private final ArrayList<Project> allProjects;

    public SnapshotDialog(MainWindow parent, GameSession session) {
        super(parent, "Edit Snapshot", true);
        this.parent = parent;
        this.session = session;
        this.numPlayers = session.getState().getPlayers().length;
        this.coinSpinners = new JSpinner[numPlayers];
        this.projectChecks = new JCheckBox[numPlayers][];
        this.allProjects = ProjectLoader.getAllProjects();

        buildUI();
        loadCurrentState();
        pack();
        setLocationRelativeTo(parent);
    }

    private void buildUI() {
        setLayout(new BorderLayout(8, 8));
        getRootPane().setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

        JTabbedPane tabs = new JTabbedPane();
        Player[] players = session.getState().getPlayers();

        for (int i = 0; i < numPlayers; i++) {
            JPanel tab = buildPlayerTab(i, players[i].getName());
            tabs.addTab(players[i].getName(), tab);
        }

        add(tabs, BorderLayout.CENTER);

        // Buttons
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton applyBtn = new JButton("Apply Snapshot");
        applyBtn.addActionListener(this::onApply);
        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> dispose());
        btnRow.add(cancelBtn);
        btnRow.add(applyBtn);
        add(btnRow, BorderLayout.SOUTH);

        JLabel hint = new JLabel("<html><small>Changes take effect from this point. Turn history resets to empty.</small></html>");
        hint.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        add(hint, BorderLayout.NORTH);
    }

    private JPanel buildPlayerTab(int playerIndex, String playerName) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Coin spinner
        JPanel coinRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        coinRow.add(label("Coins:"));
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(3, 0, 200, 1));
        spinner.setPreferredSize(new Dimension(70, 28));
        coinSpinners[playerIndex] = spinner;
        coinRow.add(spinner);
        panel.add(coinRow);

        panel.add(Box.createVerticalStrut(6));
        panel.add(label("Owned cards (tick = owned):"));
        panel.add(Box.createVerticalStrut(4));

        // Sort projects by color for readability
        String[] colorOrder = {"blau", "grün", "rot", "lila", "gelb"};
        projectChecks[playerIndex] = new JCheckBox[allProjects.size()];

        for (String color : colorOrder) {
            JPanel colorSection = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
            colorSection.setBorder(BorderFactory.createTitledBorder(colorLabel(color)));
            for (int j = 0; j < allProjects.size(); j++) {
                Project p = allProjects.get(j);
                if (!p.getColor().equals(color)) continue;
                JCheckBox cb = new JCheckBox(capitalize(p.getId()) + " (" + p.getCost() + ")");
                cb.setFont(new Font("Arial", Font.PLAIN, 11));
                projectChecks[playerIndex][j] = cb;
                colorSection.add(cb);
            }
            panel.add(colorSection);
        }

        JScrollPane scroll = new JScrollPane(panel);
        scroll.setPreferredSize(new Dimension(520, 360));
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(scroll);
        return wrapper;
    }

    private void loadCurrentState() {
        Player[] players = session.getState().getPlayers();
        for (int i = 0; i < numPlayers; i++) {
            coinSpinners[i].setValue(players[i].getCoins());
            for (int j = 0; j < allProjects.size(); j++) {
                if (projectChecks[i][j] != null) {
                    projectChecks[i][j].setSelected(
                            players[i].hasProject(allProjects.get(j).getId()));
                }
            }
        }
    }

    private void onApply(ActionEvent e) {
        String[] names = session.getPlayerNames();
        GameStateBuilder builder = new GameStateBuilder(numPlayers);
        for (int i = 0; i < numPlayers; i++) {
            builder.setPlayerName(i, names[i]);
            builder.setCoins(i, (int) coinSpinners[i].getValue());
            for (int j = 0; j < allProjects.size(); j++) {
                if (projectChecks[i][j] != null && projectChecks[i][j].isSelected()) {
                    builder.addProject(i, allProjects.get(j).getId());
                }
            }
        }

        // Replace session state
        GameSession newSession = GameSession.fromSnapshot(builder, names);
        // Copy the new session's state back into the existing session via reflection/rebuild
        // We instead re-root by rebuilding via the parent. Because GameSession fields are
        // final, we signal the parent to replace it with a new session.
        parent.replaceSession(newSession);
        dispose();
    }

    // ---- Helpers ----

    private static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setFont(LABEL_FONT);
        return l;
    }

    private static String colorLabel(String color) {
        return switch (color) {
            case "blau" -> "Blau (all turns)";
            case "rot"  -> "Rot (opponent turns)";
            case "grün" -> "Grün (own turn)";
            case "lila" -> "Lila (own turn, unique)";
            case "gelb" -> "Gelb (Großprojekte)";
            default     -> color;
        };
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * A FlowLayout that wraps its components across multiple rows when the
     * container is too narrow to fit them all on one line.
     */
    static class WrapLayout extends FlowLayout {
        WrapLayout(int align, int hgap, int vgap) { super(align, hgap, vgap); }

        @Override
        public Dimension preferredLayoutSize(Container target) {
            return layoutSize(target, true);
        }

        @Override
        public Dimension minimumLayoutSize(Container target) {
            Dimension minimum = layoutSize(target, false);
            minimum.width -= (getHgap() + 1);
            return minimum;
        }

        private Dimension layoutSize(Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                int targetWidth = target.getSize().width;
                if (targetWidth == 0) targetWidth = Integer.MAX_VALUE;
                int hgap = getHgap();
                int vgap = getVgap();
                Insets insets = target.getInsets();
                int maxWidth = targetWidth - (insets.left + insets.right + hgap * 2);
                int width = 0, height = vgap;
                int rowWidth = 0, rowHeight = 0;
                int nmembers = target.getComponentCount();
                for (int i = 0; i < nmembers; i++) {
                    Component m = target.getComponent(i);
                    if (m.isVisible()) {
                        Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
                        if (rowWidth + d.width > maxWidth) {
                            width = Math.max(width, rowWidth);
                            height += rowHeight + vgap;
                            rowWidth = 0;
                            rowHeight = 0;
                        }
                        if (rowWidth != 0) rowWidth += hgap;
                        rowWidth += d.width;
                        rowHeight = Math.max(rowHeight, d.height);
                    }
                }
                width = Math.max(width, rowWidth);
                height += rowHeight + insets.top + insets.bottom + vgap * 2;
                return new Dimension(width, height);
            }
        }
    }
}
