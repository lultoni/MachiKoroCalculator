package gui.newui;

import logic.probability.*;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.InputStream;
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
    /** Coin icon scaled to 16×16, or null if the resource could not be loaded. */
    private static final ImageIcon COIN_ICON = loadScaledIcon("resources/other_icons/COIN.png", 16);

    // ---- state ----
    private GameSession session;
    private final RankingOptions rankOpts = new RankingOptions();
    private boolean showWinProb = false;
    private int mcSimCount = 1000;

    // ---- left panel components ----
    private JLabel activePlayerLabel;
    private DiceSelectorPanel dieStrip1;   // always shown (mandatory, 1d6 or first of 2d6)
    private DiceSelectorPanel dieStrip2;   // shown when Bahnhof owned (optional second die)
    private JPanel dieStrip2Wrapper;       // wrapping panel to show/hide dieStrip2
    private JCheckBox doublesCheckBox;
    private JComboBox<String> buyCombo;
    private JButton confirmBtn;
    private JButton undoBtn;
    private JLabel coinsLabel;      // shows "N coins" in bold text
    private JLabel coinsAfterLabel; // shows post-roll delta (hidden when no change)
    private JPanel historyPanel;
    private JPanel rollPreviewPanel;

    // ---- center panel components ----
    private JLabel topCardName;
    private JPanel topCardCostRow;  // cost + activation dice faces
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
        setTitle(Strings.mainWindowTitle());
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1020, 600));
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

        // Wire die strip change listeners now that rollPreviewPanel is initialized.
        dieStrip1.addChangeListener(e -> refreshAfterRollChange());
        dieStrip2.addChangeListener(e -> refreshAfterRollChange());

        JSplitPane rightSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, center, right);
        rightSplit.setDividerLocation(320);
        rightSplit.setResizeWeight(0.35);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, rightSplit);
        mainSplit.setDividerLocation(240);
        mainSplit.setResizeWeight(0.0);

        setContentPane(mainSplit);
        setJMenuBar(buildMenuBar());
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu langMenu = new JMenu(Strings.menuLanguage());
        ButtonGroup langGroup = new ButtonGroup();

        JRadioButtonMenuItem deItem = new JRadioButtonMenuItem(Strings.menuLangDE());
        JRadioButtonMenuItem enItem = new JRadioButtonMenuItem(Strings.menuLangEN());
        langGroup.add(deItem);
        langGroup.add(enItem);
        deItem.setSelected(Strings.isDE());
        enItem.setSelected(!Strings.isDE());

        deItem.addActionListener(e -> onLanguageChange(Strings.Locale.DE));
        enItem.addActionListener(e -> onLanguageChange(Strings.Locale.EN));

        langMenu.add(deItem);
        langMenu.add(enItem);
        bar.add(langMenu);
        return bar;
    }

    private void onLanguageChange(Strings.Locale locale) {
        Strings.setLocale(locale);
        // Rebuild the entire window in-place: tear down content and menu, rebuild.
        setTitle(Strings.mainWindowTitle());
        buildUI();
        refreshAll();
        revalidate();
        repaint();
    }

    // ---- Left panel: turn input ----

    private JPanel buildLeftPanel() {
        // Outer panel uses BorderLayout so the history scroll fills all remaining vertical space.
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(titledBorder(Strings.leftPanelTitle()));
        panel.setPreferredSize(new Dimension(240, 0));

        // Controls sub-panel (fixed height): everything except the history log
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));

        // Active player
        activePlayerLabel = new JLabel(Strings.playerTurn("Player 1"));
        activePlayerLabel.setFont(HEADER_FONT);
        activePlayerLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.add(wrap(activePlayerLabel));

        // Coins display — bold text with post-roll delta below
        coinsLabel = new JLabel(Strings.coinsDisplay(3));
        coinsLabel.setFont(new Font("Arial", Font.BOLD, 14));
        coinsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.add(wrap(coinsLabel));

        coinsAfterLabel = new JLabel(Strings.coinsAfterNeutral(3));
        coinsAfterLabel.setFont(new Font("Arial", Font.PLAIN, 11));
        coinsAfterLabel.setForeground(new Color(0x888888));
        coinsAfterLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.add(wrap(coinsAfterLabel));

        controls.add(Box.createVerticalStrut(8));

        // Roll input — first die strip (always shown, mandatory selection)
        controls.add(wrap(bold(Strings.diceRollLabel())));
        dieStrip1 = new DiceSelectorPanel(false);
        dieStrip1.setValue(3);  // default to 3
        dieStrip1.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.add(dieStrip1);

        // Second die strip (shown only when player has Bahnhof; optional/deselectable)
        dieStrip2 = new DiceSelectorPanel(true);
        dieStrip2.setAlignmentX(Component.LEFT_ALIGNMENT);
        dieStrip2Wrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        dieStrip2Wrapper.setOpaque(false);
        dieStrip2Wrapper.add(dieStrip2);
        dieStrip2Wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
        dieStrip2Wrapper.setVisible(false);
        controls.add(dieStrip2Wrapper);

        // Doubles (Pasch) checkbox: only shown when player has Bahnhof + Freizeitpark
        doublesCheckBox = new JCheckBox(Strings.doublesCheckbox());
        doublesCheckBox.setFont(new Font("Arial", Font.BOLD, 12));
        doublesCheckBox.setForeground(new Color(0x7030A0));
        doublesCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        doublesCheckBox.setToolTipText(Strings.doublesTooltip());
        doublesCheckBox.setVisible(false);
        controls.add(wrap(doublesCheckBox));

        controls.add(Box.createVerticalStrut(8));

        // Roll preview (compact, inline with turn input)
        controls.add(wrap(bold(Strings.rollOutcomeLabel())));
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
        controls.add(wrap(bold(Strings.purchaseLabel())));
        buyCombo = new JComboBox<>();
        buyCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        buyCombo.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.add(buyCombo);

        controls.add(Box.createVerticalStrut(10));

        // Confirm button
        confirmBtn = new JButton(Strings.confirmTurnBtn());
        confirmBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        confirmBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        confirmBtn.addActionListener(this::onConfirmTurn);
        controls.add(confirmBtn);

        controls.add(Box.createVerticalStrut(4));

        // Undo button
        undoBtn = new JButton(Strings.undoBtn());
        undoBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        undoBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        undoBtn.addActionListener(this::onUndo);
        controls.add(undoBtn);

        controls.add(Box.createVerticalStrut(10));

        // Snapshot button
        JButton snapshotBtn = new JButton(Strings.snapshotBtn());
        snapshotBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        snapshotBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        snapshotBtn.addActionListener(this::onOpenSnapshot);
        controls.add(snapshotBtn);

        controls.add(Box.createVerticalStrut(4));

        // Save / Load buttons
        JButton saveBtn = new JButton(Strings.saveBtn());
        saveBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        saveBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        saveBtn.addActionListener(this::onSave);
        controls.add(saveBtn);

        controls.add(Box.createVerticalStrut(2));

        JButton loadBtn = new JButton(Strings.loadBtn());
        loadBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        loadBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        loadBtn.addActionListener(this::onLoad);
        controls.add(loadBtn);

        controls.add(Box.createVerticalStrut(8));
        controls.add(wrap(bold(Strings.historyLabel())));

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
        panel.setBorder(titledBorder(Strings.centerPanelTitle()));
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

        topCardCostRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        topCardCostRow.setOpaque(false);
        topCardCostRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(topCardCostRow);

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

        topCardEV    = addMetricRow(metricsGrid, Strings.evRoundLabel(), Strings.evRoundTooltip());
        topCardROI   = addMetricRow(metricsGrid, Strings.roiLabel(), Strings.roiTooltip());
        topCardRisk  = addMetricRow(metricsGrid, Strings.p0Label(), Strings.p0Tooltip());
        topCardVar   = addMetricRow(metricsGrid, Strings.varianceLabel(), Strings.varianceTooltip());
        topCardWinProb = addMetricRow(metricsGrid, Strings.winProbLabel(), Strings.winProbTooltip());
        topCardWinProb.setVisible(false);
        // Also hide its label
        ((JLabel) metricsGrid.getComponent(metricsGrid.getComponentCount() - 2)).setVisible(false);

        JPanel metricsWrapper = new JPanel(new BorderLayout());
        metricsWrapper.add(metricsGrid, BorderLayout.NORTH);
        metricsWrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(metricsWrapper);

        panel.add(Box.createVerticalStrut(4));

        // Metric legend — collapsible; always visible by default
        JPanel legendPanel = buildMetricLegend();
        legendPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton legendToggle = new JButton(Strings.metricLegendToggleOpen());
        legendToggle.setFont(new Font("Arial", Font.PLAIN, 11));
        legendToggle.setFocusPainted(false);
        legendToggle.setBorderPainted(false);
        legendToggle.setContentAreaFilled(false);
        legendToggle.setForeground(new Color(0x555555));
        legendToggle.setAlignmentX(Component.LEFT_ALIGNMENT);
        legendToggle.addActionListener(e -> {
            boolean visible = !legendPanel.isVisible();
            legendPanel.setVisible(visible);
            legendToggle.setText(visible ? Strings.metricLegendToggleOpen() : Strings.metricLegendToggleClosed());
        });
        panel.add(wrap(legendToggle));
        panel.add(legendPanel);

        // Baseline win probability
        baselineWinProbLabel = new JLabel(Strings.baselineWinProbLabel());
        baselineWinProbLabel.setFont(new Font("Arial", Font.BOLD, 12));
        baselineWinProbLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        baselineWinProbLabel.setToolTipText(Strings.winProbSoftmaxTooltip());
        JLabel winProbExplain = new JLabel(Strings.winProbSoftmaxExplain());
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
     * Builds a compact legend panel listing all metric abbreviations used in the card details
     * and ranking table, so users can understand the metrics without hovering over labels.
     */
    private static JPanel buildMetricLegend() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(true);
        panel.setBackground(new Color(0xF8F8F8));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xCCCCCC), 1),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        String[][] entries = {
            {Strings.legendEVAbbr(),  Strings.legendEVDesc()},
            {Strings.legendROIAbbr(), Strings.legendROIDesc()},
            {Strings.legendP0Abbr(),  Strings.legendP0Desc()},
            {Strings.legendVarAbbr(), Strings.legendVarDesc()},
            {Strings.legendWinAbbr(), Strings.legendWinDesc()},
        };

        for (String[] entry : entries) {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
            row.setOpaque(false);
            JLabel abbr = new JLabel(entry[0] + ":");
            abbr.setFont(new Font("Arial", Font.BOLD, 10));
            abbr.setPreferredSize(new Dimension(62, 14));
            // HTML wrapping ensures long descriptions don't get clipped in the narrow center panel
            JLabel desc = new JLabel("<html><body style='width:170px'>" + entry[1] + "</body></html>");
            desc.setFont(new Font("Arial", Font.PLAIN, 10));
            desc.setForeground(new Color(0x444444));
            row.add(abbr);
            row.add(desc);
            panel.add(row);
        }

        return panel;
    }

    /** Adds a two-cell metric row (label + value) to the grid panel and returns the value label.
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
        panel.setBorder(titledBorder(Strings.rightPanelTitle()));

        String[] cols = {Strings.colCard(), Strings.colCost(), Strings.colEV(), Strings.colROI(), Strings.colP0(), Strings.colVar()};
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

        toggleWinProbBtn = new JButton(Strings.showWinProbBtn());
        toggleWinProbBtn.addActionListener(this::onToggleWinProb);
        btnBar.add(toggleWinProbBtn);

        deepAnalysisBtn = new JToggleButton(Strings.deepAnalysisBtn());
        deepAnalysisBtn.setToolTipText(Strings.deepAnalysisTooltip());
        deepAnalysisBtn.addActionListener(this::onToggleDeepAnalysis);
        btnBar.add(deepAnalysisBtn);

        // MC sim count spinner (only relevant when deep analysis is on)
        mcSimSpinner = new BoundedSpinner(new SpinnerNumberModel(1000, 100, 10000, 100));
        mcSimSpinner.setPreferredSize(new Dimension(70, 24));
        mcSimSpinner.setToolTipText(Strings.mcSimTooltip());
        mcSimSpinner.setEnabled(false);
        mcSimSpinner.addChangeListener(e -> {
            mcSimCount = (int) mcSimSpinner.getValue();
            rankOpts.mcSimulations = deepAnalysisBtn.isSelected() ? mcSimCount : 0;
        });
        btnBar.add(new JLabel("N:"));
        btnBar.add(mcSimSpinner);

        // Reload button for MC without double-toggling
        mcReloadBtn = new JButton("⟳");
        mcReloadBtn.setToolTipText(Strings.mcReloadTooltip());
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

        // Per-column header tooltips via a custom JTableHeader
        rankTable.setTableHeader(new javax.swing.table.JTableHeader(rankTable.getColumnModel()) {
            @Override
            public String getToolTipText(java.awt.event.MouseEvent e) {
                int col = columnAtPoint(e.getPoint());
                String[] tips = {
                    Strings.colTipCard(),
                    Strings.colTipCost(),
                    Strings.colTipEV(),
                    Strings.colTipROI(),
                    Strings.colTipP0(),
                    Strings.colTipVar(),
                    Strings.colTipWinDelta(),
                };
                if (col >= 0 && col < tips.length) return tips[col];
                return null;
            }
        });

        // Ensure the button bar is never clipped when the window is resized narrow.
        // The bar needs ~430 px (win-prob btn + deep analysis btn + N: + spinner + reload + status + gaps).
        panel.setMinimumSize(new Dimension(430, 0));
        return panel;
    }

    // =========================================================================
    // Event handlers
    // =========================================================================

    private void onConfirmTurn(ActionEvent e) {
        int pi = session.nextPlayerIndex();
        int roll = getCurrentRoll();
        boolean isDoubles = doublesCheckBox.isVisible() && doublesCheckBox.isSelected();

        Project bought = null;
        String selected = (String) buyCombo.getSelectedItem();
        if (selected != null && !selected.equals(Strings.nothingOption())) {
            bought = ProjectLoader.getProject(projectIdFromLabel(selected))
                    .orElse(null);
        }

        try {
            session.applyTurn(new TurnRecord(pi, roll, bought, isDoubles));
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), Strings.invalidTurnTitle(), JOptionPane.ERROR_MESSAGE);
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
            activePlayerLabel.setText(Strings.bonusTurn(pName));
        }

        refreshAll();
    }

    private void onUndo(ActionEvent e) {
        if (session.getHistory().isEmpty()) {
            JOptionPane.showMessageDialog(this, Strings.undoNothingMsg(), Strings.undoTitle(), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            session.undoLastTurn();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), Strings.undoFailedTitle(), JOptionPane.ERROR_MESSAGE);
            return;
        }
        refreshAll();
    }

    private void onToggleDeepAnalysis(ActionEvent e) {
        boolean enabled = deepAnalysisBtn.isSelected();
        mcSimCount = (int) mcSimSpinner.getValue();
        // MC sims are only actually used when BOTH deep analysis is on AND win prob is shown.
        // This prevents computing (and discarding) MC win-prob values that the user can't see.
        rankOpts.mcSimulations = (enabled && showWinProb) ? mcSimCount : 0;
        deepAnalysisBtn.setText(enabled ? Strings.deepAnalysisBtnOn() : Strings.deepAnalysisBtn());
        mcSimSpinner.setEnabled(enabled);
        // Reload button is only active when both deep analysis is on AND win prob is shown
        mcReloadBtn.setEnabled(enabled && showWinProb);
        refreshAll();
    }

    private void onToggleWinProb(ActionEvent e) {
        showWinProb = !showWinProb;
        rankOpts.includeWinProbDelta = showWinProb;
        toggleWinProbBtn.setText(showWinProb ? Strings.hideWinProbBtn() : Strings.showWinProbBtn());
        setWinProbRowVisible(showWinProb);
        // Reload button only enabled when deep analysis is on AND win prob is shown
        mcReloadBtn.setEnabled(deepAnalysisBtn.isSelected() && showWinProb);

        if (!showWinProb) {
            // Just rebuild table without the column — no recompute needed
            rankOpts.mcSimulations = 0;
            rebuildTable();
        } else {
            // Now that win prob is shown, apply the correct MC sim count (if deep analysis is on)
            rankOpts.mcSimulations = deepAnalysisBtn.isSelected() ? mcSimCount : 0;
            // Always recompute when showing: either analytical or MC, with includeWinProbDelta=true
            refreshAll();
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
        fc.setDialogTitle(Strings.saveDialogTitle());
        fc.setFileFilter(new FileNameExtensionFilter(Strings.saveFileFilter(), "mkoro"));
        fc.setSelectedFile(new java.io.File("game.mkoro"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = fc.getSelectedFile().toPath();
        if (!path.toString().endsWith(".mkoro")) {
            path = path.resolveSibling(path.getFileName() + ".mkoro");
        }
        try {
            session.save(path);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, Strings.saveErrorMsg(ex.getMessage()),
                    Strings.saveErrorTitle(), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onLoad(ActionEvent e) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(Strings.loadDialogTitle());
        fc.setFileFilter(new FileNameExtensionFilter(Strings.loadFileFilter(), "mkoro"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = fc.getSelectedFile().toPath();
        try {
            GameSession loaded = GameSession.load(path);
            replaceSession(loaded);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, Strings.loadErrorMsg(ex.getMessage()),
                    Strings.loadErrorTitle(), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showGameOver(String winnerName) {
        confirmBtn.setEnabled(false);
        undoBtn.setEnabled(true);

        topCardColorBar.setBackground(CARD_COLORS[4]);
        topCardName.setText(Strings.gameOver(winnerName));
        topCardColorTag.setText(Strings.gpTag());
        topCardColorTag.setBackground(new Color(0xFFF5B0));
        topCardCostRow.removeAll();
        topCardDesc.setText(Strings.gameOverDesc());
        topCardEV.setText("—");
        topCardROI.setText("—");
        topCardRisk.setText("—");
        topCardVar.setText("—");
        topCardWinProb.setText("—");
        setWinProbRowVisible(false); // hide win-prob row consistently (uses the shared helper)
        baselineWinProbLabel.setText(Strings.baselineWinProbFmt(100.0));
        topCardNote.setText(Strings.gameOverNote());

        tableModel.setRowCount(0);
        statusLabel.setText(Strings.gameOverStatus());
    }

    // =========================================================================
    // Data refresh
    // =========================================================================

    /**
     * Returns the current roll value from the die strip(s).
     * Strip 1 is always mandatory (value 1–6).
     * Strip 2 is optional (Bahnhof only): contributes its value only when a die is selected.
     */
    private int getCurrentRoll() {
        int v1 = dieStrip1.getValue();
        if (v1 < 1) v1 = 1;  // safety; strip1 is never deselectable
        int v2 = dieStrip2Wrapper.isVisible() ? dieStrip2.getValue() : -1;
        return v2 >= 1 ? v1 + v2 : v1;
    }

    /**
     * Returns a copy of the current game state with coin deltas from the current roll
     * already applied. This is the state the active player actually buys from.
     */
    private GameState postRollState() {
        int pi   = session.nextPlayerIndex();
        int roll = getCurrentRoll();
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

        activePlayerLabel.setText(Strings.playerTurn(activePlayer.getName()));
        if (session.isBonusTurnPending()) {
            activePlayerLabel.setText(Strings.bonusTurn(activePlayer.getName()));
        } else {
            activePlayerLabel.setText(Strings.playerTurn(activePlayer.getName()));
        }
        coinsLabel.setText(Strings.coinsDisplay(activePlayer.getCoins()));

        updateRollInput(activePlayer);

        GameState postRoll = postRollState();
        Player postRollPlayer = postRoll.getPlayers()[pi];
        int postRollCoins = postRollPlayer.getCoins();
        updateCoinsAfterLabel(activePlayer.getCoins(), postRollCoins);

        rebuildBuyCombo(pi, postRollPlayer, postRoll);

        refreshHistory();
        undoBtn.setEnabled(!session.getHistory().isEmpty());

        refreshRollPreview();

        double baselineWinProb = ProbabilityCalc.computeBaselineWinProb(postRoll, pi);
        baselineWinProbLabel.setText(Strings.baselineWinProbFmt(baselineWinProb * 100));

        if (rankOpts.mcSimulations > 0) {
            statusLabel.setText(Strings.mcRunning());
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
                    statusLabel.setText(Strings.mcDone(rankOpts.mcSimulations));
                    confirmBtn.setEnabled(true);
                    mcReloadBtn.setEnabled(deepAnalysisBtn.isSelected() && showWinProb);
                    rebuildTable();
                    if (!lastRanking.isEmpty()) {
                        populateCenter(lastRanking.get(0));
                        if (rankTable.getRowCount() > 0) rankTable.setRowSelectionInterval(0, 0);
                    } else {
                        clearCenter(Strings.noAffordableCards());
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
                clearCenter(Strings.noAffordableCards());
            }
        }
    }

    /**
     * Updates the roll input strips for the given active player.
     * Shows the second die strip when the player has Bahnhof (can roll 2d6).
     * Shows the doubles checkbox when the player has both Bahnhof and Freizeitpark.
     */
    private void updateRollInput(Player activePlayer) {
        boolean hasBahnhof = activePlayer.hasProject("bahnhof");

        // Show/hide second die strip
        boolean strip2WasVisible = dieStrip2Wrapper.isVisible();
        dieStrip2Wrapper.setVisible(hasBahnhof);
        if (hasBahnhof && !strip2WasVisible) {
            // Just gained Bahnhof: default second die to 4 (so total = d1 + 4 = 3+4 = 7)
            if (dieStrip2.getValue() < 1) dieStrip2.setValue(4);
        } else if (!hasBahnhof) {
            // Lost Bahnhof access: ensure strip1 still has a valid value
            if (dieStrip1.getValue() < 1) dieStrip1.setValue(3);
        }

        // Doubles checkbox: only when player can roll 2 dice AND has Freizeitpark
        boolean canGetBonus = hasBahnhof && activePlayer.hasProject("freizeitpark");
        doublesCheckBox.setVisible(canGetBonus);
        if (!canGetBonus) doublesCheckBox.setSelected(false);
    }

    /**
     * Computes and displays per-player coin deltas for the current roll value.
     */
    private void refreshRollPreview() {
        if (rollPreviewPanel == null) return;
        int roll = getCurrentRoll();
        int pi = session.nextPlayerIndex();
        GameState state = session.getState();
        int[] deltas = ProbabilityCalc.computeAllDeltasForRoll(state, pi, roll);
        String[] names = session.getPlayerNames();

        rollPreviewPanel.removeAll();
        for (int i = 0; i < deltas.length; i++) {
            String sign = deltas[i] >= 0 ? "+" : "";
            JLabel lbl = new JLabel(names[i] + ": " + sign + deltas[i] + " " + Strings.coinsUnit());
            lbl.setFont(new Font("Monospaced", Font.PLAIN, 11));
            if (deltas[i] > 0) lbl.setForeground(new Color(0x007700));
            else if (deltas[i] < 0) lbl.setForeground(new Color(0xAA0000));
            rollPreviewPanel.add(lbl);
        }
        rollPreviewPanel.revalidate();
        rollPreviewPanel.repaint();
    }

    /**
     * Refreshes all roll-dependent UI elements when a die strip selection changes.
     */
    private void refreshAfterRollChange() {
        refreshRollPreview();

        int pi = session.nextPlayerIndex();
        GameState postRoll = postRollState();
        Player preRollPlayer  = session.getState().getPlayers()[pi];
        Player postRollPlayer = postRoll.getPlayers()[pi];
        int preCoins  = preRollPlayer.getCoins();
        int postCoins = postRollPlayer.getCoins();
        updateCoinsAfterLabel(preCoins, postCoins);

        rebuildBuyCombo(pi, postRollPlayer, postRoll);

        double baselineWinProb = ProbabilityCalc.computeBaselineWinProb(postRoll, pi);
        baselineWinProbLabel.setText(Strings.baselineWinProbFmt(baselineWinProb * 100));

        if (rankOpts.mcSimulations == 0) {
            lastRanking = ProbabilityCalc.rankPurchasableProjects(postRoll, pi, rankOpts);
            rebuildTable();
            if (!lastRanking.isEmpty()) {
                populateCenter(lastRanking.get(0));
                if (rankTable.getRowCount() > 0) rankTable.setRowSelectionInterval(0, 0);
            } else {
                clearCenter(Strings.noAffordableCards());
            }
        }
    }

    private void rebuildBuyCombo(int pi, Player postRollPlayer, GameState postRoll) {
        buyCombo.removeAllItems();
        buyCombo.addItem(Strings.nothingOption());
        int coins = postRollPlayer.getCoins();

        for (Project p : postRoll.getUnbuilt_projects()) {
            if (p.getCost() <= coins) buyCombo.addItem(labelForProject(p));
        }

        for (Project p : ProjectLoader.getAllProjects()) {
            if (p.isIs_grossprojekt() && !postRollPlayer.hasProject(p.getId()) && p.getCost() <= coins) {
                buyCombo.addItem(labelForProject(p) + " " + Strings.gpTag());
            }
        }
    }

    private void rebuildTable() {
        // Preserve the user's current sort order across rebuilds
        RowSorter<?> existingSorter = rankTable.getRowSorter();
        java.util.List<? extends RowSorter.SortKey> savedSortKeys =
                (existingSorter != null) ? existingSorter.getSortKeys() : null;

        String[] cols = showWinProb
                ? new String[]{Strings.colCard(), Strings.colCost(), Strings.colEV(), Strings.colROI(), Strings.colP0(), Strings.colVar(), Strings.colWinDelta()}
                : new String[]{Strings.colCard(), Strings.colCost(), Strings.colEV(), Strings.colROI(), Strings.colP0(), Strings.colVar()};

        tableModel.setColumnIdentifiers(cols);
        tableModel.setRowCount(0);

        for (RankEntry e : lastRanking) {
            String cardLabel = e.project.isIs_grossprojekt()
                    ? e.project.getLocalizedName() + " " + Strings.gpTag()
                    : e.project.getLocalizedName();
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

        // Re-apply renderers and widths — col 1 (cost) neutral, cols 2..N metric-aware
        rankTable.getColumnModel().getColumn(0).setCellRenderer(new CardNameRenderer());
        rankTable.getColumnModel().getColumn(0).setPreferredWidth(120);
        rankTable.getColumnModel().getColumn(1).setCellRenderer(new NumericCellRenderer(MetricColorScheme.COST));
        rankTable.getColumnModel().getColumn(1).setPreferredWidth(52);
        for (int c = 2; c < cols.length; c++) {
            MetricColorScheme scheme = MetricColorScheme.TABLE_ORDER[c - 2];
            rankTable.getColumnModel().getColumn(c).setCellRenderer(new NumericCellRenderer(scheme));
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
        topCardName.setText(p.getLocalizedName());

        String colorStr = Strings.colorLabel(p.getColor());
        topCardColorTag.setText(colorStr);
        topCardColorTag.setBackground(colorForCard(p.getColor(), false));
        topCardColorTag.setForeground(colorForCard(p.getColor(), true).darker());

        topCardCostRow.removeAll();
        JLabel costLabel = new JLabel(Strings.costPrefix(p.getCost()));
        costLabel.setFont(new Font("Arial", Font.PLAIN, 13));
        topCardCostRow.add(costLabel);
        buildActivationDice(topCardCostRow, p);
        String desc = p.getLocalizedDescription();
        topCardDesc.setText("<html><i>" + (desc != null && !desc.isEmpty() ? desc : "—") + "</i></html>");

        applyMetricColor(topCardEV,    MetricColorScheme.EV,            entry.evPerRound);
        applyMetricColor(topCardROI,   MetricColorScheme.ROI,           entry.roiOverHorizon);
        applyMetricColor(topCardRisk,  MetricColorScheme.P0,            entry.probNoIncomeOwnTurn);
        applyMetricColor(topCardVar,   MetricColorScheme.VARIANCE,      entry.variance);
        applyMetricColor(topCardWinProb, MetricColorScheme.WIN_PROB_DELTA, entry.winProbDelta);
        topCardNote.setText("<html><i>" + buildNote(entry) + "</i></html>");
        topCardColorBar.setBackground(colorForCard(p));
        // Always re-apply visibility to keep it in sync with the global toggle
        setWinProbRowVisible(showWinProb);
    }

    /** Sets the text and background/foreground tint of a metric label using the given scheme. */
    private static void applyMetricColor(JLabel label, MetricColorScheme scheme, double value) {
        label.setText(fmt2(value));
        Color bg = scheme.backgroundFor(value);
        Color fg = scheme.foregroundFor(value);
        label.setOpaque(bg != null);
        label.setBackground(bg != null ? bg : label.getParent() != null ? label.getParent().getBackground() : Color.WHITE);
        label.setForeground(fg != null ? fg : Color.BLACK);
    }

    private void clearCenter(String message) {
        topCardName.setText("—");
        topCardColorTag.setText("");
        topCardColorTag.setBackground(null);
        topCardCostRow.removeAll();
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
        int nPlayers = session.getState().getPlayers().length;

        for (int i = history.size() - 1; i >= 0; i--) {
            TurnRecord t = history.get(i);
            Color pColor = playerIndexColor(t.playerIndex);
            TurnEntryPanel entry = new TurnEntryPanel(t, names, nPlayers, pColor, i % 2 == 0);
            entry.setAlignmentX(Component.LEFT_ALIGNMENT);
            historyPanel.add(entry);
        }

        historyPanel.revalidate();
        historyPanel.repaint();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String buildNote(RankEntry e) {
        if (e.notes != null && !e.notes.isEmpty()) return e.notes;
        String name = e.project.getLocalizedName();
        if (e.roiOverHorizon > 0) {
            return name + ": " + fmt2(e.evPerRound) + " " + Strings.coinsUnit() + "/round, ROI " + fmt2(e.roiOverHorizon);
        }
        return name + " " + Strings.costNotRecouped(10);
    }

    private static String fmt2(double v) {
        return String.format("%.2f", v);
    }

    private static String labelForProject(Project p) {
        return p.getLocalizedName() + " (" + p.getCost() + ")";
    }

    /**
     * Extracts the project from a combo label like "Weizenfeld (1)" or "Wheat Field (1) [GP]".
     * Uses a reverse lookup by localized name so it works in both DE and EN.
     */
    private static Project projectFromLabel(String label) {
        String clean = label.replace(" " + Strings.gpTag(), "");
        int paren = clean.indexOf(" (");
        String localizedName = paren >= 0 ? clean.substring(0, paren) : clean;
        for (Project p : ProjectLoader.getAllProjects()) {
            if (p.getLocalizedName().equals(localizedName)) return p;
        }
        return null;
    }

    /** Extracts the project ID from a combo label like "Weizenfeld (1)" or "Bahnhof (4) [GP]" */
    private static String projectIdFromLabel(String label) {
        Project p = projectFromLabel(label);
        return p != null ? p.getId() : label.replace(" " + Strings.gpTag(), "").toLowerCase();
    }

    /**
     * Appends activation dice faces (or a text label for landmarks) to {@code row}.
     * For regular cards: a separator label " · " followed by one DiceFacePanel per activation value.
     * For Großprojekte: a text label " · Großprojekt".
     */
    private static void buildActivationDice(JPanel row, Project p) {
        if (p.isIs_grossprojekt()) {
            JLabel lbl = new JLabel(" · " + Strings.grossProjekt());
            lbl.setFont(new Font("Arial", Font.ITALIC, 12));
            lbl.setForeground(new Color(0x555555));
            row.add(lbl);
            return;
        }
        int[] dice = p.getDice_activation();
        if (dice == null || dice.length == 0) return;
        JLabel sep = new JLabel(" ·");
        sep.setFont(new Font("Arial", Font.PLAIN, 12));
        row.add(sep);
        for (int v : dice) {
            row.add(new DiceFacePanel(v, 20));
        }
        row.revalidate();
        row.repaint();
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

    /**
     * Updates the post-roll coins label below the main coin display.
     * Always visible to avoid layout shift; shows a neutral "±0" placeholder when the roll
     * has no effect, and green/red delta when coins change.
     */
    private void updateCoinsAfterLabel(int preCoins, int postCoins) {
        int delta = postCoins - preCoins;
        if (delta != 0) {
            coinsAfterLabel.setText(Strings.coinsAfterDelta(postCoins, delta));
            coinsAfterLabel.setForeground(delta > 0 ? new Color(0x007700) : new Color(0xAA0000));
        } else {
            coinsAfterLabel.setText(Strings.coinsAfterNeutral(postCoins));
            coinsAfterLabel.setForeground(new Color(0x888888));
        }
        coinsAfterLabel.setVisible(true);
    }

    /**
     * Loads and scales an image from classpath resources.
     * Returns null if the resource is unavailable so callers can degrade gracefully.
     */
    private static ImageIcon loadScaledIcon(String resourcePath, int size) {
        try (InputStream is = MainWindow.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (is == null) return null;
            BufferedImage img = ImageIO.read(is);
            Image scaled = img.getScaledInstance(size, size, Image.SCALE_SMOOTH);
            return new ImageIcon(scaled);
        } catch (Exception e) {
            return null;
        }
    }

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
                // Strip [GP] suffix and reverse-lookup by localized name to find color
                String clean = label.replace(" " + Strings.gpTag(), "");
                int paren = clean.indexOf(" (");
                String localizedName = paren >= 0 ? clean.substring(0, paren) : clean;
                Project p = null;
                for (Project proj : ProjectLoader.getAllProjects()) {
                    if (proj.getLocalizedName().equals(localizedName)) { p = proj; break; }
                }
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
     * Renders numeric table cells with 2-decimal formatting and metric-aware colour coding.
     * Each instance is bound to a {@link MetricColorScheme} so P(0) and Variance are
     * coloured with inverted logic (lower = better), while EV, ROI and Win Δ use normal
     * (higher = better) logic.
     */
    private static class NumericCellRenderer extends DefaultTableCellRenderer {
        private final MetricColorScheme scheme;

        NumericCellRenderer(MetricColorScheme scheme) {
            this.scheme = scheme;
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
                    Color bg = scheme.backgroundFor(d);
                    Color fg = scheme.foregroundFor(d);
                    setBackground(bg != null ? bg : table.getBackground());
                    setForeground(fg != null ? fg : table.getForeground());
                } catch (NumberFormatException ignored) {
                    setBackground(table.getBackground());
                    setForeground(table.getForeground());
                }
            } else if (isSelected) {
                setBackground(table.getSelectionBackground());
                setForeground(table.getSelectionForeground());
            }
            return this;
        }
    }
}
