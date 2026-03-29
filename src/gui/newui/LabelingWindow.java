package gui.newui;

import com.google.gson.*;
import logic.probability.*;

import javax.swing.*;
import javax.swing.event.ChangeListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/**
 * A standalone window for labeling game-state snapshots with phase ratings.
 *
 * <p>Displays 2–4 {@link SnapshotCard}s side by side, with a single phase-progression
 * slider: left = Early (1,0,0), centre = Mid (0,1,0), right = Late (0,0,1), smoothly
 * interpolated between the three poles.
 *
 * <p>Labels are collected in memory, automatically written to {@code phase_labels.json}
 * in the working directory after each "Next Snapshot" click, and persist across sessions.
 * The "Calibrate" button runs {@link PhaseFitter} on all collected labels and updates
 * the live phase-detection thresholds in {@link AssistantConfig} without restart.
 *
 * <h2>Label format (JSON)</h2>
 * <pre>
 * [
 *   {
 *     "n_players": 3,
 *     "players": [
 *       { "name":"…", "coins":N, "gps":["bahnhof","einkaufszentrum"],
 *         "cards":[{"id":"weizenfeld","count":2}, …] }
 *     ],
 *     "features": { "avg_gps":…, "max_gps":…, "avg_cards":…, "avg_coins":… },
 *     "labels": { "early":0.8, "mid":0.3, "late":0.1 }
 *   }, …
 * ]
 * </pre>
 *
 * <p>Opened via the "Tools" menu in {@link MainWindow}.
 */
public class LabelingWindow extends JFrame {

    // ── Slider constants ──────────────────────────────────────────────────────
    /** 0 = full Early, 50 = full Mid, 100 = full Late */
    private static final int SLIDER_MIN  = 0;
    private static final int SLIDER_MAX  = 100;
    private static final int SLIDER_INIT = 25; // start in early/mid transition

    /** GP IDs in purchase order — must match SnapshotCard.GP_IDS */
    private static final String[] GP_IDS = {"bahnhof", "einkaufszentrum", "freizeitpark", "funkturm"};

    /** Auto-save target in the working directory. */
    static final Path AUTO_SAVE_PATH = Path.of("phase_labels.json");

    // ── State ─────────────────────────────────────────────────────────────────
    private GameState currentSnapshot;
    private final List<JsonObject> labels = new ArrayList<>();

    // ── UI ────────────────────────────────────────────────────────────────────
    private JPanel  cardsPanel;
    private JSlider phaseSlider;
    private JLabel  phaseValueLabel;
    private JLabel  statusLabel;
    private JSpinner numPlayersSpinner;
    private JSpinner minTurnSpinner;
    private JSpinner maxTurnSpinner;

    public LabelingWindow() {
        super(Strings.labelingWindowTitle());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        loadExistingLabels();
        buildUI();
        pack();
        setLocationRelativeTo(null);
        setVisible(true);
    }

    // ── Persist across sessions ───────────────────────────────────────────────

    private void loadExistingLabels() {
        if (!Files.exists(AUTO_SAVE_PATH)) return;
        try {
            String json = Files.readString(AUTO_SAVE_PATH);
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement el : arr) labels.add(el.getAsJsonObject());
        } catch (Exception ignored) {}
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUI() {
        setLayout(new BorderLayout(8, 8));
        getRootPane().setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        add(buildTopControls(), BorderLayout.NORTH);
        add(buildCenterArea(),  BorderLayout.CENTER);
        add(buildBottomBar(),   BorderLayout.SOUTH);
    }

    private JPanel buildTopControls() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        panel.add(new JLabel(Strings.labelingNumPlayers()));
        numPlayersSpinner = new JSpinner(new SpinnerNumberModel(3, 2, 4, 1));
        numPlayersSpinner.setPreferredSize(new Dimension(50, 26));
        panel.add(numPlayersSpinner);
        panel.add(new JLabel(Strings.labelingTurnRange()));
        minTurnSpinner = new JSpinner(new SpinnerNumberModel(3, 0, 100, 1));
        minTurnSpinner.setPreferredSize(new Dimension(55, 26));
        maxTurnSpinner = new JSpinner(new SpinnerNumberModel(20, 1, 200, 1));
        maxTurnSpinner.setPreferredSize(new Dimension(55, 26));
        panel.add(minTurnSpinner);
        panel.add(new JLabel("–"));
        panel.add(maxTurnSpinner);
        JButton genBtn = new JButton(Strings.labelingGenerate());
        genBtn.addActionListener(e -> generateSnapshot());
        panel.add(genBtn);
        JButton fileBtn = new JButton(Strings.labelingFromFileBtn());
        fileBtn.addActionListener(e -> loadFromFile());
        panel.add(fileBtn);
        return panel;
    }

    private JPanel buildCenterArea() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        cardsPanel = new JPanel();
        cardsPanel.setLayout(new BoxLayout(cardsPanel, BoxLayout.X_AXIS));
        cardsPanel.setBorder(BorderFactory.createTitledBorder("Snapshot"));
        JLabel placeholder = new JLabel(Strings.labelingNoSnapshot(), SwingConstants.CENTER);
        placeholder.setFont(new Font("Arial", Font.ITALIC, 12));
        placeholder.setForeground(Color.GRAY);
        cardsPanel.add(Box.createHorizontalGlue());
        cardsPanel.add(placeholder);
        cardsPanel.add(Box.createHorizontalGlue());
        panel.add(cardsPanel, BorderLayout.CENTER);
        panel.add(buildSliderPanel(), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildSliderPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder(
                Strings.isDE() ? "Spielphase" : "Game Phase"));

        // Single slider: 0=Early, 50=Mid, 100=Late
        JPanel sliderRow = new JPanel(new BorderLayout(6, 0));
        JLabel leftLbl  = new JLabel(Strings.isDE() ? "Frühphase" : "Early Game");
        JLabel rightLbl = new JLabel(Strings.isDE() ? "Endspiel"  : "Late Game");
        leftLbl.setFont(new Font("Arial", Font.PLAIN, 11));
        rightLbl.setFont(new Font("Arial", Font.PLAIN, 11));

        phaseSlider = new JSlider(SLIDER_MIN, SLIDER_MAX, SLIDER_INIT);
        phaseSlider.setPaintLabels(false);
        phaseSlider.setPaintTicks(false);
        phaseSlider.setPaintTrack(true);

        // Live value display: e.g. "Early 80% · Mid 20% · Late 0%"
        phaseValueLabel = new JLabel(phaseText(SLIDER_INIT), SwingConstants.CENTER);
        phaseValueLabel.setFont(new Font("Arial", Font.PLAIN, 11));
        phaseValueLabel.setForeground(new Color(0x444444));
        phaseSlider.addChangeListener(e -> phaseValueLabel.setText(phaseText(phaseSlider.getValue())));

        sliderRow.add(leftLbl,   BorderLayout.WEST);
        sliderRow.add(phaseSlider, BorderLayout.CENTER);
        sliderRow.add(rightLbl,  BorderLayout.EAST);
        sliderRow.setAlignmentX(LEFT_ALIGNMENT);

        panel.add(sliderRow);
        panel.add(phaseValueLabel);
        return panel;
    }

    /** Interpolates the slider position into (early, mid, late) values and formats them. */
    static double[] sliderToPhaseValues(int pos) {
        // pos in [0..100]:  0=Early peak, 50=Mid peak, 100=Late peak
        // Piecewise linear triangles:
        //   early: 1 at 0,  0 at 50,  0 at 100
        //   mid:   0 at 0,  1 at 50,  0 at 100
        //   late:  0 at 0,  0 at 50,  1 at 100
        double t = pos / 100.0;
        double early, mid, late;
        if (t <= 0.5) {
            early = 1.0 - 2 * t;
            mid   = 2 * t;
            late  = 0.0;
        } else {
            early = 0.0;
            mid   = 2.0 - 2 * t;
            late  = 2 * t - 1.0;
        }
        return new double[]{early, mid, late};
    }

    private String phaseText(int pos) {
        double[] v = sliderToPhaseValues(pos);
        if (Strings.isDE()) {
            return String.format("Früh %.0f%%  ·  Mitte %.0f%%  ·  Spät %.0f%%",
                    v[0]*100, v[1]*100, v[2]*100);
        } else {
            return String.format("Early %.0f%%  ·  Mid %.0f%%  ·  Late %.0f%%",
                    v[0]*100, v[1]*100, v[2]*100);
        }
    }

    private JPanel buildBottomBar() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));

        JButton nextBtn = new JButton(Strings.labelingNextBtn());
        nextBtn.addActionListener(e -> saveCurrentAndNext());

        JButton calibrateBtn = new JButton(Strings.isDE() ? "Kalibrieren…" : "Calibrate…");
        calibrateBtn.setToolTipText(Strings.isDE()
                ? "Regression über alle Labels → neue Phasenschwellwerte"
                : "Regression over all labels → updated phase thresholds");
        calibrateBtn.addActionListener(e -> runCalibration());

        JButton exportBtn = new JButton(Strings.labelingExportBtn());
        exportBtn.addActionListener(e -> exportLabels());

        buttons.add(nextBtn);
        buttons.add(calibrateBtn);
        buttons.add(exportBtn);
        panel.add(buttons, BorderLayout.WEST);

        statusLabel = new JLabel(Strings.labelingLabelCount(labels.size()));
        statusLabel.setFont(new Font("Arial", Font.ITALIC, 11));
        statusLabel.setForeground(new Color(0x555555));
        panel.add(statusLabel, BorderLayout.EAST);
        return panel;
    }

    // ── Snapshot management ───────────────────────────────────────────────────

    private void generateSnapshot() {
        int n    = (int) numPlayersSpinner.getValue();
        int minT = (int) minTurnSpinner.getValue();
        int maxT = (int) maxTurnSpinner.getValue();
        if (maxT < minT) { maxTurnSpinner.setValue(minT); maxT = minT; }
        GameState gs = SnapshotGenerator.generate(n, minT, maxT);
        if (gs == null) {
            JOptionPane.showMessageDialog(this,
                    Strings.isDE() ? "Simulation Timeout — kein Snapshot erzeugt."
                                   : "Simulation timeout — no snapshot produced.",
                    Strings.isDE() ? "Fehler" : "Error", JOptionPane.WARNING_MESSAGE);
            return;
        }
        showSnapshot(gs);
    }

    private void loadFromFile() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("Machi Koro saves (*.mkoro)", "mkoro"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            GameState gs = SnapshotGenerator.generateFromFile(fc.getSelectedFile().toPath());
            if (gs == null) {
                JOptionPane.showMessageDialog(this,
                        Strings.isDE() ? "Datei enthält keine Züge." : "File contains no turns.",
                        Strings.isDE() ? "Fehler" : "Error", JOptionPane.WARNING_MESSAGE);
                return;
            }
            showSnapshot(gs);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, Strings.labelingLoadError(ex.getMessage()),
                    Strings.isDE() ? "Fehler" : "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showSnapshot(GameState gs) {
        currentSnapshot = gs;
        phaseSlider.setValue(SLIDER_INIT);
        rebuildCards(gs);
        revalidate();
        repaint();
    }

    private void rebuildCards(GameState gs) {
        cardsPanel.removeAll();
        cardsPanel.setLayout(new BoxLayout(cardsPanel, BoxLayout.X_AXIS));
        Player[] players = gs.getPlayers();
        for (int i = 0; i < players.length; i++) {
            if (i > 0) cardsPanel.add(Box.createHorizontalStrut(8));
            cardsPanel.add(new SnapshotCard(players[i], gs, i));
        }
    }

    // ── Label collection ──────────────────────────────────────────────────────

    private void saveCurrentAndNext() {
        if (currentSnapshot != null) {
            labels.add(buildLabelEntry(currentSnapshot));
            statusLabel.setText(Strings.labelingLabelCount(labels.size()));
            autoSave();
        }
        generateSnapshot();
    }

    private void autoSave() {
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            JsonArray arr = new JsonArray();
            labels.forEach(arr::add);
            Files.writeString(AUTO_SAVE_PATH, gson.toJson(arr));
        } catch (IOException ex) {
            statusLabel.setText(Strings.labelingExportError(ex.getMessage()));
        }
    }

    private JsonObject buildLabelEntry(GameState gs) {
        JsonObject entry = new JsonObject();
        entry.addProperty("n_players", gs.getPlayers().length);

        // Per-player detail: exact GPs + card counts by id
        JsonArray playersArr = new JsonArray();
        for (Player p : gs.getPlayers()) {
            JsonObject pObj = new JsonObject();
            pObj.addProperty("name",  p.getName());
            pObj.addProperty("coins", p.getCoins());

            // Which GPs are built (list of ids in purchase order)
            JsonArray gpsArr = new JsonArray();
            for (String gpId : GP_IDS) {
                if (p.hasProject(gpId)) gpsArr.add(gpId);
            }
            pObj.addProperty("gp_count", gpsArr.size());
            pObj.add("gps", gpsArr);

            // Non-GP cards: aggregate by id with count
            Map<String, Integer> cardCounts = new LinkedHashMap<>();
            for (Project proj : p.getOwned_projects()) {
                if (!proj.isIs_grossprojekt()) {
                    cardCounts.merge(proj.getId(), 1, Integer::sum);
                }
            }
            pObj.addProperty("non_gp_cards", cardCounts.values().stream().mapToInt(i->i).sum());
            JsonArray cardsArr = new JsonArray();
            for (Map.Entry<String, Integer> e : cardCounts.entrySet()) {
                JsonObject c = new JsonObject();
                c.addProperty("id",    e.getKey());
                c.addProperty("count", e.getValue());
                cardsArr.add(c);
            }
            pObj.add("cards", cardsArr);

            playersArr.add(pObj);
        }
        entry.add("players", playersArr);

        // Derived features (for PhaseFitter — pre-computed so the fitter is simple)
        Player[] ps = gs.getPlayers();
        int n = ps.length;
        double avgGps   = Arrays.stream(ps).mapToInt(p -> (int) p.getOwned_projects().stream()
                              .filter(Project::isIs_grossprojekt).count()).average().orElse(0);
        int    maxGps   = Arrays.stream(ps).mapToInt(p -> (int) p.getOwned_projects().stream()
                              .filter(Project::isIs_grossprojekt).count()).max().orElse(0);
        double avgCards = Arrays.stream(ps).mapToLong(p -> p.getOwned_projects().stream()
                              .filter(proj -> !proj.isIs_grossprojekt()).count()).average().orElse(0);
        double avgCoins = Arrays.stream(ps).mapToInt(Player::getCoins).average().orElse(0);
        JsonObject features = new JsonObject();
        features.addProperty("avg_gps",   avgGps);
        features.addProperty("max_gps",   maxGps);
        features.addProperty("avg_cards", avgCards);
        features.addProperty("avg_coins", avgCoins);
        entry.add("features", features);

        // Phase labels from single slider
        double[] v = sliderToPhaseValues(phaseSlider.getValue());
        JsonObject labelObj = new JsonObject();
        labelObj.addProperty("early", Math.round(v[0] * 100.0) / 100.0);
        labelObj.addProperty("mid",   Math.round(v[1] * 100.0) / 100.0);
        labelObj.addProperty("late",  Math.round(v[2] * 100.0) / 100.0);
        entry.add("labels", labelObj);

        return entry;
    }

    // ── Calibration ───────────────────────────────────────────────────────────

    private void runCalibration() {
        if (labels.size() < 10) {
            JOptionPane.showMessageDialog(this,
                    Strings.isDE()
                        ? "Zu wenige Labels (" + labels.size() + "). Mindestens 10 nötig."
                        : "Too few labels (" + labels.size() + "). At least 10 needed.",
                    Strings.isDE() ? "Kalibrierung" : "Calibration",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        PhaseFitter.FitResult result = PhaseFitter.fit(labels);
        // Apply to live AssistantConfig
        PhaseFitter.applyToConfig(result);

        // Show result dialog
        String msg = Strings.isDE()
            ? String.format(
                "Kalibrierung abgeschlossen (%d Labels)%n%n"
                + "Neue Schwellwerte (live aktiv):%n"
                + "  LATE_GP_THRESHOLD:      %.2f → %d%n"
                + "  EARLY_AVG_EV_THRESHOLD: %.2f (fix, EV-basiert)%n%n"
                + "R² Early: %.3f   Mid: %.3f   Late: %.3f",
                labels.size(),
                result.rawLateGpThreshold(), result.lateGpThreshold(),
                result.earlyEvThreshold(),
                result.r2Early(), result.r2Mid(), result.r2Late())
            : String.format(
                "Calibration complete (%d labels)%n%n"
                + "New thresholds (live):%n"
                + "  LATE_GP_THRESHOLD:      %.2f → %d%n"
                + "  EARLY_AVG_EV_THRESHOLD: %.2f (fixed, EV-based)%n%n"
                + "R² Early: %.3f   Mid: %.3f   Late: %.3f",
                labels.size(),
                result.rawLateGpThreshold(), result.lateGpThreshold(),
                result.earlyEvThreshold(),
                result.r2Early(), result.r2Mid(), result.r2Late());
        JOptionPane.showMessageDialog(this, msg,
                Strings.isDE() ? "Kalibrierung" : "Calibration",
                JOptionPane.INFORMATION_MESSAGE);
        statusLabel.setText(Strings.isDE()
                ? "Kalibriert — " + Strings.labelingLabelCount(labels.size())
                : "Calibrated — " + Strings.labelingLabelCount(labels.size()));
    }

    // ── Export ────────────────────────────────────────────────────────────────

    private void exportLabels() {
        if (labels.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    Strings.isDE() ? "Keine Labels vorhanden." : "No labels to export.",
                    Strings.isDE() ? "Hinweis" : "Note", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("phase_labels.json"));
        fc.setFileFilter(new FileNameExtensionFilter("JSON (*.json)", "json"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = fc.getSelectedFile().toPath();
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            JsonArray arr = new JsonArray();
            labels.forEach(arr::add);
            Files.writeString(path, gson.toJson(arr));
            statusLabel.setText(Strings.labelingExportSuccess(path.getFileName().toString()));
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, Strings.labelingExportError(ex.getMessage()),
                    Strings.isDE() ? "Export-Fehler" : "Export Error", JOptionPane.ERROR_MESSAGE);
        }
    }
}
