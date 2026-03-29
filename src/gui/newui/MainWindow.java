package gui.newui;

import logic.probability.*;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Main turn-by-turn game window.
 *
 * <p>Layout (three columns inside a JSplitPane chain):
 * <pre>
 *  ┌────────────────────┬───────────────────────┬──────────────────────┐
 *  │ Current Turn       │  Card Details         │  All Cards (Ranking) │
 *  │ Tracker (~230)     │  (center ~300)        │  (right, flex)       │
 *  └────────────────────┴───────────────────────┴──────────────────────┘
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
    private int mcSimCount = 1000;

    // ---- left panel components ----
    private JLabel activePlayerLabel;
    private JLabel rollRangeLabel;
    private JSpinner rollSpinner;
    private JCheckBox doublesCheckBox;
    private JComboBox<String> buyCombo;
    private JButton confirmBtn;
    private JButton undoBtn;
    private JLabel coinsLabel;
    private JPanel historyPanel;
    private JPanel rollPreviewPanel;

    // ---- center panel components ----
    private JLabel topCardName;
    private JLabel topCardCost;
    private JLabel topCardColorTag;
    private JLabel topCardDesc;
    private JLabel topCardEV;
    private JLabel topCardROI;
    private JLabel topCardRisk;
    private JLabel topCardVar;
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
    private JSpinner mcSimSpinner;
    private JButton mcReloadBtn;

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
        setMinimumSize(new Dimension(980, 600));
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

        // Wire roll spinner change listener now that rollPreviewPanel is initialized.
        rollSpinner.addChangeListener(e -> refreshAfterRollChange());

        JSplitPane rightSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, center, right);
        rightSplit.setDividerLocation(320);
        rightSplit.setResizeWeight(0.35);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, rightSplit);
        mainSplit.setDividerLocation(240);
        mainSplit.setResizeWeight(0.0);

        setContentPane(mainSplit);
    }

    // ---- Left panel: turn input ----

    private JPanel buildLeftPanel() {
        // Outer panel uses BorderLayout so the history scroll fills all remaining vertical space.
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(titledBorder("Current Turn Tracker"));
        panel.setPreferredSize(new Dimension(240, 0));

        // Controls sub-panel (fixed height): everything except the history log
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));

        // Active player
        activePlayerLabel = new JLabel("Player 1's turn");
        activePlayerLabel.setFont(HEADER_FONT);
        activePlayerLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.add(wrap(activePlayerLabel));

        // Coins display
        coinsLabel = new JLabel("Coins: 3");
        coinsLabel.setFont(new Font("Arial", Font.BOLD, 14));
        coinsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.add(wrap(coinsLabel));

        controls.add(Box.createVerticalStrut(8));

        // Roll input
        rollRangeLabel = bold("Dice roll (1–6):");
        rollRangeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.add(wrap(rollRangeLabel));
        rollSpinner = new BoundedSpinner(new SpinnerNumberModel(3, 1, 6, 1));
        rollSpinner.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        rollSpinner.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.add(rollSpinner);

        // Doubles (Pasch) checkbox: only shown when player has Bahnhof + Freizeitpark
        doublesCheckBox = new JCheckBox("Doubles (Pasch)!");
        doublesCheckBox.setFont(new Font("Arial", Font.BOLD, 12));
        doublesCheckBox.setForeground(new Color(0x7030A0));
        doublesCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        doublesCheckBox.setToolTipText(
                "<html>Check this if you rolled doubles (both dice show same face).<br>" +
                "Freizeitpark grants you a bonus second turn!</html>");
        doublesCheckBox.setVisible(false);
        controls.add(wrap(doublesCheckBox));

        controls.add(Box.createVerticalStrut(8));

        // Roll preview (compact, inline with turn input)
        controls.add(wrap(bold("Roll outcome:")));
        rollPreviewPanel = new JPanel();
        rollPreviewPanel.setLayout(new BoxLayout(rollPreviewPanel, BoxLayout.Y_AXIS));
        rollPreviewPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        rollPreviewPanel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        JScrollPane previewScroll = new JScrollPane(rollPreviewPanel);
        previewScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        previewScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
        previewScroll.setBorder(BorderFactory.createLineBorder(new Color(0xCCCCCC)));
        controls.add(previewScroll);

        controls.add(Box.createVerticalStrut(8));

        // Buy dropdown
        controls.add(wrap(bold("Purchase (optional):")));
        buyCombo = new JComboBox<>();
        buyCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        buyCombo.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.add(buyCombo);

        controls.add(Box.createVerticalStrut(10));

        // Confirm button
        confirmBtn = new JButton("Confirm Turn");
        confirmBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        confirmBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        confirmBtn.addActionListener(this::onConfirmTurn);
        controls.add(confirmBtn);

        controls.add(Box.createVerticalStrut(4));

        // Undo button
        undoBtn = new JButton("Undo Last Turn");
        undoBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        undoBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        undoBtn.addActionListener(this::onUndo);
        controls.add(undoBtn);

        controls.add(Box.createVerticalStrut(10));

        // Snapshot button
        JButton snapshotBtn = new JButton("Enter Snapshot…");
        snapshotBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        snapshotBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        snapshotBtn.addActionListener(this::onOpenSnapshot);
        controls.add(snapshotBtn);

        controls.add(Box.createVerticalStrut(4));

        // Save / Load buttons
        JButton saveBtn = new JButton("Save Game…");
        saveBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        saveBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        saveBtn.addActionListener(this::onSave);
        controls.add(saveBtn);

        controls.add(Box.createVerticalStrut(2));

        JButton loadBtn = new JButton("Load Game…");
        loadBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        loadBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        loadBtn.addActionListener(this::onLoad);
        controls.add(loadBtn);

        controls.add(Box.createVerticalStrut(8));
        controls.add(wrap(bold("Turn history:")));

        panel.add(controls, BorderLayout.NORTH);

        // History scroll fills all remaining vertical space (CENTER stretches in BorderLayout)
        historyPanel = new JPanel();
        historyPanel.setLayout(new BoxLayout(historyPanel, BoxLayout.Y_AXIS));
        historyPanel.setBackground(Color.WHITE);
        JScrollPane histScroll = new JScrollPane(historyPanel);
        panel.add(histScroll, BorderLayout.CENTER);

        return panel;
    }

    // ---- Center panel: card details ----

    private JPanel buildCenterPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(titledBorder("Card Details"));
        panel.setPreferredSize(new Dimension(320, 0));

        topCardColorBar = new JPanel();
        topCardColorBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
        topCardColorBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(topCardColorBar);

        panel.add(Box.createVerticalStrut(6));

        // Card name + color tag on same row
        JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        topCardName = new JLabel("—");
        topCardName.setFont(new Font("Arial", Font.BOLD, 18));
        nameRow.add(topCardName);
        topCardColorTag = new JLabel("");
        topCardColorTag.setFont(new Font("Arial", Font.BOLD, 11));
        topCardColorTag.setOpaque(true);
        topCardColorTag.setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 4));
        nameRow.add(topCardColorTag);
        nameRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(nameRow);

        topCardCost = new JLabel("Cost: —");
        topCardCost.setFont(new Font("Arial", Font.PLAIN, 13));
        topCardCost.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(wrap(topCardCost));

        // Card description
        topCardDesc = new JLabel("<html><i>—</i></html>");
        topCardDesc.setFont(new Font("Arial", Font.ITALIC, 11));
        topCardDesc.setForeground(new Color(0x555555));
        topCardDesc.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(wrap(topCardDesc));

        panel.add(Box.createVerticalStrut(8));

        // Metrics in two-column grid with labels + values
        JPanel metricsGrid = new JPanel(new GridLayout(0, 2, 4, 3));
        metricsGrid.setAlignmentX(Component.LEFT_ALIGNMENT);

        topCardEV    = addMetricRow(metricsGrid, "EV / round:", "Expected coins earned per full game round (own turn + opponent turns). Higher = better income engine.");
        topCardROI   = addMetricRow(metricsGrid, "ROI (10 turns):", "Discounted return on investment over 10 rounds minus purchase cost. Positive = profitable buy.");
        topCardRisk  = addMetricRow(metricsGrid, "P(0 income):", "Probability of earning zero coins on your own turn. Lower = more reliable income.");
        topCardVar   = addMetricRow(metricsGrid, "Variance:", "Statistical spread of per-turn income. Lower = more predictable; higher = boom-or-bust.");
        topCardWinProb = addMetricRow(metricsGrid, "Win Prob Δ:", "Change in estimated win probability from buying this card. Requires win-prob analysis.");
        topCardWinProb.setVisible(false);
        // Also hide its label
        ((JLabel) metricsGrid.getComponent(metricsGrid.getComponentCount() - 2)).setVisible(false);

        JPanel metricsWrapper = new JPanel(new BorderLayout());
        metricsWrapper.add(metricsGrid, BorderLayout.NORTH);
        metricsWrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(metricsWrapper);

        panel.add(Box.createVerticalStrut(6));

        // Baseline win probability
        baselineWinProbLabel = new JLabel("Current win prob: —");
        baselineWinProbLabel.setFont(new Font("Arial", Font.BOLD, 12));
        baselineWinProbLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel winProbExplain = new JLabel("<html><small>Analytical softmax estimate based on relative EV score vs opponents</small></html>");
        winProbExplain.setFont(new Font("Arial", Font.PLAIN, 10));
        winProbExplain.setForeground(new Color(0x666666));
        winProbExplain.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(wrap(baselineWinProbLabel));
        panel.add(wrap(winProbExplain));

        panel.add(Box.createVerticalStrut(6));

        topCardNote = new JLabel("<html><i>—</i></html>");
        topCardNote.setFont(new Font("Arial", Font.ITALIC, 12));
        topCardNote.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(wrap(topCardNote));

        return panel;
    }

    /**
     * Adds a two-cell metric row (label + value) to the grid panel and returns the value label.
     * The label gets a tooltip with the explanation text.
     */
    private JLabel addMetricRow(JPanel grid, String labelText, String tooltip) {
        JLabel lbl = new JLabel(labelText);
        lbl.setFont(new Font("Arial", Font.BOLD, 12));
        lbl.setToolTipText(tooltip);
        JLabel val = new JLabel("—");
        val.setFont(MONO_FONT);
        val.setToolTipText(tooltip);
        grid.add(lbl);
        grid.add(val);
        return val;
    }

    // ---- Right panel: full ranking table ----

    private JPanel buildRightPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(titledBorder("All Affordable Cards"));

        String[] cols = {"Card", "Cost", "EV/rnd", "ROI", "P(0)", "Var"};
        tableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
            @Override public Class<?> getColumnClass(int c) {
                return c == 0 ? String.class : Double.class;
            }
        };
        rankTable = new JTable(tableModel);
        rankTable.setFont(MONO_FONT);
        rankTable.setRowHeight(22);
        rankTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        rankTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) onTableSelect();
        });

        // Sortable columns
        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(tableModel);
        rankTable.setRowSorter(sorter);
        // Card column (String): alphabetic sort; numeric columns: numeric sort
        sorter.setComparator(0, java.util.Comparator.naturalOrder());
        for (int c = 1; c <= 5; c++) {
            sorter.setComparator(c, java.util.Comparator.comparingDouble(o -> (Double) o));
        }
        // Default: sort by ROI (column 3) descending
        java.util.List<RowSorter.SortKey> sortKeys = new java.util.ArrayList<>();
        sortKeys.add(new RowSorter.SortKey(3, SortOrder.DESCENDING));
        sorter.setSortKeys(sortKeys);

        panel.add(new JScrollPane(rankTable), BorderLayout.CENTER);

        // Button bar at bottom
        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));

        toggleWinProbBtn = new JButton("Show Win Prob Δ");
        toggleWinProbBtn.addActionListener(this::onToggleWinProb);
        btnBar.add(toggleWinProbBtn);

        deepAnalysisBtn = new JToggleButton("Deep Analysis (MC)");
        deepAnalysisBtn.setToolTipText("Run Monte Carlo simulations per card for accurate win-probability delta.");
        deepAnalysisBtn.addActionListener(this::onToggleDeepAnalysis);
        btnBar.add(deepAnalysisBtn);

        // MC sim count spinner (only relevant when deep analysis is on)
        mcSimSpinner = new BoundedSpinner(new SpinnerNumberModel(1000, 100, 10000, 100));
        mcSimSpinner.setPreferredSize(new Dimension(70, 24));
        mcSimSpinner.setToolTipText("Number of Monte Carlo simulations (100–10000). More = accurate but slower.");
        mcSimSpinner.setEnabled(false);
        mcSimSpinner.addChangeListener(e -> {
            mcSimCount = (int) mcSimSpinner.getValue();
            rankOpts.mcSimulations = deepAnalysisBtn.isSelected() ? mcSimCount : 0;
        });
        btnBar.add(new JLabel("N:"));
        btnBar.add(mcSimSpinner);

        // Reload button for MC without double-toggling
        mcReloadBtn = new JButton("⟳");
        mcReloadBtn.setToolTipText("Re-run Monte Carlo analysis with the current settings.");
        mcReloadBtn.setEnabled(false);
        mcReloadBtn.addActionListener(e -> {
            if (deepAnalysisBtn.isSelected() && showWinProb) {
                refreshAll();
            }
        });
        btnBar.add(mcReloadBtn);

        statusLabel = new JLabel("");
        statusLabel.setFont(new Font("Arial", Font.ITALIC, 11));
        btnBar.add(statusLabel);

        panel.add(btnBar, BorderLayout.SOUTH);

        // Column tooltips explaining headers
        rankTable.getTableHeader().setToolTipText(
                "<html>EV/rnd = expected coins/round · ROI = return on investment (10 turns) · " +
                "P(0) = probability of zero income · Var = variance</html>");

        return panel;
    }

    // =========================================================================
    // Event handlers
    // =========================================================================

    private void onConfirmTurn(ActionEvent e) {
        int pi = session.nextPlayerIndex();
        int roll = (int) rollSpinner.getValue();
        boolean isDoubles = doublesCheckBox.isVisible() && doublesCheckBox.isSelected();

        Project bought = null;
        String selected = (String) buyCombo.getSelectedItem();
        if (selected != null && !selected.equals("— nothing —")) {
            bought = ProjectLoader.getProject(projectIdFromLabel(selected))
                    .orElse(null);
        }

        try {
            session.applyTurn(new TurnRecord(pi, roll, bought, isDoubles));
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Invalid Turn", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Reset doubles checkbox after confirming
        doublesCheckBox.setSelected(false);

        if (session.isFinished()) {
            String winner = session.getPlayerNames()[session.getWinnerIndex()];
            showGameOver(winner);
            return;
        }

        // Show bonus turn notification if Freizeitpark triggered
        if (session.isBonusTurnPending()) {
            String pName = session.getPlayerNames()[pi];
            activePlayerLabel.setText("<html><b style='color:#7030A0'>" + pName
                    + " BONUS TURN!</b></html>");
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
        mcSimCount = (int) mcSimSpinner.getValue();
        rankOpts.mcSimulations = enabled ? mcSimCount : 0;
        deepAnalysisBtn.setText(enabled ? "Deep Analysis ON (MC)" : "Deep Analysis (MC)");
        mcSimSpinner.setEnabled(enabled);
        // Reload button is only active when both deep analysis is on AND win prob is shown
        mcReloadBtn.setEnabled(enabled && showWinProb);
        refreshAll();
    }

    private void onToggleWinProb(ActionEvent e) {
        showWinProb = !showWinProb;
        rankOpts.includeWinProbDelta = showWinProb;
        toggleWinProbBtn.setText(showWinProb ? "Hide Win Prob Δ" : "Show Win Prob Δ");
        setWinProbRowVisible(showWinProb);
        // Reload button only enabled when deep analysis is on AND win prob is shown
        mcReloadBtn.setEnabled(deepAnalysisBtn.isSelected() && showWinProb);

        if (showWinProb) {
            // Only recompute if analytical (MC was already computed or is off)
            if (rankOpts.mcSimulations == 0) {
                refreshAll();
            } else {
                // MC is on: add Win Δ column and rebuild table without re-running MC
                rebuildTable();
            }
        } else {
            // Just rebuild table without the column — no need to recompute
            rebuildTable();
        }
    }

    /**
     * Shows or hides the Win Prob row in the center panel.
     * The row occupies two cells in the metrics grid (label + value).
     */
    private void setWinProbRowVisible(boolean visible) {
        // topCardWinProb's label is the component just before it in the grid
        topCardWinProb.setVisible(visible);
        // Find and toggle its label sibling
        Container grid = topCardWinProb.getParent();
        if (grid != null) {
            int idx = -1;
            for (int i = 0; i < grid.getComponentCount(); i++) {
                if (grid.getComponent(i) == topCardWinProb) { idx = i; break; }
            }
            if (idx > 0) grid.getComponent(idx - 1).setVisible(visible);
        }
    }

    private void onTableSelect() {
        int viewRow = rankTable.getSelectedRow();
        if (viewRow < 0) return;
        int modelRow = rankTable.convertRowIndexToModel(viewRow);
        if (modelRow < 0 || modelRow >= lastRanking.size()) return;
        RankEntry entry = lastRanking.get(modelRow);
        populateCenter(entry);
    }

    private void onOpenSnapshot(ActionEvent e) {
        new SnapshotDialog(this, session).setVisible(true);
        refreshAll();
    }

    private void onSave(ActionEvent e) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Save Game");
        fc.setFileFilter(new FileNameExtensionFilter("Machi Koro save files (*.mkoro)", "mkoro"));
        fc.setSelectedFile(new java.io.File("game.mkoro"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = fc.getSelectedFile().toPath();
        if (!path.toString().endsWith(".mkoro")) {
            path = path.resolveSibling(path.getFileName() + ".mkoro");
        }
        try {
            session.save(path);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not save: " + ex.getMessage(),
                    "Save Failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onLoad(ActionEvent e) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Load Game");
        fc.setFileFilter(new FileNameExtensionFilter("Machi Koro save files (*.mkoro)", "mkoro"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = fc.getSelectedFile().toPath();
        try {
            GameSession loaded = GameSession.load(path);
            replaceSession(loaded);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not load: " + ex.getMessage(),
                    "Load Failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showGameOver(String winnerName) {
        confirmBtn.setEnabled(false);
        undoBtn.setEnabled(true);

        topCardColorBar.setBackground(CARD_COLORS[4]);
        topCardName.setText(winnerName + " wins!");
        topCardColorTag.setText("[GP]");
        topCardColorTag.setBackground(new Color(0xFFF5B0));
        topCardCost.setText("");
        topCardDesc.setText("<html><i>All 4 Großprojekte built!</i></html>");
        topCardEV.setText("—");
        topCardROI.setText("—");
        topCardRisk.setText("—");
        topCardVar.setText("—");
        topCardWinProb.setText("—");
        topCardWinProb.setVisible(false);
        baselineWinProbLabel.setText("Current win prob: 100%");
        topCardNote.setText("<html><i>Game over. Use Undo to continue or close the window.</i></html>");

        tableModel.setRowCount(0);
        statusLabel.setText("Game over!");
    }

    // =========================================================================
    // Data refresh
    // =========================================================================

    /**
     * Returns a copy of the current game state with coin deltas from the current roll
     * already applied. This is the state the active player actually buys from.
     */
    private GameState postRollState() {
        int pi   = session.nextPlayerIndex();
        int roll = (int) rollSpinner.getValue();
        GameState state  = session.getState().copy();
        int[] deltas = ProbabilityCalc.computeAllDeltasForRoll(state, pi, roll);
        Player[] players = state.getPlayers();
        for (int i = 0; i < players.length; i++) {
            players[i].setCoins(Math.max(0, players[i].getCoins() + deltas[i]));
        }
        return state;
    }

    private void refreshAll() {
        int pi = session.nextPlayerIndex();
        Player[] players = session.getState().getPlayers();
        Player activePlayer = players[pi];

        activePlayerLabel.setText(activePlayer.getName() + "'s turn");
        if (session.isBonusTurnPending()) {
            activePlayerLabel.setText("<html><b style='color:#7030A0'>" + activePlayer.getName()
                    + " — BONUS TURN (Freizeitpark)!</b></html>");
        } else {
            activePlayerLabel.setText(activePlayer.getName() + "'s turn");
        }
        coinsLabel.setText("Coins: " + activePlayer.getCoins());

        updateRollSpinner(activePlayer);

        GameState postRoll = postRollState();
        Player postRollPlayer = postRoll.getPlayers()[pi];
        int postRollCoins = postRollPlayer.getCoins();
        if (postRollCoins != activePlayer.getCoins()) {
            coinsLabel.setText(activePlayer.getCoins() + " → " + postRollCoins + " coins (after roll)");
        }

        rebuildBuyCombo(pi, postRollPlayer, postRoll);

        refreshHistory();
        undoBtn.setEnabled(!session.getHistory().isEmpty());

        refreshRollPreview();

        double baselineWinProb = ProbabilityCalc.computeBaselineWinProb(postRoll, pi);
        baselineWinProbLabel.setText(String.format("Win prob: %.1f%%", baselineWinProb * 100));

        if (rankOpts.mcSimulations > 0) {
            statusLabel.setText("Running MC…");
            confirmBtn.setEnabled(false);
            mcReloadBtn.setEnabled(false);
            final GameState snapState = postRoll;
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
                    mcReloadBtn.setEnabled(deepAnalysisBtn.isSelected() && showWinProb);
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
            statusLabel.setText("");
            lastRanking = ProbabilityCalc.rankPurchasableProjects(postRoll, pi, rankOpts);
            rebuildTable();
            if (!lastRanking.isEmpty()) {
                populateCenter(lastRanking.get(0));
                if (rankTable.getRowCount() > 0) rankTable.setRowSelectionInterval(0, 0);
            } else {
                clearCenter("No affordable cards — save up!");
            }
        }
    }

    /**
     * Updates the roll spinner's range and default value for the given active player.
     * Also shows the doubles checkbox when the player has both Bahnhof and Freizeitpark.
     */
    private void updateRollSpinner(Player activePlayer) {
        boolean hasBahnhof = activePlayer.hasProject("bahnhof");
        int newMax = hasBahnhof ? 12 : 6;
        int newDefault = hasBahnhof ? 7 : 3;
        int current = (int) rollSpinner.getValue();
        int clamped = Math.min(current, newMax);
        SpinnerNumberModel model = (SpinnerNumberModel) rollSpinner.getModel();
        model.setMaximum(newMax);
        model.setMinimum(1);
        if (current != clamped) rollSpinner.setValue(clamped);
        if ((hasBahnhof && current <= 6) || (!hasBahnhof && current > 6)) {
            rollSpinner.setValue(newDefault);
        }
        rollRangeLabel.setText("Dice roll (1–" + newMax + "):");

        // Show doubles checkbox only when player can roll 2 dice (Bahnhof) AND has Freizeitpark
        boolean canGetBonus = hasBahnhof && activePlayer.hasProject("freizeitpark");
        doublesCheckBox.setVisible(canGetBonus);
        if (!canGetBonus) doublesCheckBox.setSelected(false);
    }

    /**
     * Computes and displays per-player coin deltas for the current roll spinner value.
     */
    private void refreshRollPreview() {
        if (rollPreviewPanel == null) return;
        int roll = (int) rollSpinner.getValue();
        int pi = session.nextPlayerIndex();
        GameState state = session.getState();
        int[] deltas = ProbabilityCalc.computeAllDeltasForRoll(state, pi, roll);
        String[] names = session.getPlayerNames();

        rollPreviewPanel.removeAll();
        for (int i = 0; i < deltas.length; i++) {
            String sign = deltas[i] >= 0 ? "+" : "";
            JLabel lbl = new JLabel(names[i] + ": " + sign + deltas[i] + " coins");
            lbl.setFont(new Font("Monospaced", Font.PLAIN, 11));
            if (deltas[i] > 0) lbl.setForeground(new Color(0x007700));
            else if (deltas[i] < 0) lbl.setForeground(new Color(0xAA0000));
            rollPreviewPanel.add(lbl);
        }
        rollPreviewPanel.revalidate();
        rollPreviewPanel.repaint();
    }

    /**
     * Refreshes all roll-dependent UI elements when the roll spinner value changes.
     */
    private void refreshAfterRollChange() {
        refreshRollPreview();

        int pi = session.nextPlayerIndex();
        GameState postRoll = postRollState();
        Player preRollPlayer  = session.getState().getPlayers()[pi];
        Player postRollPlayer = postRoll.getPlayers()[pi];
        int preCoins  = preRollPlayer.getCoins();
        int postCoins = postRollPlayer.getCoins();
        if (postCoins != preCoins) {
            coinsLabel.setText(preCoins + " → " + postCoins + " coins (after roll)");
        } else {
            coinsLabel.setText("Coins: " + preCoins);
        }

        rebuildBuyCombo(pi, postRollPlayer, postRoll);

        double baselineWinProb = ProbabilityCalc.computeBaselineWinProb(postRoll, pi);
        baselineWinProbLabel.setText(String.format("Win prob: %.1f%%", baselineWinProb * 100));

        if (rankOpts.mcSimulations == 0) {
            lastRanking = ProbabilityCalc.rankPurchasableProjects(postRoll, pi, rankOpts);
            rebuildTable();
            if (!lastRanking.isEmpty()) {
                populateCenter(lastRanking.get(0));
                if (rankTable.getRowCount() > 0) rankTable.setRowSelectionInterval(0, 0);
            } else {
                clearCenter("No affordable cards — save up!");
            }
        }
    }

    private void rebuildBuyCombo(int pi, Player postRollPlayer, GameState postRoll) {
        buyCombo.removeAllItems();
        buyCombo.addItem("— nothing —");
        int coins = postRollPlayer.getCoins();

        for (Project p : postRoll.getUnbuilt_projects()) {
            if (p.getCost() <= coins) buyCombo.addItem(labelForProject(p));
        }

        for (Project p : ProjectLoader.getAllProjects()) {
            if (p.isIs_grossprojekt() && !postRollPlayer.hasProject(p.getId()) && p.getCost() <= coins) {
                buyCombo.addItem(labelForProject(p) + " [GP]");
            }
        }
    }

    private void rebuildTable() {
        // Preserve the user's current sort order across rebuilds
        RowSorter<?> existingSorter = rankTable.getRowSorter();
        java.util.List<? extends RowSorter.SortKey> savedSortKeys =
                (existingSorter != null) ? existingSorter.getSortKeys() : null;

        String[] cols = showWinProb
                ? new String[]{"Card", "Cost", "EV/rnd", "ROI", "P(0)", "Var", "Win Δ"}
                : new String[]{"Card", "Cost", "EV/rnd", "ROI", "P(0)", "Var"};

        tableModel.setColumnIdentifiers(cols);
        tableModel.setRowCount(0);

        for (RankEntry e : lastRanking) {
            String cardLabel = e.project.isIs_grossprojekt()
                    ? UIUtils.capitalize(e.project.getId()) + " [GP]"
                    : UIUtils.capitalize(e.project.getId());
            Object[] row = showWinProb
                    ? new Object[]{cardLabel, (double) e.project.getCost(),
                                   e.evPerRound, e.roiOverHorizon,
                                   e.probNoIncomeOwnTurn, e.variance,
                                   e.winProbDelta}
                    : new Object[]{cardLabel, (double) e.project.getCost(),
                                   e.evPerRound, e.roiOverHorizon,
                                   e.probNoIncomeOwnTurn, e.variance};
            tableModel.addRow(row);
        }

        // Re-apply renderers and widths
        rankTable.getColumnModel().getColumn(0).setCellRenderer(new CardNameRenderer());
        rankTable.getColumnModel().getColumn(0).setPreferredWidth(120);

        NumericCellRenderer numRenderer = new NumericCellRenderer();
        for (int c = 1; c < cols.length; c++) {
            rankTable.getColumnModel().getColumn(c).setCellRenderer(numRenderer);
            rankTable.getColumnModel().getColumn(c).setPreferredWidth(52);
        }

        // Re-attach sorter comparators after column rebuild
        TableRowSorter<?> sorter = (TableRowSorter<?>) rankTable.getRowSorter();
        if (sorter != null) {
            @SuppressWarnings("unchecked")
            TableRowSorter<DefaultTableModel> trs = (TableRowSorter<DefaultTableModel>) sorter;
            trs.setComparator(0, java.util.Comparator.naturalOrder());
            for (int c = 1; c < cols.length; c++) {
                trs.setComparator(c, java.util.Comparator.comparingDouble(o -> (Double) o));
            }
            // Restore the previously saved sort order (or keep default ROI-descending)
            if (savedSortKeys != null && !savedSortKeys.isEmpty()) {
                // Clamp sort key columns to valid range after possible column count change
                java.util.List<RowSorter.SortKey> restored = new java.util.ArrayList<>();
                for (RowSorter.SortKey sk : savedSortKeys) {
                    if (sk.getColumn() < cols.length) restored.add(sk);
                }
                if (!restored.isEmpty()) trs.setSortKeys(restored);
            }
        }
    }

    private void populateCenter(RankEntry entry) {
        Project p = entry.project;
        topCardName.setText(UIUtils.capitalize(p.getId()));

        String colorStr = colorLabel(p.getColor());
        topCardColorTag.setText(colorStr);
        topCardColorTag.setBackground(colorForCard(p.getColor(), false));
        topCardColorTag.setForeground(colorForCard(p.getColor(), true).darker());

        topCardCost.setText("Cost: " + p.getCost() + " coin" + (p.getCost() != 1 ? "s" : "")
                + activationLabel(p));
        String desc = p.getDescription();
        topCardDesc.setText("<html><i>" + (desc != null && !desc.isEmpty() ? desc : "—") + "</i></html>");

        topCardEV.setText(fmt2(entry.evPerRound));
        topCardROI.setText(fmt2(entry.roiOverHorizon));
        topCardRisk.setText(fmt2(entry.probNoIncomeOwnTurn));
        topCardVar.setText(fmt2(entry.variance));
        topCardWinProb.setText(fmt2(entry.winProbDelta));
        topCardNote.setText("<html><i>" + buildNote(entry) + "</i></html>");
        topCardColorBar.setBackground(colorForCard(p));
        // Always re-apply visibility to keep it in sync with the global toggle
        setWinProbRowVisible(showWinProb);
    }

    private void clearCenter(String message) {
        topCardName.setText("—");
        topCardColorTag.setText("");
        topCardColorTag.setBackground(null);
        topCardCost.setText("");
        topCardDesc.setText("");
        topCardEV.setText("—");
        topCardROI.setText("—");
        topCardRisk.setText("—");
        topCardVar.setText("—");
        topCardWinProb.setText("—");
        topCardNote.setText("<html><i>" + message + "</i></html>");
        topCardColorBar.setBackground(Color.LIGHT_GRAY);
    }

    private void refreshHistory() {
        historyPanel.removeAll();
        List<TurnRecord> history = session.getHistory();
        String[] names = session.getPlayerNames();
        GameState current = session.getState();
        String[] playerColors = new String[current.getPlayers().length];
        for (int i = 0; i < current.getPlayers().length; i++) {
            // Assign a distinct color per player for history display
            Color c = playerIndexColor(i);
            playerColors[i] = String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
        }

        for (int i = history.size() - 1; i >= 0; i--) {
            TurnRecord t = history.get(i);
            String pName = names[t.playerIndex];
            String colorHex = playerColors[t.playerIndex];

            StringBuilder html = new StringBuilder("<html>");
            html.append("<b style='color:").append(colorHex).append("'>").append(pName).append("</b>");
            html.append(" rolled <b>").append(t.roll).append("</b>");
            if (t.isDoubles) html.append(" 🎲🎲 <b style='color:#7030A0'>DOUBLES!</b>");
            if (t.bought != null) {
                String gpMark = t.bought.isIs_grossprojekt() ? " [GP]" : "";
                html.append(" → bought <b>").append(UIUtils.capitalize(t.bought.getId()))
                    .append(gpMark).append("</b> (−").append(t.bought.getCost()).append("¢)");
            } else {
                html.append(" → saved");
            }
            html.append("</html>");

            JLabel lbl = new JLabel(html.toString());
            lbl.setFont(new Font("Arial", Font.PLAIN, 11));
            lbl.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            // Alternate row background
            lbl.setOpaque(true);
            lbl.setBackground((i % 2 == 0) ? Color.WHITE : new Color(0xF5F5F5));
            historyPanel.add(lbl);
        }

        historyPanel.revalidate();
        historyPanel.repaint();
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
        String clean = label.replace(" [GP]", "");
        int paren = clean.indexOf(" (");
        String name = paren >= 0 ? clean.substring(0, paren) : clean;
        return name.toLowerCase();
    }

    /** Returns a short activation string for a card (e.g. " · Rolls: 2, 3" or " · All turns"). */
    private static String activationLabel(Project p) {
        if (p.isIs_grossprojekt()) return " · Großprojekt";
        int[] dice = p.getDice_activation();
        if (dice == null || dice.length == 0) return "";
        StringBuilder sb = new StringBuilder(" · Roll");
        if (dice.length > 1) sb.append("s");
        sb.append(": ");
        for (int i = 0; i < dice.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(dice[i]);
        }
        return sb.toString();
    }

    private static Color playerIndexColor(int idx) {
        return switch (idx) {
            case 0 -> new Color(0x1565C0); // dark blue
            case 1 -> new Color(0xAD1457); // dark pink
            case 2 -> new Color(0x2E7D32); // dark green
            case 3 -> new Color(0xE65100); // dark orange
            default -> Color.DARK_GRAY;
        };
    }

    private static String colorLabel(String color) {
        return switch (color) {
            case "blau"  -> "Blau";
            case "rot"   -> "Rot";
            case "grün"  -> "Grün";
            case "lila"  -> "Lila";
            case "gelb"  -> "Gelb";
            default      -> color;
        };
    }

    private static Color colorForCard(Project p) {
        return colorForCard(p.getColor(), true);
    }

    /**
     * Returns the display color for a card.
     *
     * @param colorId  card color string (e.g. "blau", "rot")
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
            if (!isSelected && value instanceof String label) {
                // Strip [GP] suffix to look up color
                String id = label.replace(" [GP]", "").toLowerCase();
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

    // =========================================================================
    // Color-coded numeric cell renderer
    // =========================================================================

    /**
     * Renders numeric table cells with 2-decimal formatting and color-coding:
     * positive values use a green tint, negative values use a red tint.
     */
    private static class NumericCellRenderer extends DefaultTableCellRenderer {
        NumericCellRenderer() {
            setHorizontalAlignment(SwingConstants.RIGHT);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                        boolean isSelected, boolean hasFocus,
                                                        int row, int column) {
            if (value instanceof Double d) {
                value = String.format("%.2f", d);
            }
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (!isSelected && value instanceof String s) {
                try {
                    double d = Double.parseDouble(s);
                    if (d > 0.5) {
                        setBackground(new Color(0xDDFFDD));
                    } else if (d < -0.5) {
                        setBackground(new Color(0xFFDDDD));
                    } else {
                        setBackground(table.getBackground());
                    }
                } catch (NumberFormatException ignored) {
                    setBackground(table.getBackground());
                }
            } else if (isSelected) {
                setBackground(table.getSelectionBackground());
            }
            return this;
        }
    }
}
