package gui.newui;

import logic.probability.*;

import javax.swing.*;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A compact, optionally-editable panel that displays one player's snapshot:
 * name, coins, GP progress bar (named slots), card list with ×N multipliers, and EV/round.
 *
 * <p>Used by {@link LabelingWindow} to display 2–4 players side by side.
 *
 * <h2>API</h2>
 * <pre>
 *   SnapshotCard(Player player, GameState gs, int playerIndex)
 *   SnapshotCard.setPlayer(Player p)          // live update from session
 *   SnapshotCard.getEditedPlayer() → Player   // returns current edited state
 *   SnapshotCard.setEditable(boolean)         // toggle edit mode
 *   SnapshotCard.addChangeListener(...)       // called on any edit
 * </pre>
 */
public class SnapshotCard extends JPanel {

    // ── GP order (cheapest first) ─────────────────────────────────────────────
    private static final String[] GP_IDS    = {"bahnhof", "einkaufszentrum", "freizeitpark", "funkturm"};
    private static final int[]    GP_COSTS  = {4, 10, 16, 22};

    // ── Colours ─────────────────────────────────────────────────────────────
    private static final Color COLOR_BLUE   = new Color(0xADD8E6);
    private static final Color COLOR_GREEN  = new Color(0x90EE90);
    private static final Color COLOR_RED    = new Color(0xFFB6B6);
    private static final Color COLOR_PURPLE = new Color(0xD8B4FE);

    private static final Color GP_OWNED   = new Color(0x4CAF50);   // this slot is built
    private static final Color GP_LEADING = new Color(0x81C784);   // most GPs among players
    private static final Color GP_MID     = new Color(0xFFD54F);
    private static final Color GP_BEHIND  = new Color(0xEF9A9A);
    private static final Color GP_EMPTY   = new Color(0xDDDDDD);

    private static final Font NAME_FONT  = new Font("Arial", Font.BOLD,  13);
    private static final Font VALUE_FONT = new Font("Arial", Font.PLAIN, 12);
    private static final Font SMALL_FONT = new Font("Arial", Font.PLAIN, 10);
    private static final Font EV_FONT    = new Font("Arial", Font.ITALIC, 11);

    // ── State ────────────────────────────────────────────────────────────────
    private Player player;
    private final int playerIndex;
    private boolean editable = false;

    private final ArrayList<Project> allProjects;

    // ── UI components ────────────────────────────────────────────────────────
    private JComponent coinComponent;
    private Component[] cardControls;

    private final List<ChangeListener> changeListeners = new ArrayList<>();

    // ── Constructor ──────────────────────────────────────────────────────────

    public SnapshotCard(Player player, GameState gs, int playerIndex) {
        this.player = player.copy();
        this.playerIndex = playerIndex;
        this.allProjects = ProjectLoader.getAllProjects();
        this.cardControls = new Component[allProjects.size()];

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xCCCCCC), 1, true),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        setBackground(Color.WHITE);
        setMinimumSize(new Dimension(170, 220));
        setPreferredSize(new Dimension(200, 340));

        buildContent(gs);
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public void setPlayer(Player p) {
        this.player = p.copy();
        rebuildContent(null);
    }

    public Player getEditedPlayer() {
        if (!editable) return player.copy();
        int coins = coinComponent instanceof JSpinner sp ? (int) sp.getValue() : player.getCoins();
        ArrayList<Project> owned = new ArrayList<>();
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

    public void setEditable(boolean editable) {
        if (this.editable == editable) return;
        this.editable = editable;
        rebuildContent(null);
    }

    public void addChangeListener(ChangeListener l) {
        changeListeners.add(l);
    }

    // ── Build ─────────────────────────────────────────────────────────────────

    private void buildContent(GameState gs) {
        removeAll();
        cardControls = new Component[allProjects.size()];

        // Top area: name + coins + GP bar (fixed height)
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setOpaque(false);

        // Name
        JLabel nameLabel = new JLabel(player.getName());
        nameLabel.setFont(NAME_FONT);
        nameLabel.setAlignmentX(LEFT_ALIGNMENT);
        top.add(nameLabel);
        top.add(Box.createVerticalStrut(4));

        // Coins row — plain ASCII "Münzen:" label to avoid emoji rendering issues
        JPanel coinRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        coinRow.setOpaque(false);
        coinRow.setAlignmentX(LEFT_ALIGNMENT);
        JLabel coinLabel = new JLabel(Strings.snapshotCoins());
        coinLabel.setFont(VALUE_FONT);
        coinRow.add(coinLabel);

        if (editable) {
            BoundedSpinner sp = new BoundedSpinner(new SpinnerNumberModel(player.getCoins(), 0, 200, 1));
            sp.setPreferredSize(new Dimension(65, 24));
            sp.addChangeListener(e -> fireChange());
            coinComponent = sp;
        } else {
            JLabel val = new JLabel(String.valueOf(player.getCoins()));
            val.setFont(VALUE_FONT.deriveFont(Font.BOLD));
            val.setToolTipText(Strings.snapshotCoins());
            val.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) { setEditable(true); fireChange(); }
                }
            });
            coinComponent = val;
        }
        coinRow.add(coinComponent);
        top.add(coinRow);
        top.add(Box.createVerticalStrut(4));

        // GP bar — fixed max height, named slots with per-GP tooltip
        JPanel gpBar = buildGpBar(gs);
        gpBar.setAlignmentX(LEFT_ALIGNMENT);
        top.add(gpBar);
        top.add(Box.createVerticalStrut(2));

        // EV/round
        JLabel evLabel = new JLabel(buildEvText(gs));
        evLabel.setFont(EV_FONT);
        evLabel.setForeground(new Color(0x444444));
        evLabel.setAlignmentX(LEFT_ALIGNMENT);
        top.add(evLabel);

        // Top has a fixed preferred height; card list fills remaining space
        add(top, BorderLayout.NORTH);

        // Card list — takes all remaining space
        JPanel cards = editable ? buildEditPanel() : buildCardListPanel();
        JScrollPane scroll = new JScrollPane(cards,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createTitledBorder(
                Strings.isDE() ? "Karten" : "Cards"));
        add(scroll, BorderLayout.CENTER);

        revalidate();
        repaint();
    }

    private void rebuildContent(GameState gs) {
        buildContent(gs);
    }

    // ── GP bar ───────────────────────────────────────────────────────────────

    private JPanel buildGpBar(GameState gs) {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        bar.setOpaque(false);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));

        int ownGPs  = countGPs(player);
        int maxGPs  = gs != null ? maxGPs(gs) : ownGPs;
        Color fillColor = gpBarColor(ownGPs, maxGPs);

        for (int i = 0; i < 4; i++) {
            boolean owned = player.hasProject(GP_IDS[i]);
            String gpName = gpLocalizedName(i);
            JLabel slot = new JLabel(owned ? "✓" : " ");
            slot.setFont(SMALL_FONT.deriveFont(Font.BOLD));
            slot.setOpaque(true);
            slot.setBorder(BorderFactory.createLineBorder(new Color(0x888888), 1));
            slot.setPreferredSize(new Dimension(22, 18));
            slot.setHorizontalAlignment(SwingConstants.CENTER);
            slot.setBackground(owned ? fillColor : GP_EMPTY);
            slot.setForeground(owned ? Color.WHITE : new Color(0x888888));
            // Tooltip: GP name + cost + owned/not
            String tip = owned
                    ? "<html><b>" + gpName + "</b> (" + GP_COSTS[i] + " " + Strings.coinsUnit() + ") — " + (Strings.isDE() ? "gebaut" : "built") + "</html>"
                    : "<html><b>" + gpName + "</b> (" + GP_COSTS[i] + " " + Strings.coinsUnit() + ") — " + (Strings.isDE() ? "nicht gebaut" : "not built") + "</html>";
            slot.setToolTipText(tip);
            bar.add(slot);
        }

        JLabel gpText = new JLabel(" " + ownGPs + "/4");
        gpText.setFont(SMALL_FONT);
        bar.add(gpText);
        return bar;
    }

    private Color gpBarColor(int ownGPs, int maxGPs) {
        if (ownGPs == 4)        return GP_OWNED;
        if (ownGPs == maxGPs)   return GP_LEADING;
        if (ownGPs == 0)        return GP_BEHIND;
        return GP_MID;
    }

    private String gpLocalizedName(int slotIdx) {
        return switch (GP_IDS[slotIdx]) {
            case "bahnhof"       -> Strings.isDE() ? "Bahnhof"         : "Train Station";
            case "einkaufszentrum" -> Strings.isDE() ? "Einkaufszentrum" : "Shopping Mall";
            case "freizeitpark"  -> Strings.isDE() ? "Freizeitpark"    : "Amusement Park";
            case "funkturm"      -> Strings.isDE() ? "Funkturm"        : "Radio Tower";
            default -> GP_IDS[slotIdx];
        };
    }

    private int countGPs(Player p) {
        int c = 0;
        for (Project proj : p.getOwned_projects()) if (proj.isIs_grossprojekt()) c++;
        return c;
    }

    private int maxGPs(GameState gs) {
        int max = 0;
        for (Player p : gs.getPlayers()) max = Math.max(max, countGPs(p));
        return max;
    }

    // ── Card list panel (view mode) ───────────────────────────────────────────

    private JPanel buildCardListPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(true);
        panel.setBackground(Color.WHITE);

        // Collect non-GP projects, count duplicates
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Project> byId   = new LinkedHashMap<>();
        for (Project p : player.getOwned_projects()) {
            if (p.isIs_grossprojekt()) continue;
            counts.merge(p.getId(), 1, Integer::sum);
            byId.put(p.getId(), p);
        }

        if (counts.isEmpty()) {
            JLabel empty = new JLabel(Strings.snapshotCardNone());
            empty.setFont(SMALL_FONT);
            empty.setForeground(Color.GRAY);
            empty.setAlignmentX(LEFT_ALIGNMENT);
            panel.add(empty);
            return panel;
        }

        // Group by color for visual separation
        String[] colorOrder = {"blau", "grün", "rot", "lila"};
        Color[]  colorBg    = {COLOR_BLUE, COLOR_GREEN, COLOR_RED, COLOR_PURPLE};

        for (int ci = 0; ci < colorOrder.length; ci++) {
            String color = colorOrder[ci];
            boolean hasAny = false;
            for (String id : counts.keySet()) {
                if (byId.get(id).getColor().equals(color)) { hasAny = true; break; }
            }
            if (!hasAny) continue;

            Color bg = colorBg[ci];
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                Project p = byId.get(e.getKey());
                if (!p.getColor().equals(color)) continue;
                int n = e.getValue();
                String text = n > 1 ? p.getLocalizedName() + " ×" + n : p.getLocalizedName();
                JLabel lbl = new JLabel(text);
                lbl.setFont(SMALL_FONT);
                lbl.setOpaque(true);
                lbl.setBackground(bg);
                lbl.setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 4));
                lbl.setAlignmentX(LEFT_ALIGNMENT);
                lbl.setMaximumSize(new Dimension(Integer.MAX_VALUE, lbl.getPreferredSize().height + 2));
                panel.add(lbl);
            }
        }

        return panel;
    }

    // ── Edit panel (edit mode) ────────────────────────────────────────────────

    private JPanel buildEditPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);

        String[] colorOrder = {"blau", "grün", "rot", "lila", "gelb"};
        ArrayList<String> ownedIds = new ArrayList<>();
        for (Project p : player.getOwned_projects()) ownedIds.add(p.getId());

        for (String color : colorOrder) {
            boolean isMultiCopy = !color.equals("lila") && !color.equals("gelb");
            boolean hasAny = false;
            for (Project p : allProjects) if (p.getColor().equals(color)) { hasAny = true; break; }
            if (!hasAny) continue;

            JLabel header = new JLabel(colorDisplayName(color));
            header.setFont(SMALL_FONT.deriveFont(Font.BOLD));
            header.setAlignmentX(LEFT_ALIGNMENT);
            panel.add(header);

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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildEvText(GameState gs) {
        if (gs == null) return "";
        try {
            double ev = ProbabilityCalc.portfolioEvPerRound(gs, playerIndex);
            return String.format("EV/Runde: %.2f\u00A2", ev);
        } catch (Exception e) {
            return "";
        }
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

    private void fireChange() {
        for (ChangeListener l : changeListeners) l.stateChanged(null);
    }
}
