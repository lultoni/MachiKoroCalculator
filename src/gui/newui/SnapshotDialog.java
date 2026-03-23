package gui.newui;

import logic.probability.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Modal dialog that lets the user edit the current game state as a snapshot,
 * then resume turn-by-turn tracking from that point.
 *
 * <p>Opened via the "Enter Snapshot…" button in {@link MainWindow}.
 * On "Apply", it calls {@link GameSession#fromSnapshot} with the edited state and
 * replaces the session's current state (the session is re-rooted at the new snapshot
 * with an empty history from that point).
 *
 * <p><b>Card controls by color:</b>
 * <ul>
 *   <li><b>blau / grün / rot</b> — {@link JSpinner}(0–6): a player may own multiple copies.</li>
 *   <li><b>lila / gelb</b> — {@link JCheckBox}: at most one copy per player (purple unique;
 *       landmarks are owned or not).</li>
 * </ul>
 */
public class SnapshotDialog extends JDialog {

    private static final Font LABEL_FONT = new Font("Arial", Font.PLAIN, 13);

    private final GameSession session;
    private final MainWindow parent;

    private final int numPlayers;
    private final JSpinner[] coinSpinners;
    /**
     * Per-player, per-card control: either a {@link JSpinner} (for blau/grün/rot multi-copy
     * cards) or a {@link JCheckBox} (for lila/gelb unique cards). May be {@code null} if the
     * card was not added to the player's tab section for some reason.
     */
    private final Component[][] cardControls;  // [player][projectIndex]
    private final ArrayList<Project> allProjects;

    /**
     * Constructs and displays the snapshot editor as a modal dialog.
     *
     * @param parent  owning main window (used for dialog ownership and session replacement)
     * @param session the current game session whose state is pre-loaded into the form
     */
    public SnapshotDialog(MainWindow parent, GameSession session) {
        super(parent, "Edit Snapshot", true);
        this.parent = parent;
        this.session = session;
        this.numPlayers = session.getState().getPlayers().length;
        this.coinSpinners = new JSpinner[numPlayers];
        this.cardControls = new Component[numPlayers][];
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
        panel.add(label("Owned cards:"));
        panel.add(Box.createVerticalStrut(4));

        cardControls[playerIndex] = new Component[allProjects.size()];

        // Sort projects by color for readability
        String[] colorOrder = {"blau", "grün", "rot", "lila", "gelb"};

        for (String color : colorOrder) {
            JPanel colorSection = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
            colorSection.setBorder(BorderFactory.createTitledBorder(colorLabel(color)));
            boolean isMultiCopy = color.equals("blau") || color.equals("grün") || color.equals("rot");

            for (int j = 0; j < allProjects.size(); j++) {
                Project p = allProjects.get(j);
                if (!p.getColor().equals(color)) continue;

                String cardLabel = UIUtils.capitalize(p.getId()) + " (" + p.getCost() + ")";
                if (isMultiCopy) {
                    // JSpinner(0–6): player may own multiple copies
                    JPanel cell = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
                    cell.add(new JLabel(cardLabel));
                    JSpinner countSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 6, 1));
                    countSpinner.setPreferredSize(new Dimension(45, 22));
                    cell.add(countSpinner);
                    cardControls[playerIndex][j] = countSpinner;
                    colorSection.add(cell);
                } else {
                    // JCheckBox: at most 1 copy per player (lila unique; gelb owned-or-not)
                    JCheckBox cb = new JCheckBox(cardLabel);
                    cb.setFont(new Font("Arial", Font.PLAIN, 11));
                    cardControls[playerIndex][j] = cb;
                    // Purple cards are unique — when this checkbox is selected, uncheck the same
                    // card for all other players.
                    if (color.equals("lila")) {
                        final int cardIndex = j;
                        cb.addItemListener(ev -> {
                            if (cb.isSelected()) {
                                for (int otherPlayer = 0; otherPlayer < numPlayers; otherPlayer++) {
                                    if (otherPlayer == playerIndex) continue;
                                    Component ctrl = cardControls[otherPlayer] != null
                                            ? cardControls[otherPlayer][cardIndex] : null;
                                    if (ctrl instanceof JCheckBox otherCb) {
                                        otherCb.setSelected(false);
                                    }
                                }
                            }
                        });
                    }
                    colorSection.add(cb);
                }
            }
            panel.add(colorSection);
        }

        JScrollPane scroll = new JScrollPane(panel);
        scroll.setPreferredSize(new Dimension(520, 380));
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(scroll);
        return wrapper;
    }

    private void loadCurrentState() {
        Player[] players = session.getState().getPlayers();
        for (int i = 0; i < numPlayers; i++) {
            coinSpinners[i].setValue(players[i].getCoins());
            // Build a flat list of ids for frequency counting
            ArrayList<String> ownedIds = new ArrayList<>();
            for (Project p : players[i].getOwned_projects()) ownedIds.add(p.getId());

            for (int j = 0; j < allProjects.size(); j++) {
                Component ctrl = cardControls[i][j];
                if (ctrl == null) continue;
                String id = allProjects.get(j).getId();
                if (ctrl instanceof JSpinner sp) {
                    sp.setValue(Collections.frequency(ownedIds, id));
                } else if (ctrl instanceof JCheckBox cb) {
                    cb.setSelected(ownedIds.contains(id));
                }
            }
        }
    }

    private void onApply(ActionEvent e) {
        String[] names = session.getPlayerNames();
        GameStateBuilder builder = new GameStateBuilder(numPlayers);
        try {
            for (int i = 0; i < numPlayers; i++) {
                builder.setPlayerName(i, names[i]);
                builder.setCoins(i, (int) coinSpinners[i].getValue());
                for (int j = 0; j < allProjects.size(); j++) {
                    Component ctrl = cardControls[i][j];
                    if (ctrl == null) continue;
                    String id = allProjects.get(j).getId();
                    if (ctrl instanceof JSpinner sp) {
                        int count = (int) sp.getValue();
                        for (int k = 0; k < count; k++) builder.addProject(i, id);
                    } else if (ctrl instanceof JCheckBox cb) {
                        if (cb.isSelected()) builder.addProject(i, id);
                    }
                }
            }
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this,
                    "Invalid game state: " + ex.getMessage(),
                    "Snapshot Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        GameSession newSession = GameSession.fromSnapshot(builder, names);
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
            case "blau" -> "Blau — all turns (spinner = copies owned)";
            case "rot"  -> "Rot — opponent turns (spinner = copies owned)";
            case "grün" -> "Grün — own turn (spinner = copies owned)";
            case "lila" -> "Lila — own turn, unique (tick = owned)";
            case "gelb" -> "Gelb — Großprojekte (tick = built)";
            default     -> color;
        };
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
