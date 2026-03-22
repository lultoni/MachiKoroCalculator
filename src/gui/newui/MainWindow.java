package gui.newui;

import logic.probability.*;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Main turn-by-turn game window.
 *
 * <p>Layout (three columns inside a JSplitPane chain):
 * <pre>
 *  ┌───────────────┬────────────────────┬──────────────────────┐
 *  │  Turn Input   │  Top Recommendation│  Full Ranking Table  │
 *  │  (left ~220)  │  (center ~280)     │  (right, flex)       │
 *  └───────────────┴────────────────────┴──────────────────────┘
 * </pre>
 */
public class MainWindow extends JFrame {

    // ---- constants ----
    private static final Font HEADER_FONT = new Font("Arial", Font.BOLD, 13);
    private static final Font MONO_FONT   = new Font("Monospaced", Font.PLAIN, 12);
    private static final Color[] CARD_COLORS = {
            new Color(0x5B9BD5),  // blau
            new Color(0xED7D31),  // rot
            new Color(0x70AD47),  // grün
            new Color(0x7030A0),  // lila
            new Color(0xFFD700),  // gelb
    };

    // ---- state ----
    private GameSession session;
    private final RankingOptions rankOpts = new RankingOptions();
    private boolean showWinProb = false;

    // ---- left panel components ----
    private JLabel activePlayerLabel;
    private JSpinner rollSpinner;
    private JComboBox<String> buyCombo;
    private JButton confirmBtn;
    private JButton undoBtn;
    private JTextArea historyArea;
    private JLabel coinsLabel;

    // ---- center panel components ----
    private JLabel topCardName;
    private JLabel topCardCost;
    private JLabel topCardEV;
    private JLabel topCardROI;
    private JLabel topCardRisk;
    private JLabel topCardWinProb;
    private JLabel topCardNote;
    private JPanel topCardColorBar;
    private JLabel baselineWinProbLabel;

    // ---- right panel components ----
    private DefaultTableModel tableModel;
    private JTable rankTable;
    private JButton toggleWinProbBtn;
    private JToggleButton deepAnalysisBtn;
    private JLabel statusLabel;

    // ---- snapshot / live state for the current-player recommendation ----
    private ArrayList<RankEntry> lastRanking = new ArrayList<>();

    /**
     * Constructs and displays the main game window for the given session.
     *
     * @param session active game session (turn history + current state)
     */
    public MainWindow(GameSession session) {
        this.session = session;
        setTitle("Machi Koro Calculator");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 560));
        buildUI();
        refreshAll();
        pack();
        setLocationRelativeTo(null);
    }

    // =========================================================================
    // UI Construction
    // =========================================================================

    private void buildUI() {
        JPanel left   = buildLeftPanel();
        JPanel center = buildCenterPanel();
        JPanel right  = buildRightPanel();

        JSplitPane rightSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, center, right);
        rightSplit.setDividerLocation(300);
        rightSplit.setResizeWeight(0.35);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, rightSplit);
        mainSplit.setDividerLocation(230);
        mainSplit.setResizeWeight(0.0);

        setContentPane(mainSplit);
    }

    // ---- Left panel: turn input ----

    private JPanel buildLeftPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(titledBorder("Current Turn"));
        panel.setPreferredSize(new Dimension(230, 0));

        // Active player
        activePlayerLabel = new JLabel("Player 1's turn");
        activePlayerLabel.setFont(HEADER_FONT);
        activePlayerLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(wrap(activePlayerLabel));

        // Coins display
        coinsLabel = new JLabel("Coins: 3");
        coinsLabel.setFont(new Font("Arial", Font.PLAIN, 12));
        coinsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(wrap(coinsLabel));

        panel.add(Box.createVerticalStrut(10));

        // Roll input
        panel.add(wrap(bold("Dice roll (1–12):")));
        rollSpinner = new JSpinner(new SpinnerNumberModel(7, 1, 12, 1));
        rollSpinner.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        rollSpinner.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(rollSpinner);

        panel.add(Box.createVerticalStrut(10));

        // Buy dropdown
        panel.add(wrap(bold("Purchase (optional):")));
        buyCombo = new JComboBox<>();
        buyCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        buyCombo.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(buyCombo);

        panel.add(Box.createVerticalStrut(12));

        // Confirm button
        confirmBtn = new JButton("Confirm Turn");
        confirmBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        confirmBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        confirmBtn.addActionListener(this::onConfirmTurn);
        panel.add(confirmBtn);

        panel.add(Box.createVerticalStrut(6));

        // Undo button
        undoBtn = new JButton("Undo Last Turn");
        undoBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        undoBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        undoBtn.addActionListener(this::onUndo);
        panel.add(undoBtn);

        panel.add(Box.createVerticalStrut(14));

        // Snapshot button
        JButton snapshotBtn = new JButton("Enter Snapshot…");
        snapshotBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        snapshotBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        snapshotBtn.addActionListener(this::onOpenSnapshot);
        panel.add(snapshotBtn);

        panel.add(Box.createVerticalStrut(14));

        // History area
        panel.add(wrap(bold("Turn history:")));
        historyArea = new JTextArea(8, 18);
        historyArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        historyArea.setEditable(false);
        historyArea.setLineWrap(true);
        JScrollPane histScroll = new JScrollPane(historyArea);
        histScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        histScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 160));
        panel.add(histScroll);

        return panel;
    }

    // ---- Center panel: top recommendation ----

    private JPanel buildCenterPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(titledBorder("Best Purchase"));
        panel.setPreferredSize(new Dimension(300, 0));

        topCardColorBar = new JPanel();
        topCardColorBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
        topCardColorBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(topCardColorBar);

        panel.add(Box.createVerticalStrut(8));

        topCardName = new JLabel("—");
        topCardName.setFont(new Font("Arial", Font.BOLD, 20));
        topCardName.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(wrap(topCardName));

        topCardCost = new JLabel("Cost: —");
        topCardCost.setFont(new Font("Arial", Font.PLAIN, 13));
        topCardCost.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(wrap(topCardCost));

        panel.add(Box.createVerticalStrut(10));

        topCardEV = metricLabel("EV / round: —");
        topCardROI = metricLabel("ROI (10 turns): —");
        topCardRisk = metricLabel("Risk (P=0 income): —");
        topCardWinProb = metricLabel("Win Δ: —");
        topCardWinProb.setVisible(false);

        panel.add(wrap(topCardEV));
        panel.add(wrap(topCardROI));
        panel.add(wrap(topCardRisk));
        panel.add(wrap(topCardWinProb));

        panel.add(Box.createVerticalStrut(6));

        baselineWinProbLabel = new JLabel("Current win prob: —");
        baselineWinProbLabel.setFont(new Font("Arial", Font.BOLD, 12));
        baselineWinProbLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(wrap(baselineWinProbLabel));

        panel.add(Box.createVerticalStrut(12));

        topCardNote = new JLabel("<html><i>—</i></html>");
        topCardNote.setFont(new Font("Arial", Font.ITALIC, 12));
        topCardNote.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(wrap(topCardNote));

        return panel;
    }

    // ---- Right panel: full ranking table ----

    private JPanel buildRightPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(titledBorder("All Affordable Cards (sorted by ROI)"));

        String[] cols = {"Card", "Cost", "EV/rnd", "ROI", "Risk", "Var"};
        tableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        rankTable = new JTable(tableModel);
        rankTable.setFont(MONO_FONT);
        rankTable.setRowHeight(22);
        rankTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        rankTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) onTableSelect();
        });

        // Right-align numeric columns and card name color renderer are applied in rebuildTable().
        // Column widths (fixed initial values; rebuildTable also restores these)
        rankTable.getColumnModel().getColumn(0).setPreferredWidth(110);
        for (int c = 1; c <= 5; c++) rankTable.getColumnModel().getColumn(c).setPreferredWidth(55);

        panel.add(new JScrollPane(rankTable), BorderLayout.CENTER);

        // Button bar at bottom
        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        toggleWinProbBtn = new JButton("Show Win Prob Δ");
        toggleWinProbBtn.addActionListener(this::onToggleWinProb);
        btnBar.add(toggleWinProbBtn);

        deepAnalysisBtn = new JToggleButton("Deep Analysis (MC)");
        deepAnalysisBtn.setToolTipText("Run 1000 Monte Carlo simulations per card for accurate win-probability delta. ~1–2 seconds.");
        deepAnalysisBtn.addActionListener(this::onToggleDeepAnalysis);
        btnBar.add(deepAnalysisBtn);

        statusLabel = new JLabel("");
        statusLabel.setFont(new Font("Arial", Font.ITALIC, 11));
        btnBar.add(statusLabel);

        panel.add(btnBar, BorderLayout.SOUTH);

        return panel;
    }

    // =========================================================================
    // Event handlers
    // =========================================================================

    private void onConfirmTurn(ActionEvent e) {
        int pi = session.nextPlayerIndex();
        int roll = (int) rollSpinner.getValue();

        Project bought = null;
        String selected = (String) buyCombo.getSelectedItem();
        if (selected != null && !selected.equals("— nothing —")) {
            bought = ProjectLoader.getProject(projectIdFromLabel(selected))
                    .orElse(null);
        }

        try {
            session.applyTurn(new TurnRecord(pi, roll, bought));
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Invalid Turn", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (session.isFinished()) {
            String winner = session.getPlayerNames()[session.getWinnerIndex()];
            showGameOver(winner);
            return;
        }

        refreshAll();
    }

    private void onUndo(ActionEvent e) {
        if (session.getHistory().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Nothing to undo.", "Undo", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            session.undoLastTurn();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Undo failed", JOptionPane.ERROR_MESSAGE);
            return;
        }
        refreshAll();
    }

    private void onToggleDeepAnalysis(ActionEvent e) {
        boolean enabled = deepAnalysisBtn.isSelected();
        rankOpts.mcSimulations = enabled ? 1000 : 0;
        deepAnalysisBtn.setText(enabled ? "Deep Analysis ON (MC)" : "Deep Analysis (MC)");
        // Only show win-prob column when deep analysis is active
        if (enabled && !showWinProb) {
            showWinProb = true;
            rankOpts.includeWinProbDelta = true;
            toggleWinProbBtn.setText("Hide Win Prob Δ");
            topCardWinProb.setVisible(true);
        }
        refreshAll();
    }

    private void onToggleWinProb(ActionEvent e) {
        showWinProb = !showWinProb;
        rankOpts.includeWinProbDelta = showWinProb;
        toggleWinProbBtn.setText(showWinProb ? "Hide Win Prob Δ" : "Show Win Prob Δ");
        topCardWinProb.setVisible(showWinProb);

        // Add/remove Win Δ column
        if (showWinProb) {
            tableModel.addColumn("Win Δ");
        } else {
            // Remove last column — DefaultTableModel doesn't have removeColumn; rebuild
            rebuildTable();
        }
        refreshAll();
    }

    private void onTableSelect() {
        int row = rankTable.getSelectedRow();
        if (row < 0 || row >= lastRanking.size()) return;
        RankEntry entry = lastRanking.get(row);
        populateCenter(entry);
    }

    private void onOpenSnapshot(ActionEvent e) {
        new SnapshotDialog(this, session).setVisible(true);
        refreshAll();
    }

    private void showGameOver(String winnerName) {
        // Disable further input
        confirmBtn.setEnabled(false);
        undoBtn.setEnabled(true);

        // Replace center panel content with a win message
        topCardColorBar.setBackground(CARD_COLORS[4]); // gelb — landmark color
        topCardName.setText(winnerName + " wins!");
        topCardCost.setText("");
        topCardEV.setText("All 4 landmarks built.");
        topCardROI.setText("");
        topCardRisk.setText("");
        topCardWinProb.setVisible(false);
        baselineWinProbLabel.setText("Current win prob: 100%");
        topCardNote.setText("<html><i>Game over. Use Undo to continue or close the window.</i></html>");

        // Clear the ranking table — no more purchases
        tableModel.setRowCount(0);
        statusLabel.setText("Game over!");
    }

    // =========================================================================
    // Data refresh
    // =========================================================================

    private void refreshAll() {
        int pi = session.nextPlayerIndex();
        Player[] players = session.getState().getPlayers();
        Player activePlayer = players[pi];

        // Update active-player label and coins
        activePlayerLabel.setText(activePlayer.getName() + "'s turn");
        coinsLabel.setText("Coins: " + activePlayer.getCoins());

        // Rebuild buy combo: "— nothing —" + affordable unbuilt cards
        rebuildBuyCombo(pi, activePlayer);

        // History and undo must always update immediately
        refreshHistory();
        undoBtn.setEnabled(!session.getHistory().isEmpty());

        // Baseline win probability (analytical, always fast — shown regardless of MC mode)
        double baselineWinProb = ProbabilityCalc.computeBaselineWinProb(session.getState(), pi);
        baselineWinProbLabel.setText(String.format("Current win prob: %.1f%%", baselineWinProb * 100));

        if (rankOpts.mcSimulations > 0) {
            // MC path: run ranking on background thread to keep UI responsive
            statusLabel.setText("Running MC simulations…");
            confirmBtn.setEnabled(false);
            final GameState snapState = session.getState().copy();
            final int snapPi = pi;

            SwingWorker<ArrayList<RankEntry>, Void> worker = new SwingWorker<>() {
                @Override
                protected ArrayList<RankEntry> doInBackground() {
                    return ProbabilityCalc.rankPurchasableProjects(snapState, snapPi, rankOpts);
                }

                @Override
                protected void done() {
                    try {
                        lastRanking = get();
                    } catch (Exception ex) {
                        lastRanking = new ArrayList<>();
                    }
                    statusLabel.setText("MC done (" + rankOpts.mcSimulations + " sims)");
                    confirmBtn.setEnabled(true);
                    rebuildTable();
                    if (!lastRanking.isEmpty()) {
                        populateCenter(lastRanking.get(0));
                        if (rankTable.getRowCount() > 0) rankTable.setRowSelectionInterval(0, 0);
                    } else {
                        clearCenter("No affordable cards — save up!");
                    }
                }
            };
            worker.execute();
        } else {
            // Analytical path: fast, run on EDT directly
            statusLabel.setText("");
            lastRanking = ProbabilityCalc.rankPurchasableProjects(session.getState(), pi, rankOpts);
            rebuildTable();
            if (!lastRanking.isEmpty()) {
                populateCenter(lastRanking.get(0));
                if (rankTable.getRowCount() > 0) rankTable.setRowSelectionInterval(0, 0);
            } else {
                clearCenter("No affordable cards — save up!");
            }
        }
    }

    private void rebuildBuyCombo(int pi, Player activePlayer) {
        buyCombo.removeAllItems();
        buyCombo.addItem("— nothing —");
        int coins = activePlayer.getCoins();

        // Unbuilt pool (normal cards)
        for (Project p : session.getState().getUnbuilt_projects()) {
            if (p.getCost() <= coins) buyCombo.addItem(labelForProject(p));
        }

        // Großprojekte: always available if not yet owned by this player
        for (Project p : ProjectLoader.getAllProjects()) {
            if (p.isIs_grossprojekt() && !activePlayer.hasProject(p.getId()) && p.getCost() <= coins) {
                buyCombo.addItem(labelForProject(p) + " [GP]");
            }
        }
    }

    private void rebuildTable() {
        String[] cols = showWinProb
                ? new String[]{"Card", "Cost", "EV/rnd", "ROI", "Risk", "Var", "Win Δ"}
                : new String[]{"Card", "Cost", "EV/rnd", "ROI", "Risk", "Var"};

        tableModel.setColumnIdentifiers(cols);
        tableModel.setRowCount(0);

        for (RankEntry e : lastRanking) {
            Object[] row = showWinProb
                    ? new Object[]{e.project.getId(), e.project.getCost(),
                                   fmt2(e.evPerRound), fmt2(e.roiOverHorizon),
                                   fmt2(e.probNoIncomeOwnTurn), fmt2(e.variance),
                                   fmt2(e.winProbDelta)}
                    : new Object[]{e.project.getId(), e.project.getCost(),
                                   fmt2(e.evPerRound), fmt2(e.roiOverHorizon),
                                   fmt2(e.probNoIncomeOwnTurn), fmt2(e.variance)};
            tableModel.addRow(row);
        }

        // Re-apply renderers after column rebuild
        DefaultTableCellRenderer rightAlign = new DefaultTableCellRenderer();
        rightAlign.setHorizontalAlignment(SwingConstants.RIGHT);
        for (int c = 1; c < cols.length; c++) rankTable.getColumnModel().getColumn(c).setCellRenderer(rightAlign);
        rankTable.getColumnModel().getColumn(0).setCellRenderer(new CardNameRenderer());
        rankTable.getColumnModel().getColumn(0).setPreferredWidth(110);
        for (int c = 1; c < cols.length; c++) rankTable.getColumnModel().getColumn(c).setPreferredWidth(55);
    }

    private void populateCenter(RankEntry entry) {
        Project p = entry.project;
        topCardName.setText(UIUtils.capitalize(p.getId()));
        topCardCost.setText("Cost: " + p.getCost() + " coin" + (p.getCost() != 1 ? "s" : ""));
        topCardEV.setText("EV / round:    " + fmt2(entry.evPerRound));
        topCardROI.setText("ROI (10 turns): " + fmt2(entry.roiOverHorizon));
        topCardRisk.setText("Risk (P=0 income): " + fmt2(entry.probNoIncomeOwnTurn));
        topCardWinProb.setText("Win Δ:         " + fmt2(entry.winProbDelta));
        topCardNote.setText("<html><i>" + buildNote(entry) + "</i></html>");
        topCardColorBar.setBackground(colorForCard(p));
    }

    private void clearCenter(String message) {
        topCardName.setText("—");
        topCardCost.setText("");
        topCardEV.setText("EV / round:    —");
        topCardROI.setText("ROI (10 turns): —");
        topCardRisk.setText("Risk (P=0 income): —");
        topCardWinProb.setText("Win Δ:         —");
        topCardNote.setText("<html><i>" + message + "</i></html>");
        topCardColorBar.setBackground(Color.LIGHT_GRAY);
    }

    private void refreshHistory() {
        List<TurnRecord> history = session.getHistory();
        StringBuilder sb = new StringBuilder();
        String[] names = session.getPlayerNames();
        for (int i = history.size() - 1; i >= 0; i--) {
            TurnRecord t = history.get(i);
            String pName = names[t.playerIndex];
            String buy = (t.bought != null) ? " → bought " + t.bought.getId() : " → saved";
            sb.append(pName).append(" rolled ").append(t.roll).append(buy).append("\n");
        }
        historyArea.setText(sb.toString());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String buildNote(RankEntry e) {
        if (e.notes != null && !e.notes.isEmpty()) return e.notes;
        String name = UIUtils.capitalize(e.project.getId());
        if (e.roiOverHorizon > 0) {
            return name + ": " + fmt2(e.evPerRound) + " coins/round, ROI " + fmt2(e.roiOverHorizon);
        }
        return name + " (cost may not be recouped in 10 turns)";
    }

    private static String fmt2(double v) {
        return String.format("%.2f", v);
    }

    private static String labelForProject(Project p) {
        return UIUtils.capitalize(p.getId()) + " (" + p.getCost() + ")";
    }

    /** Extracts the project ID from a combo label like "Weizenfeld (1)" or "Bahnhof (4) [GP]" */
    private static String projectIdFromLabel(String label) {
        // Remove [GP] suffix if present, then parse from the capitalized label
        String clean = label.replace(" [GP]", "");
        // Label is "Name (cost)" where Name is capitalize(id), so we lowercase again
        int paren = clean.indexOf(" (");
        String name = paren >= 0 ? clean.substring(0, paren) : clean;
        return name.toLowerCase();
    }

    private static Color colorForCard(Project p) {
        return colorForCard(p.getColor(), true);
    }

    /**
     * Returns the display color for a card.
     *
     * @param colorId card color string (e.g. "blau", "rot")
     * @param saturated true = vivid/saturated color (for color bars); false = pastel (for table cell backgrounds)
     */
    private static Color colorForCard(String colorId, boolean saturated) {
        if (saturated) {
            return switch (colorId) {
                case "blau"  -> CARD_COLORS[0];
                case "rot"   -> CARD_COLORS[1];
                case "grün"  -> CARD_COLORS[2];
                case "lila"  -> CARD_COLORS[3];
                case "gelb"  -> CARD_COLORS[4];
                default      -> Color.LIGHT_GRAY;
            };
        } else {
            return switch (colorId) {
                case "blau"  -> new Color(0xD0E8FF);
                case "rot"   -> new Color(0xFFD5C2);
                case "grün"  -> new Color(0xD5F0C1);
                case "lila"  -> new Color(0xE8D5FF);
                case "gelb"  -> new Color(0xFFF5B0);
                default      -> Color.WHITE;
            };
        }
    }

    /**
     * Replaces the current session with a new one (called from {@link SnapshotDialog}).
     * The window refreshes to reflect the new state.
     */
    void replaceSession(GameSession newSession) {
        this.session = newSession;
        refreshAll();
    }

    // ---- Layout helpers ----
    private static JPanel wrap(JComponent c) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        p.add(c);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private static JLabel bold(String text) {
        JLabel l = new JLabel(text);
        l.setFont(HEADER_FONT);
        return l;
    }

    private static JLabel metricLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(MONO_FONT);
        return l;
    }

    private static TitledBorder titledBorder(String title) {
        return BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), title, TitledBorder.LEFT, TitledBorder.TOP,
                new Font("Arial", Font.BOLD, 12));
    }

    // =========================================================================
    // Card name color renderer for the table
    // =========================================================================

    private static class CardNameRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                        boolean isSelected, boolean hasFocus,
                                                        int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (!isSelected && value instanceof String) {
                String id = (String) value;
                Project p = ProjectLoader.getProject(id).orElse(null);
                if (p != null) {
                    setBackground(colorForCard(p.getColor(), false));
                } else {
                    setBackground(Color.WHITE);
                }
            } else if (isSelected) {
                setBackground(table.getSelectionBackground());
            }
            return this;
        }
    }
}
