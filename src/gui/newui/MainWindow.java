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
    private JPanel incomeMatrixPanel;   // collapsible income-by-roll grid
    private JButton incomeMatrixToggleBtn;

    // ---- center panel components ----
    private JLabel topCardName;
    private JPanel topCardCostRow;  // cost + activation dice faces
    private JLabel topCardColorTag;
    private TriggerModePanel topCardTrigger;
    private JLabel topCardDesc;
    private JLabel topCardEV;
    private JLabel topCardROI;
    private JLabel topCardRisk;
    private JLabel topCardVar;
    private JLabel topCardWinProb;
    private JLabel topCardPortfolioDelta;
    /** Per-metric rank labels in the 3-column metrics grid: [EV, ROI, P0, VAR, WIN, PDELTA]. */
    private JLabel[] topCardMetricRank = new JLabel[6];
    private JLabel topCardNote;
    private JLabel topCardRank;  // "#X / Y affordable · #Z / N total"
    private JPanel topCardColorBar;
    private JLabel baselineWinProbLabel;

    // ---- right panel components ----
    private DefaultTableModel tableModel;
    private DefaultTableModel tableModelUnaffordable;
    private DefaultTableModel tableModelAll;
    private JTable rankTable;
    private JTable rankTableUnaffordable;
    private JTable rankTableAll;
    private JTabbedPane rankTabs;
    private JPanel assistantPanel;
    private JToggleButton deepAnalysisBtn;
    private JLabel statusLabel;
    private JSpinner mcSimSpinner;
    private JSpinner mcTempSpinner;
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
        // Win probability and MC analysis are always on
        rankOpts.includeWinProbDelta = true;
        rankOpts.mcSimulations = mcSimCount;
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

        JMenu toolsMenu = new JMenu(Strings.menuTools());
        JMenuItem labelingItem = new JMenuItem(Strings.menuLabelingWindow());
        labelingItem.addActionListener(e -> new LabelingWindow());
        toolsMenu.add(labelingItem);
        bar.add(toolsMenu);

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

        controls.add(Box.createVerticalStrut(4));

        // Income matrix — collapsible, hidden by default
        incomeMatrixToggleBtn = new JButton(Strings.incomeMatrixToggleShow());
        incomeMatrixToggleBtn.setFont(new Font("Arial", Font.PLAIN, 11));
        incomeMatrixToggleBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        incomeMatrixToggleBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        incomeMatrixPanel = new JPanel();
        incomeMatrixPanel.setLayout(new BoxLayout(incomeMatrixPanel, BoxLayout.Y_AXIS));
        incomeMatrixPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        incomeMatrixPanel.setVisible(false);
        incomeMatrixToggleBtn.addActionListener(e -> {
            boolean nowVisible = !incomeMatrixPanel.isVisible();
            incomeMatrixPanel.setVisible(nowVisible);
            incomeMatrixToggleBtn.setText(nowVisible
                    ? Strings.incomeMatrixToggleHide()
                    : Strings.incomeMatrixToggleShow());
            if (nowVisible) refreshIncomeMatrix();
        });
        controls.add(incomeMatrixToggleBtn);
        controls.add(incomeMatrixPanel);

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
        topCardTrigger = new TriggerModePanel();
        nameRow.add(topCardTrigger);
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

        // Metrics in three-column grid: label | value | rank (#X/N)
        JPanel metricsGrid = new JPanel(new GridLayout(0, 3, 4, 3));
        metricsGrid.setAlignmentX(Component.LEFT_ALIGNMENT);

        topCardEV    = addMetricRow(metricsGrid, Strings.evRoundLabel(), Strings.evRoundTooltip(),    0);
        topCardROI   = addMetricRow(metricsGrid, Strings.roiLabel(), Strings.roiTooltip(),             1);
        topCardRisk  = addMetricRow(metricsGrid, Strings.p0Label(), Strings.p0Tooltip(),              2);
        topCardVar   = addMetricRow(metricsGrid, Strings.varianceLabel(), Strings.varianceTooltip(),  3);
        topCardWinProb = addMetricRow(metricsGrid, Strings.winProbLabel(), Strings.winProbTooltip(),  4);
        topCardPortfolioDelta = addMetricRow(metricsGrid, Strings.portfolioDeltaLabel(), Strings.portfolioDeltaTooltip(), 5);

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

        topCardRank = new JLabel("—");
        topCardRank.setFont(new Font("Arial", Font.ITALIC, 10));
        topCardRank.setForeground(new Color(0x555555));
        topCardRank.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(wrap(topCardRank));

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

    /** Adds a three-cell metric row (label | value | rank "#X/N") to the grid panel.
     * Returns the value label; the rank label is stored in {@code topCardMetricRank[rankIdx]}.
     */
    private JLabel addMetricRow(JPanel grid, String labelText, String tooltip, int rankIdx) {
        JLabel lbl = new JLabel(labelText);
        lbl.setFont(new Font("Arial", Font.BOLD, 12));
        lbl.setToolTipText(tooltip);
        JLabel val = new JLabel("—");
        val.setFont(MONO_FONT);
        val.setToolTipText(tooltip);
        JLabel rank = new JLabel("—");
        rank.setFont(new Font("Arial", Font.PLAIN, 10));
        rank.setForeground(new Color(0x777777));
        topCardMetricRank[rankIdx] = rank;
        grid.add(lbl);
        grid.add(val);
        grid.add(rank);
        return val;
    }

    // ---- Right panel: full ranking table ----

    private JPanel buildRightPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(titledBorder(Strings.rightPanelTitle()));

        // Three tabs: Affordable / Not Affordable / All
        rankTabs = new JTabbedPane();
        rankTable             = buildRankTable();
        rankTableUnaffordable = buildRankTable();
        rankTableAll          = buildRankTable();

        // Selection listeners: clicking any row in any tab updates Card Details
        rankTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) onTableSelect(rankTable, tableModel);
        });
        rankTableUnaffordable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) onTableSelect(rankTableUnaffordable, tableModelUnaffordable);
        });
        rankTableAll.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) onTableSelect(rankTableAll, tableModelAll);
        });

        rankTabs.addTab(Strings.tabAffordable(),    new JScrollPane(rankTable));
        rankTabs.addTab(Strings.tabNotAffordable(), new JScrollPane(rankTableUnaffordable));
        rankTabs.addTab(Strings.tabAll(),           new JScrollPane(rankTableAll));

        assistantPanel = new JPanel();
        assistantPanel.setLayout(new BoxLayout(assistantPanel, BoxLayout.Y_AXIS));
        JScrollPane assistantScroll = new JScrollPane(assistantPanel);
        assistantScroll.getVerticalScrollBar().setUnitIncrement(16);
        rankTabs.addTab(Strings.tabAssistant(), assistantScroll);

        panel.add(rankTabs, BorderLayout.CENTER);

        // Button bar at bottom
        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));

        deepAnalysisBtn = new JToggleButton(Strings.deepAnalysisBtnOn());
        deepAnalysisBtn.setSelected(true);
        deepAnalysisBtn.setToolTipText(Strings.deepAnalysisTooltip());
        deepAnalysisBtn.addActionListener(this::onToggleDeepAnalysis);
        btnBar.add(deepAnalysisBtn);

        // MC sim count spinner
        mcSimSpinner = new BoundedSpinner(new SpinnerNumberModel(1000, 100, 10000, 100));
        mcSimSpinner.setPreferredSize(new Dimension(70, 24));
        mcSimSpinner.setToolTipText(Strings.mcSimTooltip());
        mcSimSpinner.setEnabled(true);
        mcSimSpinner.addChangeListener(e -> {
            mcSimCount = (int) mcSimSpinner.getValue();
            rankOpts.mcSimulations = deepAnalysisBtn.isSelected() ? mcSimCount : 0;
        });
        btnBar.add(new JLabel("N:"));
        btnBar.add(mcSimSpinner);

        // Boltzmann temperature spinner (T=0 greedy, T=0.7 recommended)
        mcTempSpinner = new BoundedSpinner(new SpinnerNumberModel(0.0, 0.0, 5.0, 0.1));
        ((JSpinner.NumberEditor) mcTempSpinner.getEditor()).getFormat().setMaximumFractionDigits(1);
        mcTempSpinner.setPreferredSize(new Dimension(55, 24));
        mcTempSpinner.setToolTipText(Strings.mcTempTooltip());
        mcTempSpinner.addChangeListener(e ->
            rankOpts.mcExplorationTemp = (double) mcTempSpinner.getValue());
        btnBar.add(new JLabel(Strings.mcTempLabel()));
        btnBar.add(mcTempSpinner);

        // Reload button for MC
        mcReloadBtn = new JButton("Reload MC");
        mcReloadBtn.setToolTipText(Strings.mcReloadTooltip());
        mcReloadBtn.setEnabled(true);
        mcReloadBtn.addActionListener(e -> {
            if (deepAnalysisBtn.isSelected()) {
                refreshAll();
            }
        });
        btnBar.add(mcReloadBtn);

        statusLabel = new JLabel("");
        statusLabel.setFont(new Font("Arial", Font.ITALIC, 11));
        btnBar.add(statusLabel);

        panel.add(btnBar, BorderLayout.SOUTH);

        // Ensure the button bar is never clipped when the window is resized narrow.
        panel.setMinimumSize(new Dimension(430, 0));
        return panel;
    }

    /** Creates a new JTable with the standard rank table settings but no model yet. */
    private JTable buildRankTable() {
        String[] cols = {Strings.colCard(), Strings.colCost(), Strings.colEV(), Strings.colROI(), Strings.colP0(), Strings.colVar()};
        DefaultTableModel model = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
            @Override public Class<?> getColumnClass(int c) {
                return c == 0 ? String.class : Double.class;
            }
        };
        // Assign model to the correct field based on which call this is
        // (models assigned by caller via setModel; use a local reference here)
        if (tableModel == null)             tableModel = model;
        else if (tableModelUnaffordable == null) tableModelUnaffordable = model;
        else                                tableModelAll = model;

        JTable table = new JTable(model);
        table.setFont(MONO_FONT);
        table.setRowHeight(22);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);
        sorter.setComparator(0, java.util.Comparator.naturalOrder());
        for (int c = 1; c <= 5; c++) {
            sorter.setComparator(c, java.util.Comparator.comparingDouble(o -> (Double) o));
        }
        java.util.List<RowSorter.SortKey> sortKeys = new java.util.ArrayList<>();
        sortKeys.add(new RowSorter.SortKey(3, SortOrder.DESCENDING));
        sorter.setSortKeys(sortKeys);

        // Per-column header tooltips
        table.setTableHeader(new javax.swing.table.JTableHeader(table.getColumnModel()) {
            @Override
            public String getToolTipText(java.awt.event.MouseEvent e) {
                int col = columnAtPoint(e.getPoint());
                String[] tips = {
                    Strings.colTipCard(), Strings.colTipCost(), Strings.colTipEV(),
                    Strings.colTipROI(), Strings.colTipP0(), Strings.colTipVar(),
                    Strings.colTipWinDelta(),
                };
                if (col >= 0 && col < tips.length) return tips[col];
                return null;
            }
        });

        return table;
    }

    // =========================================================================
    // Event handlers
    // =========================================================================

    /** Selects the first affordable entry in the affordable tab, or clears Card Details if none. */
    private void selectFirstAffordable() {
        RankEntry first = null;
        for (RankEntry e : lastRanking) {
            if (e.affordable) { first = e; break; }
        }
        if (first != null) {
            populateCenter(first);
            if (rankTable.getRowCount() > 0) rankTable.setRowSelectionInterval(0, 0);
        } else {
            clearCenter(Strings.noAffordableCardsTab());
        }
    }

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

        // --- Bürohaus swap dialog ---
        // After the turn's coin income is applied, offer the player a chance to swap cards
        // if they own bürohaus and rolled a 6 (bürohaus fires on lila/roll=6, own turn only).
        if (roll == 6 && session.getState().getPlayers()[pi].hasProject("bürohaus")) {
            String note = ProbabilityCalc.bürohausSwapNote(session.getState(), pi);
            if (note != null) {
                double ev = ProbabilityCalc.bürohausSwapEV(session.getState(), pi);
                String msg = Strings.bürohausSwapPrompt(note, ev);
                int choice = JOptionPane.showConfirmDialog(this, msg,
                        Strings.bürohausSwapTitle(), JOptionPane.YES_NO_OPTION);
                if (choice == JOptionPane.YES_OPTION) {
                    session.applyBürohausSwap(pi);
                }
            }
        }

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
        rankOpts.mcSimulations = enabled ? mcSimCount : 0;
        deepAnalysisBtn.setText(enabled ? Strings.deepAnalysisBtnOn() : Strings.deepAnalysisBtn());
        mcSimSpinner.setEnabled(enabled);
        mcReloadBtn.setEnabled(enabled);
        refreshAll();
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

    private void onTableSelect(JTable table, DefaultTableModel model) {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) return;
        int modelRow = table.convertRowIndexToModel(viewRow);
        // Find the matching RankEntry by card name stored in column 0
        String label = (String) model.getValueAt(modelRow, 0);
        for (RankEntry entry : lastRanking) {
            if (entry.isWaitEntry()) {
                if (Strings.waitLabel().equals(label)) {
                    // Show the wait-entry notes in the center panel header area
                    clearCenter("");
                    topCardName.setText(Strings.waitLabel());
                    if (entry.notes != null) topCardDesc.setText(entry.notes);
                    for (JLabel r : topCardMetricRank) if (r != null) r.setText("");
                    return;
                }
                continue;
            }
            String entryLabel = entry.project.isIs_grossprojekt()
                    ? entry.project.getLocalizedName() + " " + Strings.gpTag()
                    : entry.project.getLocalizedName();
            if (entryLabel.equals(label)) {
                populateCenter(entry);
                return;
            }
        }
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
        topCardPortfolioDelta.setText("—");
        for (JLabel r : topCardMetricRank) if (r != null) r.setText("—");
        setWinProbRowVisible(false); // hide win-prob row consistently (uses the shared helper)
        baselineWinProbLabel.setText(Strings.baselineWinProbFmt(100.0));
        topCardNote.setText(Strings.gameOverNote());

        tableModel.setRowCount(0);
        tableModelUnaffordable.setRowCount(0);
        tableModelAll.setRowCount(0);
        assistantPanel.removeAll();
        assistantPanel.revalidate();
        assistantPanel.repaint();
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
        rankOpts.turnsElapsed = session.getEffectiveTurnCount();
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
        refreshIncomeMatrix();

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
                    return ProbabilityCalc.rankAllProjects(snapState, snapPi, rankOpts);
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
                    mcReloadBtn.setEnabled(deepAnalysisBtn.isSelected());
                    rebuildTable();
                    selectFirstAffordable();
                }
            };
            worker.execute();
        } else {
            statusLabel.setText("");
            lastRanking = ProbabilityCalc.rankAllProjects(postRoll, pi, rankOpts);
            rebuildTable();
            selectFirstAffordable();
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
     * Rebuilds the income matrix: a grid showing coin delta per roll (1–12) per player.
     * Only called when the panel is visible (lazy update).
     */
    private void refreshIncomeMatrix() {
        if (incomeMatrixPanel == null || !incomeMatrixPanel.isVisible()) return;
        int pi = session.nextPlayerIndex();
        GameState state = session.getState();
        String[] names = session.getPlayerNames();
        int n = names.length;

        // Determine max roll: 12 if active player has (or can use) 2d6, else 6
        int maxRoll = hasBahnhof(state, pi) ? 12 : 6;

        // Build as a JTable with columns: Roll | Player1 | Player2 | ...
        String[] columns = new String[1 + n];
        columns[0] = Strings.incomeMatrixRollHeader();
        System.arraycopy(names, 0, columns, 1, n);

        Object[][] data = new Object[maxRoll][1 + n];
        for (int roll = 1; roll <= maxRoll; roll++) {
            int[] deltas = ProbabilityCalc.computeAllDeltasForRoll(state, pi, roll);
            data[roll - 1][0] = roll;
            for (int p = 0; p < n; p++) {
                String sign = deltas[p] > 0 ? "+" : "";
                data[roll - 1][1 + p] = sign + deltas[p];
            }
        }

        javax.swing.table.DefaultTableModel model =
                new javax.swing.table.DefaultTableModel(data, columns) {
                    @Override public boolean isCellEditable(int r, int c) { return false; }
                };
        JTable table = new JTable(model);
        table.setFont(new Font("Monospaced", Font.PLAIN, 11));
        table.getTableHeader().setFont(new Font("Arial", Font.BOLD, 11));
        table.setRowHeight(16);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        // Color cells: positive green, negative red
        javax.swing.table.DefaultTableCellRenderer cellRenderer =
                new javax.swing.table.DefaultTableCellRenderer() {
                    @Override
                    public java.awt.Component getTableCellRendererComponent(
                            JTable tbl, Object val, boolean sel, boolean foc, int row, int col) {
                        super.getTableCellRendererComponent(tbl, val, sel, foc, row, col);
                        setHorizontalAlignment(col == 0 ? CENTER : RIGHT);
                        if (col > 0 && val != null) {
                            String v = val.toString().trim();
                            if (v.startsWith("+") && !v.equals("+0"))
                                setForeground(new Color(0x006600));
                            else if (v.startsWith("-"))
                                setForeground(new Color(0xAA0000));
                            else
                                setForeground(Color.BLACK);
                        } else {
                            setForeground(Color.BLACK);
                        }
                        return this;
                    }
                };
        for (int c = 0; c < table.getColumnCount(); c++) table.getColumnModel().getColumn(c).setCellRenderer(cellRenderer);
        // Fix column widths
        table.getColumnModel().getColumn(0).setMaxWidth(36);
        int colW = 46;
        for (int c = 1; c <= n; c++) table.getColumnModel().getColumn(c).setPreferredWidth(colW);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        int tableH = (maxRoll + 1) * 17 + 4;
        scroll.setPreferredSize(new Dimension(Integer.MAX_VALUE, tableH));
        scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, tableH + 4));
        scroll.setBorder(BorderFactory.createLineBorder(new Color(0xCCCCCC)));

        incomeMatrixPanel.removeAll();
        incomeMatrixPanel.add(scroll);
        incomeMatrixPanel.revalidate();
        incomeMatrixPanel.repaint();
    }

    /** Returns true if the player at index pi currently owns Bahnhof. */
    private static boolean hasBahnhof(GameState state, int pi) {
        return state.getPlayers()[pi].hasProject("bahnhof");
    }


    /**
     * Refreshes all roll-dependent UI elements when a die strip selection changes.
     * Always re-ranks using the analytical engine (not MC) so the response is instant.
     * Preserves the current buy selection if the selected card is still affordable.
     */
    private void refreshAfterRollChange() {
        refreshRollPreview();
        refreshIncomeMatrix();

        int pi = session.nextPlayerIndex();
        rankOpts.turnsElapsed = session.getEffectiveTurnCount();
        GameState postRoll = postRollState();
        Player preRollPlayer  = session.getState().getPlayers()[pi];
        Player postRollPlayer = postRoll.getPlayers()[pi];
        int preCoins  = preRollPlayer.getCoins();
        int postCoins = postRollPlayer.getCoins();
        updateCoinsAfterLabel(preCoins, postCoins);

        // Remember currently selected project (if any) before rebuilding combo
        String previousSelection = (String) buyCombo.getSelectedItem();
        boolean hadSelection = previousSelection != null && !previousSelection.equals(Strings.nothingOption());

        rebuildBuyCombo(pi, postRollPlayer, postRoll);

        // Restore selection if the previously selected card is still in the combo
        if (hadSelection) {
            for (int i = 0; i < buyCombo.getItemCount(); i++) {
                if (previousSelection.equals(buyCombo.getItemAt(i))) {
                    buyCombo.setSelectedIndex(i);
                    break;
                }
            }
        }

        double baselineWinProb = ProbabilityCalc.computeBaselineWinProb(postRoll, pi);
        baselineWinProbLabel.setText(Strings.baselineWinProbFmt(baselineWinProb * 100));

        // Always re-rank analytically on roll change (MC results depend on coins too)
        RankingOptions quickOpts = new RankingOptions();
        quickOpts.turnsElapsed = rankOpts.turnsElapsed;
        quickOpts.mcSimulations = 0;
        lastRanking = ProbabilityCalc.rankAllProjects(postRoll, pi, quickOpts);
        rebuildTable();

        // If no prior selection was preserved, auto-select first affordable
        if (!hadSelection || buyCombo.getSelectedIndex() == 0) {
            selectFirstAffordable();
        } else {
            // Update center panel to reflect the re-selected card with updated metrics
            String sel = (String) buyCombo.getSelectedItem();
            if (sel != null && !sel.equals(Strings.nothingOption())) {
                String id = projectIdFromLabel(sel);
                lastRanking.stream().filter(e -> e.project.getId().equals(id)).findFirst()
                        .ifPresent(this::populateCenter);
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
        String[] cols = new String[]{Strings.colCard(), Strings.colCost(), Strings.colEV(), Strings.colROI(), Strings.colP0(), Strings.colVar(), Strings.colWinDelta(), Strings.colPortfolioDelta()};

        // Update tab labels (language may have changed)
        rankTabs.setTitleAt(0, Strings.tabAffordable());
        rankTabs.setTitleAt(1, Strings.tabNotAffordable());
        rankTabs.setTitleAt(2, Strings.tabAll());
        rankTabs.setTitleAt(3, Strings.tabAssistant());

        fillRankTableModel(tableModel,             rankTable,             cols, lastRanking, true,  false);
        fillRankTableModel(tableModelUnaffordable, rankTableUnaffordable, cols, lastRanking, false, false);
        fillRankTableModel(tableModelAll,          rankTableAll,          cols, lastRanking, null,  true);

        rebuildAssistantPanel();
    }

    // =========================================================================
    // Game Assistant — phase context + helpers
    // =========================================================================

    /**
     * Rich strategic snapshot of the current game state from the active player's perspective.
     * All continuous signals are in [0,1] unless noted. Used by {@link #addContextProfile}
     * to interpolate weight arrays and generate contextual advice.
     *
     * <p><b>Phase signals</b> (sum to 1.0):
     * {@code earlyStrength}, {@code midStrength}, {@code lateStrength}.
     *
     * <p><b>Position signals</b>:
     * {@code catchUpStrength} (0=leading, 1=far behind) and
     * {@code pullAheadStrength} (0=equal, 1=far ahead) reflect relative standing
     * vs the strongest opponent. These modulate Aggro/GPRush vs ROI/Safe weights.
     *
     * <p><b>Economy signals</b>:
     * {@code coinAdvantage} (positive = more coins than avg opp, negative = less) relative
     * to avgOppEv, clamped to [-1,1]; {@code portfolioDiversity} (0=concentrated on one
     * number, 1=perfectly spread); {@code evGapVsLeader} (own EV minus best-opp EV,
     * negative = lagging economy).
     *
     * <p><b>Urgency</b>: {@code turnsToOwnWin} and {@code minTurnsToOppWin} — both in turns.
     *
     * <p><b>Synergy gaps</b>: flags for individual GP and card-synergy suggestions.
     */
    private record GamePhaseContext(
            // ── Phase (sum to 1.0) ──
            String phaseLabel,
            double earlyStrength,
            double midStrength,
            double lateStrength,
            // ── Landmarks ──
            int ownLandmarks,
            int maxOppLandmarks,
            // ── Position (0=neutral, 1=extreme) ──
            /** 1 = far behind in GPs+EV, 0 = leading or equal. Boosts GPRush+Aggro. */
            double catchUpStrength,
            /** 1 = far ahead in GPs+EV, 0 = equal or behind. Boosts ROI+Safe (consolidate). */
            double pullAheadStrength,
            /** Own EV minus best-opponent EV. Negative = economy lagging. */
            double evGapVsLeader,
            /** Coin advantage relative to opp EV scale: (ownCoins-avgOppCoins)/10, clamped [-1,1]. */
            double coinAdvantage,
            /** How spread the portfolio is over dice outcomes: 0=concentrated, 1=fully spread. */
            double portfolioDiversity,
            // ── Urgency ──
            /** Turns until the active player can plausibly buy the 4th GP. */
            double turnsToOwnWin,
            /** Turns until the leading opponent can plausibly buy their 4th GP. */
            double minTurnsToOppWin,
            // ── GP synergy suggestions ──
            boolean bahnhofSuggested, double bahnhofEvGain,
            boolean ekzSuggested,     double ekzEvGain,
            boolean fpSuggested,
            boolean ftSuggested,
            // ── Card synergy gap ──
            /** True if a booster card exists that would amplify ≥2 cards already owned. */
            boolean synergyGapExists,
            String  synergyGapCard,   // localized name of the booster
            double  synergyGapGain    // EV gain from buying the booster
    ) {}

    /**
     * Computes the full strategic context for the active player {@code pi}.
     * All signals are derived analytically from the current game state.
     */
    private GamePhaseContext computePhaseContext(int pi) {
        Player[] players = session.getState().getPlayers();
        Player active = players[pi];
        int n = players.length;

        // ── Landmark counts ───────────────────────────────────────────────────
        int ownLm = 0;
        for (Project p : active.getOwned_projects()) if (p.isIs_grossprojekt()) ownLm++;
        int maxOppLm = 0;
        for (int i = 0; i < n; i++) {
            if (i == pi) continue;
            int lm = 0;
            for (Project p : players[i].getOwned_projects()) if (p.isIs_grossprojekt()) lm++;
            maxOppLm = Math.max(maxOppLm, lm);
        }

        // ── EV per player ─────────────────────────────────────────────────────
        double[] evs = new double[n];
        double ownEv = 0, avgEv = 0, bestOppEv = 0;
        for (int i = 0; i < n; i++) {
            evs[i] = ProbabilityCalc.portfolioEvPerRound(session.getState(), i);
            avgEv += evs[i];
            if (i != pi) bestOppEv = Math.max(bestOppEv, evs[i]);
        }
        ownEv = evs[pi];
        avgEv /= n;

        // ── Phase strengths ───────────────────────────────────────────────────
        int maxGps = Math.max(ownLm, maxOppLm);
        double lateRaw = maxGps >= AssistantConfig.LATE_GP_THRESHOLD
                ? 1.0 : (double) maxGps / AssistantConfig.LATE_GP_THRESHOLD;

        double ownCoins = active.getCoins();
        boolean ekzReachable = (ownCoins + AssistantConfig.EARLY_SAVE_ROUNDS * ownEv)
                                >= AssistantConfig.EKZ_COST;
        double evWeak  = Math.max(0.0, 1.0 - avgEv / AssistantConfig.EARLY_AVG_EV_THRESHOLD);
        double ekzFar  = ekzReachable ? 0.0 : 1.0;
        double earlyRaw = (evWeak + ekzFar) / 2.0;

        double lateStr  = lateRaw;
        double earlyStr = earlyRaw * (1.0 - lateStr);
        double midStr   = 1.0 - lateStr - earlyStr;

        String phase;
        if (lateStr >= midStr && lateStr >= earlyStr)  phase = Strings.assistantPhaseLate();
        else if (earlyStr > midStr)                    phase = Strings.assistantPhaseEarly();
        else                                           phase = Strings.assistantPhaseMid();

        // ── Position: catch-up vs pull-ahead ─────────────────────────────────
        // GP gap: positive = we lead, negative = we lag
        int gpGap = ownLm - maxOppLm;         // e.g. -2 = opponent 2 GPs ahead
        // EV gap
        double evGapVsLeader = ownEv - bestOppEv;  // negative = economy lagging

        // Composite position score: weighted sum of GP gap and EV gap
        // GP gap scaled by 0.5 (max 4 GPs), EV gap scaled by ~1.5¢ range
        double positionScore = (gpGap / 4.0) * 0.6 + (evGapVsLeader / 1.5) * 0.4;
        positionScore = Math.max(-1.0, Math.min(1.0, positionScore));

        // catchUp = how far behind (0=leading/equal, 1=far behind)
        double catchUpStrength  = positionScore < 0 ? Math.min(1.0, -positionScore * 1.5) : 0.0;
        // pullAhead = how far ahead (0=equal/behind, 1=clearly leading)
        double pullAheadStrength = positionScore > 0 ? Math.min(1.0, positionScore * 1.5) : 0.0;

        // ── Coin advantage ────────────────────────────────────────────────────
        double avgOppCoins = 0;
        int oppCount = 0;
        for (int i = 0; i < n; i++) {
            if (i == pi) continue;
            avgOppCoins += players[i].getCoins();
            oppCount++;
        }
        if (oppCount > 0) avgOppCoins /= oppCount;
        // Scale: ±10 coins = ±1.0 (roughly 2-3 turns of income)
        double coinAdvantage = Math.max(-1.0, Math.min(1.0, (ownCoins - avgOppCoins) / 10.0));

        // ── Portfolio diversity ───────────────────────────────────────────────
        // Measure: how many distinct dice outcomes (1–12) trigger at least one card.
        // More outcomes covered = more diversity.
        boolean[] covered = new boolean[13]; // index 1..12
        for (Project p : active.getOwned_projects()) {
            if (!p.isIs_grossprojekt()) {
                for (int act : p.getDice_activation()) {
                    if (act >= 1 && act <= 12) covered[act] = true;
                }
            }
        }
        int coveredCount = 0;
        for (int r = 1; r <= 12; r++) if (covered[r]) coveredCount++;
        double portfolioDiversity = coveredCount / 12.0;

        // ── Urgency: turns-to-win ─────────────────────────────────────────────
        // Own: how many turns to save up for the cheapest missing GP
        double turnsToOwnWin = Double.MAX_VALUE;
        {
            int nextGpCost = nextMissingGpCost(active);
            if (nextGpCost == 0) {
                turnsToOwnWin = 0; // already has all 4
            } else if (ownEv > 0) {
                turnsToOwnWin = Math.max(0, (nextGpCost - ownCoins)) / ownEv;
            }
        }
        // Opponent: how many turns for the leading opp to win (same as pressure calc)
        double minTurnsToOppWin = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (i == pi) continue;
            double oppEv = evs[i];
            if (oppEv <= 0) continue;
            int oppLm = 0;
            for (Project p : players[i].getOwned_projects()) if (p.isIs_grossprojekt()) oppLm++;
            if (oppLm >= 4) { minTurnsToOppWin = 0; break; }
            int nextOppGpCost = nextMissingGpCost(players[i]);
            double t = Math.max(0, (nextOppGpCost - players[i].getCoins())) / oppEv;
            minTurnsToOppWin = Math.min(minTurnsToOppWin, t);
        }

        // ── GP synergy suggestions ─────────────────────────────────────────────
        boolean hasBahnhof = active.hasProject("bahnhof");
        boolean hasEkz     = active.hasProject("einkaufszentrum");
        boolean hasFp      = active.hasProject("freizeitpark");
        boolean hasFt      = active.hasProject("funkturm");

        boolean bahnhofSuggested = false;
        double bahnhofEvGain = 0.0;
        if (!hasBahnhof) {
            boolean hasHighRange = false;
            for (Project p : active.getOwned_projects()) {
                for (int act : p.getDice_activation()) if (act >= 7) { hasHighRange = true; break; }
                if (hasHighRange) break;
            }
            if (hasHighRange) {
                GameState withBahnhof = session.getState().copy();
                logic.probability.ProjectLoader.getProject("bahnhof").ifPresent(bp ->
                        withBahnhof.getPlayers()[pi].getOwned_projects().add(bp));
                bahnhofEvGain = ProbabilityCalc.portfolioEvPerRound(withBahnhof, pi) - ownEv;
                bahnhofSuggested = bahnhofEvGain > 0.2;
            }
        }

        boolean ekzSuggested = false;
        double ekzEvGain = 0.0;
        if (!hasEkz) {
            int greenOrStore = 0;
            for (Project p : active.getOwned_projects()) {
                if ((p.getColor().equals("grün") || p.getCategory().equals("store")) && !p.isIs_grossprojekt())
                    greenOrStore++;
            }
            if (greenOrStore >= 2) {
                GameState withEkz = session.getState().copy();
                logic.probability.ProjectLoader.getProject("einkaufszentrum").ifPresent(ep ->
                        withEkz.getPlayers()[pi].getOwned_projects().add(ep));
                ekzEvGain = ProbabilityCalc.portfolioEvPerRound(withEkz, pi) - ownEv;
                ekzSuggested = ekzEvGain > 0.05;
            }
        }

        boolean fpSuggested = false;
        if (!hasFp && hasBahnhof) {
            for (Project p : active.getOwned_projects()) {
                for (int act : p.getDice_activation()) {
                    if (act >= 6 && act <= 8) { fpSuggested = true; break; }
                }
                if (fpSuggested) break;
            }
        }
        boolean ftSuggested = !hasFt && hasFp;

        // ── Card synergy gap ──────────────────────────────────────────────────
        // Check if any booster card (Molkerei/Möbelfabrik/Markthalle etc.) would amplify
        // ≥2 cards already in the portfolio, and the EV gain exceeds a threshold.
        boolean synergyGapExists = false;
        String synergyGapCard = null;
        double synergyGapGain = 0.0;
        {
            String[] boosters = {"molkerei", "möbelfabrik", "markthalle", "käsefabrik", "obstmarkt"};
            for (String boosterId : boosters) {
                if (active.hasProject(boosterId)) continue;
                java.util.Optional<Project> bpOpt = logic.probability.ProjectLoader.getProject(boosterId);
                if (bpOpt.isEmpty()) continue;
                Project bp = bpOpt.get();
                int synergyCount = 0;
                for (Project owned : active.getOwned_projects()) {
                    if (cardSynergizesWith(owned, bp)) synergyCount++;
                }
                if (synergyCount < 2) continue;
                GameState withBooster = session.getState().copy();
                withBooster.getPlayers()[pi].getOwned_projects().add(bp);
                double gain = ProbabilityCalc.portfolioEvPerRound(withBooster, pi) - ownEv;
                if (gain > synergyGapGain) {
                    synergyGapGain = gain;
                    synergyGapCard = bp.getLocalizedName();
                }
            }
            synergyGapExists = synergyGapCard != null && synergyGapGain > 0.1;
        }

        return new GamePhaseContext(
                phase, earlyStr, midStr, lateStr,
                ownLm, maxOppLm,
                catchUpStrength, pullAheadStrength, evGapVsLeader, coinAdvantage, portfolioDiversity,
                turnsToOwnWin, minTurnsToOppWin,
                bahnhofSuggested, bahnhofEvGain,
                ekzSuggested, ekzEvGain,
                fpSuggested, ftSuggested,
                synergyGapExists, synergyGapCard, synergyGapGain);
    }

    /** Returns true if {@code owned} card would benefit from {@code booster}'s multiplier. */
    private static boolean cardSynergizesWith(Project owned, Project booster) {
        String cat = owned.getCategory();
        return switch (booster.getId()) {
            case "molkerei"    -> cat.equals("animal");
            case "möbelfabrik" -> cat.equals("production") || cat.equals("forest");
            case "markthalle"  -> cat.equals("food");
            case "käsefabrik"  -> cat.equals("animal");
            case "obstmarkt"   -> cat.equals("food");
            default -> false;
        };
    }

    /** Returns the cost of the cheapest GP the player does not yet own, or 0 if all owned. */
    private static int nextMissingGpCost(Player p) {
        int[][] gpCosts = {{4, 0}, {10, 1}, {16, 2}, {22, 3}}; // cost, min-GPs-already-needed
        String[] gpIds = {"bahnhof", "einkaufszentrum", "freizeitpark", "funkturm"};
        for (int i = 0; i < gpIds.length; i++) {
            if (!p.hasProject(gpIds[i])) return gpCosts[i][0];
        }
        return 0;
    }

    /**
     * Adds the Spiellage-Analyse context profile block to the top of the assistant panel.
     * Weights come from {@link AssistantConfig} and are modified by opponent-pressure.
     */
    private void addContextProfile(GamePhaseContext ctx, int coins, int pi) {
        List<RankEntry> affordable = lastRanking.stream().filter(e -> e.affordable).toList();
        if (affordable.isEmpty()) {
            addAssistantContextRow(ctx, Strings.assistantContextNoAffordable(), List.of(), null);
            return;
        }

        // Interpolate weights continuously from all three phase arrays
        double[] wE = AssistantConfig.WEIGHTS_EARLY;
        double[] wM = AssistantConfig.WEIGHTS_MID;
        double[] wL = AssistantConfig.WEIGHTS_LATE;
        double[] w = new double[8];
        for (int i = 0; i < 8; i++) {
            w[i] = ctx.earlyStrength() * wE[i]
                 + ctx.midStrength()   * wM[i]
                 + ctx.lateStrength()  * wL[i];
        }

        // ── Position modifier: catch-up boosts Aggro+GPRush; pull-ahead boosts Safe+ROI ──
        double cu = ctx.catchUpStrength();
        double pa = ctx.pullAheadStrength();
        w[AssistantConfig.W_GPRUSH] = Math.min(1.0, w[AssistantConfig.W_GPRUSH] + cu * 0.4);
        w[AssistantConfig.W_AGGRO]  = Math.min(1.0, w[AssistantConfig.W_AGGRO]  + cu * 0.3);
        w[AssistantConfig.W_CHEAP]  = Math.min(1.0, w[AssistantConfig.W_CHEAP]  + cu * 0.2);
        w[AssistantConfig.W_ROI]    = Math.min(1.0, w[AssistantConfig.W_ROI]    + pa * 0.2);
        w[AssistantConfig.W_SAFE]   = Math.min(1.0, w[AssistantConfig.W_SAFE]   + pa * 0.2);
        w[AssistantConfig.W_LOWVAR] = Math.min(1.0, w[AssistantConfig.W_LOWVAR] + pa * 0.15);

        // ── Coin advantage: coin-rich → prefer higher-cost/ROI cards (less Cheap weight) ──
        double ca = ctx.coinAdvantage();  // -1=poor, +1=rich
        w[AssistantConfig.W_CHEAP] = Math.max(0.0, w[AssistantConfig.W_CHEAP] - ca * 0.2);
        w[AssistantConfig.W_ROI]   = Math.min(1.0, w[AssistantConfig.W_ROI]   + ca * 0.1);

        // ── Diversity gap: very concentrated portfolio → boost LowVar (spread risk) ──
        double diversityGap = 1.0 - ctx.portfolioDiversity();  // 1=concentrated, 0=spread
        w[AssistantConfig.W_LOWVAR] = Math.min(1.0, w[AssistantConfig.W_LOWVAR] + diversityGap * 0.2);

        // ── Opponent-pressure (existing modifier, now using ctx.minTurnsToOppWin) ──
        double minTurnsToWin = ctx.minTurnsToOppWin();
        if (minTurnsToWin <= AssistantConfig.PRESSURE_EMERGENCY_TURNS) {
            w[AssistantConfig.W_GPRUSH] = Math.min(1.0, w[AssistantConfig.W_GPRUSH] + AssistantConfig.PRESSURE_EMERGENCY_GPRUSH);
            w[AssistantConfig.W_AGGRO]  = Math.min(1.0, w[AssistantConfig.W_AGGRO]  + AssistantConfig.PRESSURE_EMERGENCY_AGGRO);
        } else if (minTurnsToWin <= AssistantConfig.PRESSURE_WARNING_TURNS) {
            w[AssistantConfig.W_GPRUSH] = Math.min(1.0, w[AssistantConfig.W_GPRUSH] + AssistantConfig.PRESSURE_WARNING_GPRUSH);
            w[AssistantConfig.W_AGGRO]  = Math.min(1.0, w[AssistantConfig.W_AGGRO]  + AssistantConfig.PRESSURE_WARNING_AGGRO);
        }

        // Compute per-profile normalized rank vectors (best = 1.0, worst = 0.0)

        int m = affordable.size();
        // Sort copies for each metric
        List<RankEntry> byROI   = sorted(affordable, e -> e.roiOverHorizon, false);
        List<RankEntry> byEV    = sorted(affordable, e -> e.evPerRound, false);
        List<RankEntry> bySafe  = sorted(affordable, e -> e.probNoIncomeRound, true);
        List<RankEntry> byVar   = sorted(affordable, e -> e.variance, true);
        List<RankEntry> byCost  = sorted(affordable, e -> (double) e.project.getCost(), true);
        List<RankEntry> byWin   = sorted(affordable, e -> e.winProbDelta, false);
        List<RankEntry> byAggro = sorted(affordable, e ->
                (e.project.getColor().equals("rot") || e.project.getColor().equals("lila")) ? e.evPerRound : -999, false);
        List<RankEntry> byGP    = sorted(affordable, e -> e.project.isIs_grossprojekt() ? -e.project.getCost() : -99999, false);

        // Compute combined score per card
        RankEntry bestCard = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (RankEntry e : affordable) {
            double score = w[0] * normRank(byROI, e)
                         + w[1] * normRank(byEV, e)
                         + w[2] * normRank(bySafe, e)
                         + w[3] * normRank(byVar, e)
                         + w[4] * normRank(byCost, e)
                         + w[5] * normRank(byWin, e)
                         + w[6] * normRank(byAggro, e)
                         + w[7] * normRank(byGP, e);
            if (score > bestScore) { bestScore = score; bestCard = e; }
        }

        // Build factor list: show profiles with weight ≥ 0.5, ordered desc by weight
        String[] profileNames = {
            Strings.colROI(), Strings.colEV(),
            Strings.assistantProfileSafe(), Strings.assistantProfileLowVar(),
            Strings.assistantProfileCheap(), Strings.assistantProfileWinProb(),
            Strings.assistantProfileAggro(), Strings.assistantProfileGPRush()
        };
        @SuppressWarnings("unchecked")
        List<RankEntry>[] sortedByProfile = new List[]{byROI, byEV, bySafe, byVar, byCost, byWin, byAggro, byGP};
        List<String> factors = new java.util.ArrayList<>();
        // Sort profile indices by weight desc
        Integer[] order = {0,1,2,3,4,5,6,7};
        java.util.Arrays.sort(order, (a, b) -> Double.compare(w[b], w[a]));
        for (int idx : order) {
            if (w[idx] < 0.5) continue;
            int rank = 1 + sortedByProfile[idx].indexOf(bestCard);
            if (rank <= 0) rank = m;
            factors.add(Strings.assistantContextFactor(profileNames[idx], w[idx], rank));
        }

        String recommend = bestCard != null ? Strings.assistantContextRecommend(bestCard.project.getLocalizedName()) : "";
        addAssistantContextRow(ctx, recommend, factors, bestCard);
    }

    /** Sorts entries by metric, best first. For inverted metrics, lower value = better. */
    private static List<RankEntry> sorted(
            List<RankEntry> entries, java.util.function.ToDoubleFunction<RankEntry> metric, boolean lowerBetter) {
        return entries.stream()
                .sorted(lowerBetter
                        ? java.util.Comparator.comparingDouble(metric)
                        : java.util.Comparator.comparingDouble(metric).reversed())
                .toList();
    }

    /** Returns the normalized rank of {@code entry} in the sorted list: 0.0 = best, but used as 1.0 here (best=1). */
    private static double normRank(List<RankEntry> sortedBestFirst, RankEntry entry) {
        int idx = sortedBestFirst.indexOf(entry);
        if (idx < 0 || sortedBestFirst.size() == 1) return 1.0;
        return 1.0 - (double) idx / (sortedBestFirst.size() - 1);
    }

    /** Renders the Spiellage context block at the top of the assistant panel. */
    @SuppressWarnings("unused")
    private void addAssistantContextRow(GamePhaseContext ctx, String body, List<String> factors, RankEntry winner) {
        JPanel ctxPanel = new JPanel();
        ctxPanel.setLayout(new BoxLayout(ctxPanel, BoxLayout.Y_AXIS));
        ctxPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 2, 0, new Color(0xAAAAAA)),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        ctxPanel.setBackground(new Color(0xF0F4FF));
        ctxPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        ctxPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        // Title
        JLabel titleLabel = new JLabel(Strings.assistantContextTitle());
        titleLabel.setFont(new Font("Arial", Font.BOLD, 12));
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        ctxPanel.add(titleLabel);

        // Phase line: shows continuous blend
        JLabel phaseLabel = new JLabel(Strings.assistantContextPhase(ctx.phaseLabel(), ctx.maxOppLandmarks(),
                ctx.earlyStrength(), ctx.midStrength(), ctx.lateStrength()));
        phaseLabel.setFont(new Font("Arial", Font.PLAIN, 10));
        phaseLabel.setForeground(new Color(0x555555));
        phaseLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        ctxPanel.add(phaseLabel);

        // Position line: catch-up vs pull-ahead
        String posLine = Strings.assistantContextPosition(ctx.catchUpStrength(), ctx.pullAheadStrength(),
                ctx.evGapVsLeader(), ctx.turnsToOwnWin(), ctx.minTurnsToOppWin());
        if (posLine != null) {
            JLabel posLabel = new JLabel("<html>" + posLine + "</html>");
            posLabel.setFont(new Font("Arial", Font.PLAIN, 10));
            posLabel.setForeground(new Color(0x444488));
            posLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            ctxPanel.add(posLabel);
        }

        ctxPanel.add(Box.createVerticalStrut(4));

        // Recommendation
        JLabel recLabel = new JLabel("<html>" + body + "</html>");
        recLabel.setFont(new Font("Arial", Font.BOLD, 11));
        recLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        ctxPanel.add(recLabel);

        // Factors (with blend explanation header)
        if (!factors.isEmpty()) {
            ctxPanel.add(Box.createVerticalStrut(3));
            JLabel weightsBlend = new JLabel("<html><i>" + Strings.assistantContextWeightsBlend() + "</i></html>");
            weightsBlend.setFont(new Font("Arial", Font.PLAIN, 9));
            weightsBlend.setForeground(new Color(0x666666));
            weightsBlend.setAlignmentX(Component.LEFT_ALIGNMENT);
            ctxPanel.add(weightsBlend);
            StringBuilder sb = new StringBuilder("<html><body style='font-size:10px;color:#444'>");
            for (String f : factors) sb.append(f).append("<br>");
            sb.append("</body></html>");
            JLabel factorLabel = new JLabel(sb.toString());
            factorLabel.setFont(new Font("Arial", Font.PLAIN, 10));
            factorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            ctxPanel.add(factorLabel);
        }

        // Synergy gap hint
        if (ctx.synergyGapExists()) {
            ctxPanel.add(Box.createVerticalStrut(3));
            JLabel synLabel = new JLabel("<html>" + Strings.assistantContextSynergyGap(
                    ctx.synergyGapCard(), ctx.synergyGapGain()) + "</html>");
            synLabel.setFont(new Font("Arial", Font.PLAIN, 10));
            synLabel.setForeground(new Color(0x1a6600));
            synLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            ctxPanel.add(synLabel);
        }

        // GP hints
        boolean anyHint = ctx.bahnhofSuggested() || ctx.ekzSuggested() || ctx.fpSuggested() || ctx.ftSuggested();
        if (anyHint) {
            ctxPanel.add(Box.createVerticalStrut(3));
            java.util.function.BiConsumer<String, Double> addHint = (gp, evGain) -> {
                JLabel h = new JLabel("<html>" + Strings.assistantContextGPHint(gp, evGain) + "</html>");
                h.setFont(new Font("Arial", Font.PLAIN, 10));
                h.setForeground(new Color(0x2244AA));
                h.setAlignmentX(Component.LEFT_ALIGNMENT);
                ctxPanel.add(h);
            };
            if (ctx.bahnhofSuggested()) {
                logic.probability.ProjectLoader.getProject("bahnhof").ifPresent(p ->
                        addHint.accept(p.getLocalizedName(), ctx.bahnhofEvGain()));
            }
            if (ctx.ekzSuggested()) {
                logic.probability.ProjectLoader.getProject("einkaufszentrum").ifPresent(p ->
                        addHint.accept(p.getLocalizedName(), ctx.ekzEvGain()));
            }
            if (ctx.fpSuggested()) {
                logic.probability.ProjectLoader.getProject("freizeitpark").ifPresent(p ->
                        addHint.accept(p.getLocalizedName(), 0.0));
            }
            if (ctx.ftSuggested()) {
                logic.probability.ProjectLoader.getProject("funkturm").ifPresent(p ->
                        addHint.accept(p.getLocalizedName(), 0.0));
            }
        }

        // Bahnhof dice-choice hint
        int pi = session.nextPlayerIndex();
        if (session.getState().getPlayers()[pi].hasProject("bahnhof")) {
            ctxPanel.add(Box.createVerticalStrut(4));
            int optDice = ProbabilityCalc.optimalDiceCount(session.getState(), pi);
            String diceHint = optDice == 2 ? Strings.assistantDiceHint2d6() : Strings.assistantDiceHint1d6();
            JLabel diceLabel = new JLabel("<html>" + diceHint + "</html>");
            diceLabel.setFont(new Font("Arial", Font.PLAIN, 10));
            diceLabel.setForeground(new Color(0x444444));
            diceLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            ctxPanel.add(diceLabel);
        }

        assistantPanel.add(ctxPanel);
    }

    /**
     * Rebuilds the Game Assistant tab from the current {@code lastRanking}.
     * Shows the Spiellage-Analyse block plus 8 strategy-profile rows.
     */
    // ---- Assistant helpers ----

    /** Result of profile resolution: winner, optional tiebreaker note, names of other tied entries. */
    private record TieResult(RankEntry winner, String tiebreakerNote, java.util.List<String> otherNames) {
        boolean hasWinner() { return winner != null; }
    }

    /**
     * Picks the best affordable entry for an assistant profile using {@code metric},
     * resolving ties with a three-level tiebreaker (ROI → EV/round → lowest cost).
     */
    private TieResult resolveWithTiebreaker(
            java.util.function.ToDoubleFunction<RankEntry> metric, boolean lowerIsBetter) {

        List<RankEntry> pool = lastRanking.stream().filter(e -> e.affordable).toList();
        if (pool.isEmpty()) return new TieResult(null, null, List.of());

        // Find best value
        double best = lowerIsBetter
                ? pool.stream().mapToDouble(metric).min().orElse(0)
                : pool.stream().mapToDouble(metric).max().orElse(0);
        final double EPS = 1e-6;
        List<RankEntry> tied = pool.stream()
                .filter(e -> Math.abs(metric.applyAsDouble(e) - best) <= EPS)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));

        if (tied.size() == 1) return new TieResult(tied.get(0), null, List.of());

        // Tiebreaker 1: highest ROI
        double bestROI = tied.stream().mapToDouble(e -> e.roiOverHorizon).max().orElse(0);
        List<RankEntry> t1 = tied.stream().filter(e -> Math.abs(e.roiOverHorizon - bestROI) <= EPS).toList();
        if (t1.size() == 1) {
            List<String> others = tied.stream().filter(e -> e != t1.get(0)).map(e -> e.project.getLocalizedName()).toList();
            return new TieResult(t1.get(0), Strings.assistantTiebreakerNote(Strings.colROI()), others);
        }

        // Tiebreaker 2: highest EV/round
        double bestEV = t1.stream().mapToDouble(e -> e.evPerRound).max().orElse(0);
        List<RankEntry> t2 = t1.stream().filter(e -> Math.abs(e.evPerRound - bestEV) <= EPS).toList();
        if (t2.size() == 1) {
            List<String> others = tied.stream().filter(e -> e != t2.get(0)).map(e -> e.project.getLocalizedName()).toList();
            return new TieResult(t2.get(0), Strings.assistantTiebreakerNote(Strings.colEV()), others);
        }

        // Tiebreaker 3: lowest cost
        int minCost = t2.stream().mapToInt(e -> e.project.getCost()).min().orElse(0);
        RankEntry winner = t2.stream().filter(e -> e.project.getCost() == minCost).findFirst().orElse(tied.get(0));
        List<String> others = tied.stream().filter(e -> e != winner).map(e -> e.project.getLocalizedName()).toList();
        return new TieResult(winner, Strings.assistantTiebreakerNote(Strings.colCost()), others);
    }

    /** Builds the tie/also suffix HTML fragment for a {@link TieResult}. Empty string if no ties. */
    private static String buildTieSuffix(TieResult result) {
        if (result.tiebreakerNote() == null || result.otherNames().isEmpty()) return "";
        List<String> shown = result.otherNames().size() <= 3
                ? result.otherNames()
                : result.otherNames().subList(0, 3);
        int extra = result.otherNames().size() - shown.size();
        return "<br><i style='color:#777'>" + result.tiebreakerNote()
                + " " + Strings.assistantAlso(shown, extra) + "</i>";
    }

    /**
     * Returns up to {@code max} runner-up names (best after the winner) for a profile metric.
     * Used to populate the right-side "also: #2, #3" column in each assistant row.
     */
    private List<String> runnerUpNames(
            java.util.function.ToDoubleFunction<RankEntry> metric,
            boolean lowerIsBetter, String winnerId, int max) {
        List<RankEntry> pool = lastRanking.stream()
                .filter(e -> e.affordable && !e.isWaitEntry() && !e.project.getId().equals(winnerId))
                .sorted(lowerIsBetter
                        ? java.util.Comparator.comparingDouble(metric)
                        : java.util.Comparator.comparingDouble(metric).reversed())
                .toList();
        List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < Math.min(max, pool.size()); i++) {
            names.add(pool.get(i).project.getLocalizedName());
        }
        return names;
    }

    /** Adds a profile row to the assistant panel with optional runner-up column. */
    private void addAssistantRow(String profileLabel, String body, List<String> runnerUps) {
        JPanel profileRow = new JPanel(new BorderLayout(6, 0));
        profileRow.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xDDDDDD)),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        profileRow.setBackground(Color.WHITE);
        profileRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        profileRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        // Left: profile name + recommendation body
        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setOpaque(false);

        JLabel nameLabel = new JLabel(profileLabel);
        nameLabel.setFont(new Font("Arial", Font.BOLD, 11));
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        left.add(nameLabel);

        JLabel bodyLabel = new JLabel("<html><body style='width:180px'>" + body + "</body></html>");
        bodyLabel.setFont(new Font("Arial", Font.PLAIN, 11));
        bodyLabel.setForeground(new Color(0x333333));
        bodyLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        left.add(Box.createVerticalStrut(2));
        left.add(bodyLabel);

        profileRow.add(left, BorderLayout.CENTER);

        // Right: compact runner-up list
        if (!runnerUps.isEmpty()) {
            JPanel right = new JPanel();
            right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
            right.setOpaque(false);
            right.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(0xDDDDDD)),
                    BorderFactory.createEmptyBorder(0, 6, 0, 0)));

            JLabel ruHeader = new JLabel(Strings.isDE() ? "Alternativ:" : "Also:");
            ruHeader.setFont(new Font("Arial", Font.PLAIN, 9));
            ruHeader.setForeground(new Color(0x888888));
            right.add(ruHeader);

            for (int i = 0; i < runnerUps.size(); i++) {
                JLabel ru = new JLabel((i + 2) + ". " + runnerUps.get(i));
                ru.setFont(new Font("Arial", Font.PLAIN, 10));
                ru.setForeground(new Color(0x666666));
                right.add(ru);
            }
            profileRow.add(right, BorderLayout.EAST);
        }

        assistantPanel.add(profileRow);
    }

    /** Overload without runner-ups (used by GP Rush which has no ranked alternative). */
    private void addAssistantRow(String profileLabel, String body) {
        addAssistantRow(profileLabel, body, List.of());
    }

    private void rebuildAssistantPanel() {
        assistantPanel.removeAll();

        if (lastRanking.isEmpty()) {
            JLabel empty = new JLabel(Strings.noAffordableCardsTab());
            empty.setBorder(BorderFactory.createEmptyBorder(8, 10, 4, 10));
            assistantPanel.add(empty);
            assistantPanel.revalidate();
            assistantPanel.repaint();
            return;
        }

        int pi = session.nextPlayerIndex();
        int coins = session.getState().getPlayers()[pi].getCoins();

        // ---- Phase context (built first, used by context profile AND GP Rush) ----
        GamePhaseContext ctx = computePhaseContext(pi);

        // ---- Context profile (9th, shown first) ----
        addContextProfile(ctx, coins, pi);

        // ---- 8 individual strategy profiles (with tie-breaking) ----

        // ROI
        TieResult trROI = resolveWithTiebreaker(e -> e.roiOverHorizon, false);
        String bodyROI = trROI.hasWinner()
                ? Strings.assistantExplainROI(trROI.winner().project.getLocalizedName(), trROI.winner().roiOverHorizon) + buildTieSuffix(trROI)
                : Strings.assistantNoAffordable();
        addAssistantRow(Strings.assistantProfileROI(), bodyROI,
                trROI.hasWinner() ? runnerUpNames(e -> e.roiOverHorizon, false, trROI.winner().project.getId(), 2) : List.of());

        // EV
        TieResult trEV = resolveWithTiebreaker(e -> e.evPerRound, false);
        String bodyEV = trEV.hasWinner()
                ? Strings.assistantExplainEV(trEV.winner().project.getLocalizedName(), trEV.winner().evPerRound) + buildTieSuffix(trEV)
                : Strings.assistantNoAffordable();
        addAssistantRow(Strings.assistantProfileEV(), bodyEV,
                trEV.hasWinner() ? runnerUpNames(e -> e.evPerRound, false, trEV.winner().project.getId(), 2) : List.of());

        // Safe — lowest P0
        TieResult trSafe = resolveWithTiebreaker(e -> e.probNoIncomeRound, true);
        String bodySafe = trSafe.hasWinner()
                ? Strings.assistantExplainSafe(trSafe.winner().project.getLocalizedName(), trSafe.winner().probNoIncomeRound) + buildTieSuffix(trSafe)
                : Strings.assistantNoAffordable();
        addAssistantRow(Strings.assistantProfileSafe(), bodySafe,
                trSafe.hasWinner() ? runnerUpNames(e -> e.probNoIncomeRound, true, trSafe.winner().project.getId(), 2) : List.of());

        // LowVar
        TieResult trLowVar = resolveWithTiebreaker(e -> e.variance, true);
        String bodyLowVar = trLowVar.hasWinner()
                ? Strings.assistantExplainLowVar(trLowVar.winner().project.getLocalizedName(), trLowVar.winner().variance) + buildTieSuffix(trLowVar)
                : Strings.assistantNoAffordable();
        addAssistantRow(Strings.assistantProfileLowVar(), bodyLowVar,
                trLowVar.hasWinner() ? runnerUpNames(e -> e.variance, true, trLowVar.winner().project.getId(), 2) : List.of());

        // Cheap — lowest cost
        TieResult trCheap = resolveWithTiebreaker(e -> e.project.getCost(), true);
        String bodyCheap = trCheap.hasWinner()
                ? Strings.assistantExplainCheap(trCheap.winner().project.getLocalizedName(), trCheap.winner().project.getCost()) + buildTieSuffix(trCheap)
                : Strings.assistantNoAffordable();
        addAssistantRow(Strings.assistantProfileCheap(), bodyCheap,
                trCheap.hasWinner() ? runnerUpNames(e -> (double) e.project.getCost(), true, trCheap.winner().project.getId(), 2) : List.of());

        // WinProb
        boolean hasWinProb = lastRanking.stream().anyMatch(e -> e.winProbDelta != 0.0);
        TieResult trWin = hasWinProb ? resolveWithTiebreaker(e -> e.winProbDelta, false) : new TieResult(null, null, List.of());
        String bodyWin = trWin.hasWinner()
                ? Strings.assistantExplainWinProb(trWin.winner().project.getLocalizedName(), trWin.winner().winProbDelta) + buildTieSuffix(trWin)
                : Strings.assistantNoWinProb();
        addAssistantRow(Strings.assistantProfileWinProb(), bodyWin,
                trWin.hasWinner() ? runnerUpNames(e -> e.winProbDelta, false, trWin.winner().project.getId(), 2) : List.of());

        // Aggro — rot/lila only
        List<RankEntry> aggroPool = lastRanking.stream()
                .filter(e -> e.affordable && (e.project.getColor().equals("rot") || e.project.getColor().equals("lila")))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        TieResult trAggro = aggroPool.isEmpty() ? new TieResult(null, null, List.of())
                : resolveAggroWithTiebreaker(aggroPool);
        String bodyAggro = trAggro.hasWinner()
                ? Strings.assistantExplainAggro(trAggro.winner().project.getLocalizedName()) + buildTieSuffix(trAggro)
                : Strings.assistantNoAffordable();
        addAssistantRow(Strings.assistantProfileAggro(), bodyAggro,
                trAggro.hasWinner() ? runnerUpNames(e -> e.evPerRound, false, trAggro.winner().project.getId(), 2) : List.of());

        // GP Rush — cheapest unbuilt GP
        RankEntry bestGP = lastRanking.stream()
                .filter(e -> !e.isWaitEntry() && e.project.isIs_grossprojekt())
                .min(java.util.Comparator.comparingInt(e -> e.project.getCost())).orElse(null);
        String bodyGP = bestGP != null
                ? Strings.assistantExplainGPRush(bestGP.project.getLocalizedName(), bestGP.project.getCost(), coins)
                : Strings.assistantNoAffordable();
        addAssistantRow(Strings.assistantProfileGPRush(), bodyGP);

        assistantPanel.revalidate();
        assistantPanel.repaint();
    }

    /** Resolves the Aggro profile from a pre-filtered rot/lila pool. */
    private TieResult resolveAggroWithTiebreaker(List<RankEntry> pool) {
        double best = pool.stream().mapToDouble(e -> e.evPerRound).max().orElse(0);
        final double EPS = 1e-6;
        List<RankEntry> tied = pool.stream().filter(e -> Math.abs(e.evPerRound - best) <= EPS).toList();
        if (tied.size() == 1) return new TieResult(tied.get(0), null, List.of());
        // Tiebreaker: highest ROI
        double bestROI = tied.stream().mapToDouble(e -> e.roiOverHorizon).max().orElse(0);
        RankEntry winner = tied.stream().filter(e -> Math.abs(e.roiOverHorizon - bestROI) <= EPS).findFirst().orElse(tied.get(0));
        List<String> others = tied.stream().filter(e -> e != winner).map(e -> e.project.getLocalizedName()).toList();
        return new TieResult(winner, Strings.assistantTiebreakerNote(Strings.colROI()), others);
    }

    /**
     * Fills a rank table model and re-applies column renderers and sorter comparators.
     *
     * @param model        the DefaultTableModel to populate
     * @param table        the JTable whose renderers and sorter to update
     * @param cols         column header names (length 6 or 7)
     * @param ranking      the full ranking (affordable + unaffordable)
     * @param affordableFilter  true = only affordable, false = only unaffordable, null = all
     * @param dimUnaffordable   true = use dimmed renderer for unaffordable rows (used in "All" tab)
     */
    private void fillRankTableModel(DefaultTableModel model, JTable table, String[] cols,
                                     ArrayList<RankEntry> ranking,
                                     Boolean affordableFilter, boolean dimUnaffordable) {
        RowSorter<?> existingSorter = table.getRowSorter();
        java.util.List<? extends RowSorter.SortKey> savedSortKeys =
                existingSorter != null ? existingSorter.getSortKeys() : null;

        model.setColumnIdentifiers(cols);
        model.setRowCount(0);

        for (RankEntry e : ranking) {
            // "Wait" sentinel: shown in affordable tab and "All" tab, not in unaffordable tab
            if (e.isWaitEntry()) {
                if (Boolean.FALSE.equals(affordableFilter)) continue;
            } else {
                if (affordableFilter != null && e.affordable != affordableFilter) continue;
            }
            String cardLabel = e.isWaitEntry()
                    ? Strings.waitLabel()
                    : (e.project.isIs_grossprojekt()
                            ? e.project.getLocalizedName() + " " + Strings.gpTag()
                            : e.project.getLocalizedName());
            double cost = e.isWaitEntry() ? Double.NaN : (double) e.project.getCost();
            Object[] row = new Object[]{cardLabel, cost,
                                       e.evPerRound, e.roiOverHorizon,
                                       e.probNoIncomeRound, e.variance,
                                       e.winProbDelta, e.portfolioDeltaEV};
            model.addRow(row);
        }

        // Re-apply renderers
        boolean useUnaffordableRenderer = dimUnaffordable;
        table.getColumnModel().getColumn(0).setCellRenderer(
                useUnaffordableRenderer ? new CardNameRendererWithDim(ranking) : new CardNameRenderer());
        table.getColumnModel().getColumn(0).setPreferredWidth(120);
        table.getColumnModel().getColumn(1).setCellRenderer(new NumericCellRenderer(MetricColorScheme.COST));
        table.getColumnModel().getColumn(1).setPreferredWidth(52);
        for (int c = 2; c < cols.length; c++) {
            MetricColorScheme scheme = MetricColorScheme.TABLE_ORDER[c - 2];
            NumericCellRenderer r = useUnaffordableRenderer
                    ? new NumericCellRendererWithDim(scheme, ranking)
                    : new NumericCellRenderer(scheme);
            table.getColumnModel().getColumn(c).setCellRenderer(r);
            table.getColumnModel().getColumn(c).setPreferredWidth(52);
        }

        // Re-attach sorter
        @SuppressWarnings("unchecked")
        TableRowSorter<DefaultTableModel> sorter = (TableRowSorter<DefaultTableModel>) table.getRowSorter();
        if (sorter != null) {
            sorter.setComparator(0, java.util.Comparator.naturalOrder());
            for (int c = 1; c < cols.length; c++) {
                sorter.setComparator(c, java.util.Comparator.comparingDouble(o -> (Double) o));
            }
            if (savedSortKeys != null && !savedSortKeys.isEmpty()) {
                java.util.List<RowSorter.SortKey> restored = new java.util.ArrayList<>();
                for (RowSorter.SortKey sk : savedSortKeys) {
                    if (sk.getColumn() < cols.length) restored.add(sk);
                }
                if (!restored.isEmpty()) sorter.setSortKeys(restored);
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
        topCardTrigger.setCardColor(p.getColor());

        topCardCostRow.removeAll();
        JLabel costLabel = new JLabel(Strings.costPrefix(p.getCost()));
        costLabel.setFont(new Font("Arial", Font.PLAIN, 13));
        topCardCostRow.add(costLabel);
        buildActivationDice(topCardCostRow, p);
        String desc = p.getLocalizedDescription();
        topCardDesc.setText("<html><i>" + (desc != null && !desc.isEmpty() ? desc : "—") + "</i></html>");

        MetricColorScheme[] schemes = {
            MetricColorScheme.EV, MetricColorScheme.ROI,
            MetricColorScheme.P0, MetricColorScheme.VARIANCE, MetricColorScheme.WIN_PROB_DELTA,
            MetricColorScheme.PORTFOLIO_DELTA
        };
        double[] values = {
            entry.evPerRound, entry.roiOverHorizon,
            entry.probNoIncomeRound, entry.variance, entry.winProbDelta,
            entry.portfolioDeltaEV
        };
        JLabel[] valueLabels = { topCardEV, topCardROI, topCardRisk, topCardVar, topCardWinProb, topCardPortfolioDelta };
        for (int i = 0; i < schemes.length; i++) {
            double rankPct = computeMetricRankPct(lastRanking, p.getId(), schemes[i]);
            applyRankedMetricColor(valueLabels[i], schemes[i], values[i], rankPct);
            topCardMetricRank[i].setText(metricRankText(lastRanking, p.getId(), schemes[i]));
        }

        topCardNote.setText("<html><i>" + buildNote(entry) + "</i></html>");
        topCardColorBar.setBackground(colorForCard(p));

        // Rank context: "#X / Y affordable · #Z / N total"
        int rankAffordable = 0, totalAffordable = 0, rankAll = 0, totalAll = 0;
        for (RankEntry e : lastRanking) {
            if (e.isWaitEntry()) continue;
            totalAll++;
            if (e.affordable) totalAffordable++;
            if (e.project.getId().equals(p.getId())) {
                rankAll = totalAll;
                if (e.affordable) rankAffordable = totalAffordable;
            }
        }
        topCardRank.setText(Strings.rankLabel(rankAffordable, totalAffordable, rankAll, totalAll));

        // Always re-apply visibility (win prob always shown)
        setWinProbRowVisible(true);
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

    /**
     * Computes the relative rank of {@code projectId}'s metric value within {@code ranking}.
     *
     * @param ranking   the full ranking list
     * @param projectId the card to look up
     * @param scheme    determines which metric field and whether lower = better
     * @return rank as a fraction in [0.0, 1.0]: 0.0 = best, 1.0 = worst; or 0.5 if not found
     */
    private static double computeMetricRankPct(
            List<RankEntry> ranking, String projectId, MetricColorScheme scheme) {
        if (ranking.isEmpty()) return 0.5;
        java.util.function.ToDoubleFunction<RankEntry> extractor = switch (scheme) {
            case EV               -> e -> e.evPerRound;
            case ROI              -> e -> e.roiOverHorizon;
            case P0               -> e -> e.probNoIncomeRound;
            case VARIANCE         -> e -> e.variance;
            case WIN_PROB_DELTA   -> e -> e.winProbDelta;
            case PORTFOLIO_DELTA  -> e -> e.portfolioDeltaEV;
            default               -> e -> 0.0;
        };
        // For inverted metrics lower is better → sort ascending for "best first"
        java.util.Comparator<RankEntry> comp = java.util.Comparator.comparingDouble(extractor);
        if (scheme != MetricColorScheme.P0 && scheme != MetricColorScheme.VARIANCE) {
            comp = comp.reversed(); // higher is better: best = largest
        }
        List<RankEntry> sorted = new java.util.ArrayList<>(ranking);
        sorted.sort(comp);
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).project.getId().equals(projectId)) {
                return ranking.size() == 1 ? 0.0 : (double) i / (sorted.size() - 1);
            }
        }
        return 0.5;
    }

    /**
     * Sets a metric label's text and background using rank-relative colour (0 = best, 1 = worst).
     */
    private static void applyRankedMetricColor(
            JLabel label, MetricColorScheme scheme, double value, double rankPct) {
        label.setText(fmt2(value));
        Color bg = scheme.rankedBackgroundFor(rankPct);
        Color fg = (bg == MetricColorScheme.GREEN_STRONG_REF) ? new Color(0x1A5C28) : null;
        label.setOpaque(bg != null);
        label.setBackground(bg != null ? bg : label.getParent() != null ? label.getParent().getBackground() : Color.WHITE);
        label.setForeground(fg != null ? fg : Color.BLACK);
    }

    /**
     * Returns a short rank string like "#2 / 15" for a given metric within the ranking.
     * Excludes the wait-sentinel from counting.
     */
    private static String metricRankText(
            List<RankEntry> ranking, String projectId, MetricColorScheme scheme) {
        if (ranking.isEmpty()) return "";
        java.util.function.ToDoubleFunction<RankEntry> extractor = switch (scheme) {
            case EV               -> e -> e.evPerRound;
            case ROI              -> e -> e.roiOverHorizon;
            case P0               -> e -> e.probNoIncomeRound;
            case VARIANCE         -> e -> e.variance;
            case WIN_PROB_DELTA   -> e -> e.winProbDelta;
            case PORTFOLIO_DELTA  -> e -> e.portfolioDeltaEV;
            default               -> e -> 0.0;
        };
        java.util.Comparator<RankEntry> comp = java.util.Comparator.comparingDouble(extractor);
        if (scheme != MetricColorScheme.P0 && scheme != MetricColorScheme.VARIANCE) {
            comp = comp.reversed();
        }
        // Filter out the wait sentinel
        List<RankEntry> valid = ranking.stream()
                .filter(e -> !e.isWaitEntry()).toList();
        List<RankEntry> sorted = new java.util.ArrayList<>(valid);
        sorted.sort(comp);
        int total = sorted.size();
        for (int i = 0; i < total; i++) {
            if (sorted.get(i).project.getId().equals(projectId)) {
                return "#" + (i + 1) + "/" + total;
            }
        }
        return "";
    }

    private void clearCenter(String message) {
        topCardName.setText("—");
        topCardColorTag.setText("");
        topCardColorTag.setBackground(null);
        topCardTrigger.setCardColor(null);
        topCardCostRow.removeAll();
        topCardDesc.setText("");
        topCardEV.setText("—");
        topCardROI.setText("—");
        topCardRisk.setText("—");
        topCardVar.setText("—");
        topCardWinProb.setText("—");
        topCardPortfolioDelta.setText("—");
        for (JLabel r : topCardMetricRank) if (r != null) r.setText("—");
        topCardNote.setText("<html><i>" + message + "</i></html>");
        topCardRank.setText("—");
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

    /**
     * Variant of {@link CardNameRenderer} used in the "All" tab.
     * Unaffordable rows are rendered in italic with a dimmed/grey background.
     */
    private static class CardNameRendererWithDim extends CardNameRenderer {
        private final ArrayList<RankEntry> ranking;

        CardNameRendererWithDim(ArrayList<RankEntry> ranking) {
            this.ranking = ranking;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                        boolean isSelected, boolean hasFocus,
                                                        int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (!isSelected && value instanceof String label) {
                RankEntry entry = entryForLabel(label, ranking);
                if (entry != null && !entry.affordable) {
                    setBackground(new Color(0xEEEEEE));
                    setFont(getFont().deriveFont(Font.ITALIC));
                    setForeground(new Color(0x888888));
                }
            }
            return this;
        }
    }

    // =========================================================================
    // Color-coded numeric cell renderer
    // =========================================================================

    /**
     * Renders numeric table cells with 2-decimal formatting and metric-aware colour coding.
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
                    setOpaque(true);
                    setBackground(bg != null ? bg : table.getBackground());
                    setForeground(fg != null ? fg : table.getForeground());
                } catch (NumberFormatException ignored) {
                    setOpaque(true);
                    setBackground(table.getBackground());
                    setForeground(table.getForeground());
                }
            } else if (isSelected) {
                setOpaque(true);
                setBackground(table.getSelectionBackground());
                setForeground(table.getSelectionForeground());
            }
            return this;
        }
    }

    /**
     * Variant used in the "All" tab — dims unaffordable rows with grey italic rendering.
     */
    private static class NumericCellRendererWithDim extends NumericCellRenderer {
        private final ArrayList<RankEntry> ranking;

        NumericCellRendererWithDim(MetricColorScheme scheme, ArrayList<RankEntry> ranking) {
            super(scheme);
            this.ranking = ranking;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                        boolean isSelected, boolean hasFocus,
                                                        int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (!isSelected) {
                // Look up the card name from the same row (column 0) to find the entry
                Object nameCell = table.getModel().getValueAt(
                        table.convertRowIndexToModel(row), 0);
                if (nameCell instanceof String label) {
                    RankEntry entry = entryForLabel(label, ranking);
                    if (entry != null && !entry.affordable) {
                        setBackground(new Color(0xEEEEEE));
                        setForeground(new Color(0x888888));
                        setFont(getFont().deriveFont(Font.ITALIC));
                    }
                }
            }
            return this;
        }
    }

    /** Looks up a RankEntry by the localized card label used in the table (strips [GP] suffix). */
    private static RankEntry entryForLabel(String label, ArrayList<RankEntry> ranking) {
        String clean = label.replace(" " + Strings.gpTag(), "");
        int paren = clean.indexOf(" (");
        String localizedName = paren >= 0 ? clean.substring(0, paren) : clean;
        for (RankEntry e : ranking) {
            if (e.isWaitEntry()) continue;
            if (e.project.getLocalizedName().equals(localizedName)) return e;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // TriggerModePanel — draws small circles indicating when a card fires
    // -------------------------------------------------------------------------

    /**
     * A small panel that draws filled circles representing the trigger mode of a card:
     * <ul>
     *   <li>Blau  — 3 circles: fires on every player's turn</li>
     *   <li>Grün  — 1 circle:  fires on own turn only</li>
     *   <li>Rot   — 1 circle:  fires on opponents' turns (drawn with a diagonal line inside)</li>
     *   <li>Lila  — 1 circle + diamond: own turn, once per round</li>
     *   <li>Gelb  — no indicator (landmarks are bought, not triggered)</li>
     * </ul>
     */
    private static class TriggerModePanel extends javax.swing.JPanel {
        private String color = "";

        TriggerModePanel() {
            setOpaque(false);
            setPreferredSize(new java.awt.Dimension(56, 16));
        }

        void setCardColor(String cardColor) {
            this.color = cardColor == null ? "" : cardColor;
            repaint();
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            super.paintComponent(g);
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);

            int r = 5;      // circle radius
            int d = r * 2;  // circle diameter
            int gap = 3;    // gap between circles
            int cy = getHeight() / 2;

            java.awt.Color fill;
            int count;
            boolean withLine   = false; // red: diagonal line through circle
            boolean withDiamond = false; // lila: small diamond after circle

            switch (color) {
                case "blau"  -> { fill = new java.awt.Color(0x5B9BD5); count = 3; }
                case "grün"  -> { fill = new java.awt.Color(0x70A050); count = 1; }
                case "rot"   -> { fill = new java.awt.Color(0xC84040); count = 1; withLine = true; }
                case "lila"  -> { fill = new java.awt.Color(0x9060B0); count = 1; withDiamond = true; }
                default      -> { g2.dispose(); return; }  // gelb / unknown: nothing
            }

            int x = 0;
            for (int i = 0; i < count; i++) {
                g2.setColor(fill);
                g2.fillOval(x, cy - r, d, d);
                g2.setColor(fill.darker());
                g2.setStroke(new java.awt.BasicStroke(0.8f));
                g2.drawOval(x, cy - r, d, d);

                if (withLine) {
                    g2.setColor(java.awt.Color.WHITE);
                    g2.setStroke(new java.awt.BasicStroke(1.2f));
                    g2.drawLine(x + 2, cy + r - 2, x + d - 2, cy - r + 2);
                }
                x += d + gap;
            }

            if (withDiamond) {
                int dx = x + 1;
                int ds = 4; // half-size
                int[] xs = {dx + ds, dx + ds * 2, dx + ds, dx};
                int[] ys = {cy - ds, cy, cy + ds, cy};
                g2.setColor(fill);
                g2.fillPolygon(xs, ys, 4);
                g2.setColor(fill.darker());
                g2.setStroke(new java.awt.BasicStroke(0.8f));
                g2.drawPolygon(xs, ys, 4);
            }

            g2.dispose();
        }
    }
}
