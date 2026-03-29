package gui.newui;

import com.google.gson.*;
import logic.probability.*;

import javax.swing.*;
import javax.swing.event.ChangeListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A standalone window for labeling game-state snapshots with phase ratings.
 *
 * <p>Displays 2–4 {@link SnapshotCard}s side by side, with three independent sliders
 * (no tick numbers, only endpoint labels) for Early / Mid / Late phase strength.
 * Labels are collected in memory, automatically written to {@code phase_labels.json}
 * in the working directory after each "Next Snapshot" click, and can also be exported
 * to a user-chosen location.
 *
 * <h2>Workflow</h2>
 * <ol>
 *   <li>Set the player count and turn range, then click "Generate" — or load a {@code .mkoro}
 *       file via "Load from File".</li>
 *   <li>Rate the snapshot using the three sliders (right = strong for that phase).</li>
 *   <li>Click "Next Snapshot" to save the current label (auto-written to disk) and load the next one.</li>
 *   <li>Click "Export Labels" to write all collected labels to a user-chosen file.</li>
 * </ol>
 *
 * <h2>Label format (JSON)</h2>
 * <pre>
 * [
 *   {
 *     "players": [ { "name": "…", "coins": N, "gps": N } ],
 *     "labels": { "early": 0.8, "mid": 0.3, "late": 0.1 }
 *   },
 *   …
 * ]
 * </pre>
 *
 * <p>Opened via the "Tools" menu in {@link MainWindow}.
 */
public class LabelingWindow extends JFrame {

    // ── Slider constants ──────────────────────────────────────────────────────
    private static final int SLIDER_MIN   = 0;
    private static final int SLIDER_MAX   = 100;
    private static final int SLIDER_INIT  = 50;

    /** Auto-save target: phase_labels.json in the working directory. */
    private static final Path AUTO_SAVE_PATH = Path.of("phase_labels.json");

    // ── State ─────────────────────────────────────────────────────────────────
    private GameState currentSnapshot;
    private final List<JsonObject> labels = new ArrayList<>();

    // ── UI ────────────────────────────────────────────────────────────────────
    private JPanel cardsPanel;
    private JSlider sliderEarly;
    private JSlider sliderMid;
    private JSlider sliderLate;
    private JLabel statusLabel;
    private JSpinner numPlayersSpinner;
    private JSpinner minTurnSpinner;
    private JSpinner maxTurnSpinner;

    /**
     * Opens the LabelingWindow as a non-modal frame (independent of MainWindow).
     */
    public LabelingWindow() {
        super(Strings.labelingWindowTitle());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        loadExistingLabels();
        buildUI();
        pack();
        setLocationRelativeTo(null);
        setVisible(true);
    }

    /** Loads previously saved labels from {@link #AUTO_SAVE_PATH} if it exists. */
    private void loadExistingLabels() {
        if (!Files.exists(AUTO_SAVE_PATH)) return;
        try {
            String json = Files.readString(AUTO_SAVE_PATH);
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement el : arr) labels.add(el.getAsJsonObject());
        } catch (Exception ex) {
            // Silently ignore malformed existing file — start fresh
        }
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUI() {
        setLayout(new BorderLayout(8, 8));
        getRootPane().setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        add(buildTopControls(), BorderLayout.NORTH);
        add(buildCenterArea(),  BorderLayout.CENTER);
        add(buildBottomBar(),   BorderLayout.SOUTH);
    }

    /** Top row: player count, turn range, generate button, load-from-file button. */
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

    /** Center: side-by-side SnapshotCards + three phase sliders below. */
    private JPanel buildCenterArea() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));

        // ── Card area ────────────────────────────────────────────────────────
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

        // ── Sliders ──────────────────────────────────────────────────────────
        panel.add(buildSliderPanel(), BorderLayout.SOUTH);

        return panel;
    }

    private JPanel buildSliderPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder(
                Strings.isDE() ? "Spielphase-Einschätzung" : "Phase Rating"));

        sliderEarly = makeSlider();
        sliderMid   = makeSlider();
        sliderLate  = makeSlider();

        panel.add(sliderRow(sliderEarly,
                Strings.labelingSliderEarlyLeft(), Strings.labelingSliderEarlyRight()));
        panel.add(Box.createVerticalStrut(6));
        panel.add(sliderRow(sliderMid,
                Strings.labelingSliderMidLeft(), Strings.labelingSliderMidRight()));
        panel.add(Box.createVerticalStrut(6));
        panel.add(sliderRow(sliderLate,
                Strings.labelingSliderLateLeft(), Strings.labelingSliderLateRight()));

        return panel;
    }

    /**
     * Creates one labelled slider row:
     * [left label]  [----slider----]  [right label]
     */
    private JPanel sliderRow(JSlider slider, String leftLabel, String rightLabel) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setAlignmentX(LEFT_ALIGNMENT);
        JLabel left  = new JLabel(leftLabel);
        JLabel right = new JLabel(rightLabel);
        left.setFont(new Font("Arial", Font.PLAIN, 11));
        right.setFont(new Font("Arial", Font.PLAIN, 11));
        row.add(left,   BorderLayout.WEST);
        row.add(slider, BorderLayout.CENTER);
        row.add(right,  BorderLayout.EAST);
        return row;
    }

    private JSlider makeSlider() {
        JSlider s = new JSlider(SLIDER_MIN, SLIDER_MAX, SLIDER_INIT);
        s.setPaintLabels(false);
        s.setPaintTicks(false);
        s.setPaintTrack(true);
        return s;
    }

    /** Bottom: "Next Snapshot", "Export Labels", label count. */
    private JPanel buildBottomBar() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton nextBtn = new JButton(Strings.labelingNextBtn());
        nextBtn.addActionListener(e -> saveCurrentAndNext());
        JButton exportBtn = new JButton(Strings.labelingExportBtn());
        exportBtn.addActionListener(e -> exportLabels());
        buttons.add(nextBtn);
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
        int n = (int) numPlayersSpinner.getValue();
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
        resetSliders();
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
            SnapshotCard card = new SnapshotCard(players[i], gs, i);
            cardsPanel.add(card);
        }
    }

    private void resetSliders() {
        sliderEarly.setValue(SLIDER_INIT);
        sliderMid.setValue(SLIDER_INIT);
        sliderLate.setValue(SLIDER_INIT);
    }

    // ── Label collection ──────────────────────────────────────────────────────

    private void saveCurrentAndNext() {
        if (currentSnapshot != null) {
            labels.add(buildLabelEntry(currentSnapshot));
            statusLabel.setText(Strings.labelingLabelCount(labels.size()));
            autoSave();
        }
        // Auto-generate next snapshot with same settings
        generateSnapshot();
    }

    /** Writes all current labels to {@link #AUTO_SAVE_PATH} silently (no dialog). */
    private void autoSave() {
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            JsonArray arr = new JsonArray();
            labels.forEach(arr::add);
            Files.writeString(AUTO_SAVE_PATH, gson.toJson(arr));
        } catch (IOException ex) {
            // Non-critical: auto-save failure is shown in status but doesn't block flow
            statusLabel.setText(Strings.labelingExportError(ex.getMessage()));
        }
    }

    private JsonObject buildLabelEntry(GameState gs) {
        JsonObject entry = new JsonObject();

        // Minimal snapshot summary (players)
        JsonArray players = new JsonArray();
        for (Player p : gs.getPlayers()) {
            JsonObject pObj = new JsonObject();
            pObj.addProperty("name", p.getName());
            pObj.addProperty("coins", p.getCoins());
            int gps = 0;
            for (logic.probability.Project proj : p.getOwned_projects()) {
                if (proj.isIs_grossprojekt()) gps++;
            }
            pObj.addProperty("gps", gps);
            pObj.addProperty("cards", p.getOwned_projects().size());
            players.add(pObj);
        }
        entry.add("players", players);

        // Phase labels (normalised 0.0–1.0; slider 0=left, 100=right)
        JsonObject labelObj = new JsonObject();
        labelObj.addProperty("early", sliderEarly.getValue() / 100.0);
        labelObj.addProperty("mid",   sliderMid.getValue()   / 100.0);
        labelObj.addProperty("late",  sliderLate.getValue()  / 100.0);
        entry.add("labels", labelObj);

        return entry;
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
