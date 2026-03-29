package gui.newui;

import logic.probability.*;

import javax.swing.*;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A compact, optionally-editable panel that displays one player's snapshot:
 * name, coins, GP progress bar, card-colour chip summary, and EV/round.
 *
 * <p>Used by {@link LabelingWindow} to display 2–4 players side by side.
 * When editable, double-clicking on the coin value or card chip opens an
 * inline {@link JSpinner} or {@link JCheckBox} for modification.
 *
 * <h2>API</h2>
 * <pre>
 *   SnapshotCard(Player player, GameState gs, int playerIndex)
 *   SnapshotCard.setPlayer(Player p)          // live update from session
 *   SnapshotCard.getEditedPlayer() → Player   // returns current edited state
 *   SnapshotCard.setEditable(boolean)         // toggle edit mode
 *   SnapshotCard.addChangeListener(...)       // called on any edit
 * </pre>
 *
 * <p><b>.mkoro compatibility:</b> {@link #getEditedPlayer()} returns a {@link Player}
 * directly usable with {@link GameStateBuilder}, so snapshots can be fed into
 * {@link GameSession#fromSnapshot} without additional conversion.
 */
public class SnapshotCard extends JPanel {

    // ── Colours ─────────────────────────────────────────────────────────────
    private static final Color COLOR_BLUE   = new Color(0xADD8E6);
    private static final Color COLOR_GREEN  = new Color(0x90EE90);
    private static final Color COLOR_RED    = new Color(0xFFB6B6);
    private static final Color COLOR_PURPLE = new Color(0xD8B4FE);
    private static final Color COLOR_YELLOW = new Color(0xFFEC99);

    private static final Color GP_FULL    = new Color(0x4CAF50);  // all 4 GPs
    private static final Color GP_LEADING = new Color(0x81C784);  // most among players
    private static final Color GP_MID     = new Color(0xFFD54F);  // average
    private static final Color GP_BEHIND  = new Color(0xEF9A9A);  // fewest
    private static final Color GP_EMPTY   = new Color(0xDDDDDD);

    private static final Font NAME_FONT    = new Font("Arial", Font.BOLD,  13);
    private static final Font VALUE_FONT   = new Font("Arial", Font.PLAIN, 12);
    private static final Font SMALL_FONT   = new Font("Arial", Font.PLAIN, 10);
    private static final Font EV_FONT      = new Font("Arial", Font.ITALIC, 11);

    // ── State ────────────────────────────────────────────────────────────────
    private Player player;
    private final int playerIndex;
    private final int numPlayers;
    private boolean editable = false;

    private final ArrayList<Project> allProjects;

    // ── UI components ────────────────────────────────────────────────────────
    /** Coin display — either a plain JLabel (view mode) or a BoundedSpinner (edit mode). */
    private JComponent coinComponent;
    /** GP colour bar — one JLabel per slot (0–3). */
    private JLabel[] gpSlots;
    /** Per-project edit control: JSpinner (multi-copy) or JCheckBox (unique). */
    private Component[] cardControls;
    /** Card-chip area (rebuilt on setEditable toggle). */
    private JPanel chipsPanel;
    /** EV label — updated by setPlayer and after edits. */
    private JLabel evLabel;
    /** GP-bar container — rebuilt when editable changes. */
    private JPanel gpBar;

    private final List<ChangeListener> changeListeners = new ArrayList<>();

    // ── Constructor ──────────────────────────────────────────────────────────

    /**
     * Creates a SnapshotCard for the given player.
     *
     * @param player      initial player state to display
     * @param gs          full game state (used to determine GP-bar colour relative to opponents)
     * @param playerIndex 0-based index of this player in gs.getPlayers()
     */
    public SnapshotCard(Player player, GameState gs, int playerIndex) {
        this.player = player.copy();
        this.playerIndex = playerIndex;
        this.numPlayers = gs.getPlayers().length;
        this.allProjects = ProjectLoader.getAllProjects();
        this.cardControls = new Component[allProjects.size()];

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xCCCCCC), 1, true),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        setBackground(Color.WHITE);
        setMinimumSize(new Dimension(160, 200));
        setPreferredSize(new Dimension(195, 280));

        buildContent(gs);
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Replaces the displayed player with {@code p}. Rebuilds the view but preserves
     * any in-progress edits in the coin spinner when in edit mode.
     */
    public void setPlayer(Player p) {
        this.player = p.copy();
        rebuildContent(null);
    }

    /**
     * Returns a {@link Player} reflecting the current edited state — either the
     * spinner/checkbox values (edit mode) or the last set player (view mode).
     *
     * <p>The returned player is a new copy, safe to pass to {@link GameStateBuilder}.
     */
    public Player getEditedPlayer() {
        if (!editable) return player.copy();

        int coins = coinComponent instanceof JSpinner sp ? (int) sp.getValue() : player.getCoins();
        ArrayList<Project> owned = new ArrayList<>();
        ArrayList<String> ownedIds = new ArrayList<>();
        for (Project p : player.getOwned_projects()) ownedIds.add(p.getId());

        for (int j = 0; j < allProjects.size(); j++) {
            Component ctrl = cardControls[j];
            if (ctrl == null) continue;
            Project proj = allProjects.get(j);
            if (ctrl instanceof JSpinner sp) {
                int count = (int) sp.getValue();
                for (int k = 0; k < count; k++) owned.add(proj);
            } else if (ctrl instanceof JCheckBox cb) {
                if (cb.isSelected()) owned.add(proj);
            }
        }
        return new Player(player.getName(), coins, owned);
    }

    /**
     * Toggles edit mode. In edit mode, coin and card chips become interactive
     * spinners/checkboxes. In view mode, compact chip display is shown.
     */
    public void setEditable(boolean editable) {
        if (this.editable == editable) return;
        this.editable = editable;
        rebuildContent(null);
    }

    /**
     * Adds a listener that is called whenever the user edits any field (coins or cards).
     */
    public void addChangeListener(ChangeListener l) {
        changeListeners.add(l);
    }

    // ── Internal build ───────────────────────────────────────────────────────

    private void buildContent(GameState gs) {
        removeAll();
        cardControls = new Component[allProjects.size()];

        // ── Name ──────────────────────────────────────────────────────────────
        JLabel nameLabel = new JLabel(player.getName());
        nameLabel.setFont(NAME_FONT);
        nameLabel.setAlignmentX(LEFT_ALIGNMENT);
        add(nameLabel);
        add(Box.createVerticalStrut(6));

        // ── Coins ─────────────────────────────────────────────────────────────
        JPanel coinRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        coinRow.setOpaque(false);
        coinRow.setAlignmentX(LEFT_ALIGNMENT);
        JLabel coinIcon = new JLabel("🪙");
        coinIcon.setFont(VALUE_FONT);
        coinRow.add(coinIcon);

        if (editable) {
            BoundedSpinner sp = new BoundedSpinner(new SpinnerNumberModel(player.getCoins(), 0, 200, 1));
            sp.setPreferredSize(new Dimension(65, 24));
            sp.addChangeListener(e -> fireChange());
            coinComponent = sp;
        } else {
            JLabel coinLabel = new JLabel(String.valueOf(player.getCoins()));
            coinLabel.setFont(VALUE_FONT);
            if (!editable) {
                coinLabel.setToolTipText(Strings.snapshotCoins());
                coinLabel.addMouseListener(new MouseAdapter() {
                    @Override public void mouseClicked(MouseEvent e) {
                        if (e.getClickCount() == 2) {
                            setEditable(true);
                            fireChange();
                        }
                    }
                });
            }
            coinComponent = coinLabel;
        }
        coinRow.add(coinComponent);
        add(coinRow);
        add(Box.createVerticalStrut(6));

        // ── GP progress bar ───────────────────────────────────────────────────
        int ownGPs = countGPs(player);
        int maxGPs = gs != null ? maxGPs(gs) : ownGPs;
        gpBar = buildGpBar(ownGPs, maxGPs);
        gpBar.setAlignmentX(LEFT_ALIGNMENT);
        add(gpBar);
        add(Box.createVerticalStrut(6));

        // ── Card chips / edit controls ────────────────────────────────────────
        chipsPanel = editable ? buildEditPanel() : buildChipPanel();
        chipsPanel.setAlignmentX(LEFT_ALIGNMENT);
        JScrollPane scroll = new JScrollPane(chipsPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setAlignmentX(LEFT_ALIGNMENT);
        scroll.setBorder(null);
        scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, editable ? 160 : 100));
        scroll.setPreferredSize(new Dimension(175, editable ? 150 : 90));
        add(scroll);
        add(Box.createVerticalStrut(4));

        // ── EV/round ──────────────────────────────────────────────────────────
        evLabel = new JLabel(buildEvText(gs));
        evLabel.setFont(EV_FONT);
        evLabel.setForeground(new Color(0x444444));
        evLabel.setAlignmentX(LEFT_ALIGNMENT);
        add(evLabel);

        revalidate();
        repaint();
    }

    /** Rebuilds content in place; {@code gs} may be null (EV label shows last known). */
    private void rebuildContent(GameState gs) {
        buildContent(gs);
    }

    // ── GP bar ───────────────────────────────────────────────────────────────

    private JPanel buildGpBar(int ownGPs, int maxGPs) {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        bar.setOpaque(false);
        gpSlots = new JLabel[4];
        for (int i = 0; i < 4; i++) {
            JLabel slot = new JLabel("  ");
            slot.setOpaque(true);
            slot.setBorder(BorderFactory.createLineBorder(new Color(0x999999), 1));
            slot.setPreferredSize(new Dimension(18, 18));
            slot.setBackground(i < ownGPs ? gpColor(ownGPs, maxGPs) : GP_EMPTY);
            slot.setToolTipText(ownGPs + " / 4 GPs");
            gpSlots[i] = slot;
            bar.add(slot);
        }
        JLabel gpText = new JLabel("  " + ownGPs + "/4");
        gpText.setFont(SMALL_FONT);
        bar.add(gpText);
        return bar;
    }

    private Color gpColor(int ownGPs, int maxGPs) {
        if (ownGPs == 4) return GP_FULL;
        if (ownGPs == maxGPs) return GP_LEADING;
        if (ownGPs == 0) return GP_BEHIND;
        return GP_MID;
    }

    private int countGPs(Player p) {
        int count = 0;
        for (Project proj : p.getOwned_projects()) {
            if (proj.isIs_grossprojekt()) count++;
        }
        return count;
    }

    private int maxGPs(GameState gs) {
        int max = 0;
        for (Player p : gs.getPlayers()) max = Math.max(max, countGPs(p));
        return max;
    }

    // ── Chip panel (view mode) ────────────────────────────────────────────────

    private JPanel buildChipPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new SnapshotDialog.WrapLayout(FlowLayout.LEFT, 3, 2));
        panel.setOpaque(false);

        // Count cards by color
        int blue = 0, green = 0, red = 0, purple = 0, yellow = 0;
        for (Project p : player.getOwned_projects()) {
            switch (p.getColor()) {
                case "blau"  -> blue++;
                case "grün"  -> green++;
                case "rot"   -> red++;
                case "lila"  -> purple++;
                case "gelb"  -> yellow++;
            }
        }

        if (blue   > 0) panel.add(chip(blue,   COLOR_BLUE,   Strings.snapshotColorBlauShort()));
        if (green  > 0) panel.add(chip(green,  COLOR_GREEN,  Strings.snapshotColorGrünShort()));
        if (red    > 0) panel.add(chip(red,    COLOR_RED,    Strings.snapshotColorRotShort()));
        if (purple > 0) panel.add(chip(purple, COLOR_PURPLE, Strings.snapshotColorLilaShort()));
        if (yellow > 0) panel.add(chip(yellow, COLOR_YELLOW, Strings.snapshotColorGelbShort()));

        if (blue + green + red + purple + yellow == 0) {
            JLabel empty = new JLabel(Strings.snapshotCardNone());
            empty.setFont(SMALL_FONT);
            empty.setForeground(Color.GRAY);
            panel.add(empty);
        }

        // Tooltip: card names grouped by color
        StringBuilder sb = new StringBuilder("<html>");
        appendNames(sb, player, "blau");
        appendNames(sb, player, "grün");
        appendNames(sb, player, "rot");
        appendNames(sb, player, "lila");
        appendNames(sb, player, "gelb");
        sb.append("</html>");
        panel.setToolTipText(sb.toString());

        return panel;
    }

    private JLabel chip(int count, Color bg, String colorLabel) {
        JLabel chip = new JLabel(colorLabel + (count > 1 ? " ×" + count : ""));
        chip.setFont(SMALL_FONT);
        chip.setOpaque(true);
        chip.setBackground(bg);
        chip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(bg.darker(), 1, true),
                BorderFactory.createEmptyBorder(1, 4, 1, 4)));
        return chip;
    }

    private void appendNames(StringBuilder sb, Player p, String color) {
        boolean first = true;
        for (Project proj : p.getOwned_projects()) {
            if (!proj.getColor().equals(color)) continue;
            if (first) { sb.append("<b>").append(colorDisplayName(color)).append(":</b> "); first = false; }
            else sb.append(", ");
            sb.append(proj.getLocalizedName());
        }
        if (!first) sb.append("<br>");
    }

    private String colorDisplayName(String color) {
        return switch (color) {
            case "blau"  -> Strings.snapshotColorBlauShort();
            case "grün"  -> Strings.snapshotColorGrünShort();
            case "rot"   -> Strings.snapshotColorRotShort();
            case "lila"  -> Strings.snapshotColorLilaShort();
            case "gelb"  -> Strings.snapshotColorGelbShort();
            default      -> color;
        };
    }

    // ── Edit panel (edit mode) ─────────────────────────────────────────────────

    private JPanel buildEditPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);

        String[] colorOrder = {"blau", "grün", "rot", "lila", "gelb"};
        ArrayList<String> ownedIds = new ArrayList<>();
        for (Project p : player.getOwned_projects()) ownedIds.add(p.getId());

        for (String color : colorOrder) {
            boolean hasAny = false;
            for (Project p : allProjects) {
                if (p.getColor().equals(color)) { hasAny = true; break; }
            }
            if (!hasAny) continue;

            JLabel header = new JLabel(colorDisplayName(color));
            header.setFont(SMALL_FONT.deriveFont(Font.BOLD));
            header.setAlignmentX(LEFT_ALIGNMENT);
            panel.add(header);

            boolean isMultiCopy = color.equals("blau") || color.equals("grün") || color.equals("rot");
            for (int j = 0; j < allProjects.size(); j++) {
                Project p = allProjects.get(j);
                if (!p.getColor().equals(color)) continue;

                JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 1));
                row.setOpaque(false);
                row.setAlignmentX(LEFT_ALIGNMENT);
                JLabel nameLabel = new JLabel(p.getLocalizedName());
                nameLabel.setFont(SMALL_FONT);
                row.add(nameLabel);

                if (isMultiCopy) {
                    boolean isStarter = p.getId().equals("weizenfeld") || p.getId().equals("bäckerei");
                    int maxCopies = isStarter ? 7 : 6;
                    int current = Collections.frequency(ownedIds, p.getId());
                    BoundedSpinner sp = new BoundedSpinner(new SpinnerNumberModel(current, 0, maxCopies, 1));
                    sp.setPreferredSize(new Dimension(50, 22));
                    sp.addChangeListener(e -> fireChange());
                    cardControls[j] = sp;
                    row.add(sp);
                } else {
                    JCheckBox cb = new JCheckBox();
                    cb.setSelected(ownedIds.contains(p.getId()));
                    cb.setOpaque(false);
                    cb.addItemListener(e -> fireChange());
                    cardControls[j] = cb;
                    row.add(cb);
                }
                panel.add(row);
            }
        }
        return panel;
    }

    // ── EV label ─────────────────────────────────────────────────────────────

    private String buildEvText(GameState gs) {
        if (gs == null) return "";
        try {
            double ev = ProbabilityCalc.portfolioEvPerRound(gs, playerIndex);
            return String.format("EV/Runde: %.2f¢", ev);
        } catch (Exception e) {
            return "";
        }
    }

    // ── Change notification ───────────────────────────────────────────────────

    private void fireChange() {
        for (ChangeListener l : changeListeners) l.stateChanged(null);
    }
}
