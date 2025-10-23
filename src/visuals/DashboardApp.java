package visuals;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.awt.geom.Arc2D;

/**
 * Eine Java Swing-Implementierung des Dashboard-UI-Plans.
 * Diese Datei enthält alle notwendigen Klassen als innere Klassen, 
 * um sie einfach kompilierbar und lauffähig zu machen.
 */
public class DashboardApp {

    // --- Datenmodelle (basierend auf der JSON-Spezifikation) ---

    // Enum für den Zustand der Radsegmente
    enum WheelState {
        WHITE, GREY, LIGHTBLUE, DARKBLUE
    }

    // Modell für ein einzelnes Radsegment
    static class WheelSegment {
        int index;
        WheelState state; // 'GREY' ist gesperrt, 'WHITE' ist der Standard
        boolean selected;

        public WheelSegment(int index, WheelState state, boolean selected) {
            this.index = index;
            this.state = state;
            this.selected = selected;
        }
    }

    // Modell für das gesamte Rad
    static class WheelModel {
        List<WheelSegment> segments = new ArrayList<>();
        // Hält den Index des aktuell ausgewählten Segments (falls nur eines erlaubt ist)
        // oder könnte verwendet werden, um die "aktuelle Zahl" in der Mitte anzuzeigen
        int currentSelectionNumber = 0;

        public WheelModel() {
            // Initialisiere 12 Segmente
            for (int i = 0; i < 12; i++) {
                // Beispiel: Segment 10 (Index 9) ist gesperrt (GREY)
                WheelState initialState = (i == 9) ? WheelState.GREY : WheelState.WHITE;
                segments.add(new WheelSegment(i + 1, initialState, false));
            }
        }
    }

    // Modell für den Spieler
    static class Player {
        String name;
        int coins;
        boolean[] bigProjects;

        public Player(String name, int coins, boolean[] bigProjects) {
            this.name = name;
            this.coins = coins;
            this.bigProjects = bigProjects;
        }
    }

    // Modell für ein Projekt
    static class Project {
        String id;
        String name;
        int cost;
        boolean owned;
        Color color; // Vereinfacht von String[] zu einer einzigen Farbe
        String description;
        int timesOwned;
        boolean buyable;
        boolean sellable;

        public Project(String id, String name, int cost, boolean owned, Color color,
                       String description, int timesOwned, boolean buyable, boolean sellable) {
            this.id = id;
            this.name = name;
            this.cost = cost;
            this.owned = owned;
            this.color = color;
            this.description = description;
            this.timesOwned = timesOwned;
            this.buyable = buyable;
            this.sellable = sellable;
        }
    }

    // --- Ende der Datenmodelle ---


    // --- UI-Hauptkomponenten ---

    private JFrame frame;
    private JPanel ownedProjectsListPanel;
    private JPanel availableProjectsListPanel;
    private JPanel projectActionBar;
    private JLabel coinsBadge;
    private JButton buyButton, sellButton, descButton;
    private WheelPanel wheelPanel;
    private JButton rollButton;

    // --- Anwendungsstatus ---
    private Player player;
    private List<Project> allProjects;
    private Project activeProject = null;
    private WheelModel wheelModel;

    // Map, um Projekt-IDs auf ihre UI-Panels abzubilden (für einfaches Verschieben)
    private Map<String, ProjectCardPanel> projectCardMap = new HashMap<>();

    public static void main(String[] args) {
        // Führe die GUI im Swing Event Dispatch Thread (EDT) aus
        SwingUtilities.invokeLater(() -> {
            try {
                // Ein etwas moderneres Look-and-Feel setzen
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                e.printStackTrace();
            }
            new DashboardApp().createAndShowGUI();
        });
    }

    /**
     * Lädt die Beispieldaten
     */
    private void loadData() {
        player = new Player("Player 1", 8, new boolean[]{true, false, true, false});
        wheelModel = new WheelModel();

        allProjects = new ArrayList<>();
        allProjects.add(new Project("proj-01", "Project Alpha", 3, true, Color.BLUE, "Desc Alpha", 1, false, true));
        allProjects.add(new Project("proj-02", "Project Beta", 5, false, Color.GREEN, "Desc Beta", 0, true, false));
        allProjects.add(new Project("proj-03", "Project Gamma", 2, false, Color.ORANGE, "Desc Gamma", 0, true, false));
        allProjects.add(new Project("proj-04", "Project Delta", 7, true, Color.RED, "Desc Delta", 2, false, true));
        allProjects.add(new Project("proj-05", "Project Epsilon", 1, false, Color.CYAN, "Desc Epsilon", 0, true, false));
    }

    /**
     * Initialisiert und zeigt die Haupt-GUI
     */
    private void createAndShowGUI() {
        loadData();

        frame = new JFrame("Player Dashboard");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout(10, 10)); // 10px Lücken

        // 1. Header erstellen
        frame.add(createHeaderPanel(), BorderLayout.NORTH);

        // 2. Haupt-Spalten-Layout erstellen
        frame.add(createMainColumnsPanel(), BorderLayout.CENTER);

        // 3. Untere Aktionsleiste erstellen (anfangs unsichtbar)
        frame.add(createProjectActionBar(), BorderLayout.SOUTH);

        // UI initial füllen
        populateProjectLists();
        updateCoinsBadge();

        frame.setMinimumSize(new Dimension(1000, 700));
        frame.setLocationRelativeTo(null); // Zentrieren
        frame.setVisible(true);
    }

    /**
     * Erstellt das Header-Panel (Name, Toggles, Münzen)
     */
    private JPanel createHeaderPanel() {
        JPanel headerPanel = new JPanel(new BorderLayout(10, 10));
        headerPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Spielername links
        JLabel playerNameLabel = new JLabel(player.name + " : Overview");
        playerNameLabel.setFont(playerNameLabel.getFont().deriveFont(Font.BOLD, 16f));
        headerPanel.add(playerNameLabel, BorderLayout.WEST);

        // "Big Project" Toggles in der Mitte
        JPanel togglesPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 0));
        for (int i = 0; i < player.bigProjects.length; i++) {
            JCheckBox toggle = new JCheckBox("P" + (i + 1), player.bigProjects[i]);
            toggle.setToolTipText("Toggle Big Project " + (i + 1));
            togglesPanel.add(toggle);
        }
        headerPanel.add(togglesPanel, BorderLayout.CENTER);

        // Münz-Badge rechts
        coinsBadge = new JLabel();
        coinsBadge.setFont(coinsBadge.getFont().deriveFont(Font.BOLD, 14f));
        coinsBadge.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(Color.GRAY, 1, true), // Abgerundeter Rand
                new EmptyBorder(5, 10, 5, 10)
        ));
        // Popover-Ersatz: ToolTip
        coinsBadge.setToolTipText("Zeigt Spieler-Münzen. (Popover-Details hier)");
        headerPanel.add(coinsBadge, BorderLayout.EAST);

        return headerPanel;
    }

    /**
     * Erstellt das 3-Spalten-Hauptlayout
     */
    private JPanel createMainColumnsPanel() {
        JPanel mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBorder(new EmptyBorder(0, 10, 0, 10));
        GridBagConstraints gbc = new GridBagConstraints();

        // Panel für die Liste der "Owned" Projekte
        ownedProjectsListPanel = new JPanel();
        ownedProjectsListPanel.setLayout(new BoxLayout(ownedProjectsListPanel, BoxLayout.Y_AXIS));

        // Panel für die Liste der "Available" Projekte
        availableProjectsListPanel = new JPanel();
        availableProjectsListPanel.setLayout(new BoxLayout(availableProjectsListPanel, BoxLayout.Y_AXIS));

        // Spalte 1: Owned Projects (schmaler)
        gbc.fill = GridBagConstraints.BOTH;
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0.25; // Nimmt 25% der Breite
        gbc.weighty = 1.0;
        mainPanel.add(createProjectColumn("Owned Projects", ownedProjectsListPanel), gbc);

        // Trennlinie (simuliert "dashed vertical rule")
        gbc.gridx = 1;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.VERTICAL;
        mainPanel.add(new JSeparator(SwingConstants.VERTICAL), gbc);

        // Spalte 2: Available Projects (breiter)
        gbc.gridx = 2;
        gbc.weightx = 0.5; // Nimmt 50% der Breite
        mainPanel.add(createProjectColumn("Available Projects", availableProjectsListPanel), gbc);

        // Trennlinie
        gbc.gridx = 3;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.VERTICAL;
        mainPanel.add(new JSeparator(SwingConstants.VERTICAL), gbc);

        // Spalte 3: Actions Sidebar (schmaler)
        gbc.gridx = 4;
        gbc.weightx = 0.25; // Nimmt 25% der Breite
        mainPanel.add(createActionSidebar(), gbc);

        return mainPanel;
    }

    /**
     * Hilfsmethode: Erstellt eine einzelne scrollbare Projektspalte
     */
    private JComponent createProjectColumn(String title, JPanel listPanel) {
        JPanel columnPanel = new JPanel(new BorderLayout(0, 5));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        titleLabel.setBorder(new EmptyBorder(5, 5, 5, 5));
        columnPanel.add(titleLabel, BorderLayout.NORTH);

        // Wrapper-Panel, damit BoxLayout die Breite nicht füllt
        JPanel listWrapper = new JPanel(new BorderLayout());
        listWrapper.add(listPanel, BorderLayout.NORTH);

        JScrollPane scrollPane = new JScrollPane(listWrapper);
        scrollPane.setBorder(new LineBorder(Color.LIGHT_GRAY));
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        columnPanel.add(scrollPane, BorderLayout.CENTER);
        return columnPanel;
    }

    /**
     * Erstellt die rechte Aktions-Seitenleiste
     */
    private JComponent createActionSidebar() {
        JPanel sidebarPanel = new JPanel();
        sidebarPanel.setLayout(new BoxLayout(sidebarPanel, BoxLayout.Y_AXIS));
        sidebarPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

        // 1. Das Rad (Wheel)
        wheelPanel = new WheelPanel(wheelModel);
        // Feste Größe für das Rad setzen, damit es nicht kollabiert
        wheelPanel.setPreferredSize(new Dimension(200, 200));
        wheelPanel.setMaximumSize(new Dimension(300, 300));
        wheelPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
        sidebarPanel.add(wheelPanel);

        sidebarPanel.add(Box.createRigidArea(new Dimension(0, 10)));

        // 2. Kontext-Anzeige (Platzhalter)
        JLabel contextLabel = new JLabel("Context Details Here");
        contextLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        sidebarPanel.add(contextLabel);

        sidebarPanel.add(Box.createRigidArea(new Dimension(0, 10)));

        // 3. Roll-Button
        rollButton = new JButton("Roll!");
        rollButton.setFont(rollButton.getFont().deriveFont(Font.BOLD, 18f));
        rollButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        // Deaktiviert, bis Bedingungen erfüllt sind (laut Spezifikation)
        rollButton.setEnabled(false);
        rollButton.addActionListener(e -> onWheelRoll());
        sidebarPanel.add(rollButton);

        // Füllkomponente, um alles nach oben zu schieben
        sidebarPanel.add(Box.createVerticalGlue());

        return sidebarPanel;
    }

    /**
     * Erstellt die untere Aktionsleiste (anfangs unsichtbar)
     */
    private JComponent createProjectActionBar() {
        projectActionBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        projectActionBar.setBorder(new CompoundBorder(
                new MatteBorder(1, 0, 0, 0, Color.GRAY), // Oben-Rand
                new EmptyBorder(5, 5, 5, 5)
        ));

        buyButton = new JButton("Buy");
        sellButton = new JButton("Sell");
        descButton = new JButton("Description");

        // Farben für Buttons setzen (Opaque wird benötigt, damit BG-Farbe auf Mac/Win sichtbar ist)
        buyButton.setOpaque(true);
        sellButton.setOpaque(true);
        buyButton.setBorderPainted(false);
        sellButton.setBorderPainted(false);

        buyButton.addActionListener(e -> onBuyProject());
        sellButton.addActionListener(e -> onSellProject());
        descButton.addActionListener(e -> onShowDescription());

        projectActionBar.add(buyButton);
        projectActionBar.add(sellButton);
        projectActionBar.add(descButton);

        projectActionBar.setVisible(false); // Standardmäßig ausblenden
        return projectActionBar;
    }

    /**
     * Füllt die Spalten "Owned" und "Available" mit Projektkarten
     */
    private void populateProjectLists() {
        // Zuerst alle alten Karten löschen (falls UI aktualisiert wird)
        ownedProjectsListPanel.removeAll();
        availableProjectsListPanel.removeAll();
        projectCardMap.clear();

        for (Project project : allProjects) {
            ProjectCardPanel card = new ProjectCardPanel(project, this::setActiveProject);
            projectCardMap.put(project.id, card);

            if (project.owned) {
                ownedProjectsListPanel.add(card);
            } else {
                availableProjectsListPanel.add(card);
            }
        }
        // UI aktualisieren
        frame.revalidate();
        frame.repaint();
    }

    // --- Event-Handler und Logik ---

    /**
     * Aktualisiert das Münz-Badge-Label
     */
    private void updateCoinsBadge() {
        coinsBadge.setText("Coins: " + player.coins);
    }

    /**
     * Wird aufgerufen, wenn auf eine Projektkarte geklickt wird.
     * Setzt das Projekt als "aktiv" und zeigt die Aktionsleiste an.
     */
    private void setActiveProject(Project project) {
        // Hebe altes aktives Projekt hervor (falls vorhanden)
        if (activeProject != null) {
            ProjectCardPanel oldCard = projectCardMap.get(activeProject.id);
            if (oldCard != null) {
                oldCard.setActive(false);
            }
        }

        activeProject = project;

        if (activeProject != null) {
            // Hebe neues Projekt hervor
            ProjectCardPanel newCard = projectCardMap.get(activeProject.id);
            if (newCard != null) {
                newCard.setActive(true);
            }
            // Aktualisiere und zeige die Aktionsleiste
            updateProjectActionBar();
            projectActionBar.setVisible(true);
        } else {
            // Verstecke die Aktionsleiste
            projectActionBar.setVisible(false);
        }

        frame.revalidate();
        frame.repaint();
    }

    /**
     * Aktualisiert den Zustand (Farbe, Aktivierungsstatus) der Aktionsleisten-Buttons
     */
    private void updateProjectActionBar() {
        if (activeProject == null) {
            projectActionBar.setVisible(false);
            return;
        }

        // Buy Button Logik
        boolean canBuy = activeProject.buyable && player.coins >= activeProject.cost;
        buyButton.setEnabled(canBuy);
        buyButton.setBackground(canBuy ? new Color(0, 153, 51) : Color.GRAY); // Grün / Grau
        buyButton.setForeground(canBuy ? Color.WHITE : Color.LIGHT_GRAY);

        // Sell Button Logik
        boolean canSell = activeProject.sellable;
        sellButton.setEnabled(canSell);
        sellButton.setBackground(canSell ? new Color(204, 0, 0) : Color.GRAY); // Rot / Grau
        sellButton.setForeground(canSell ? Color.WHITE : Color.LIGHT_GRAY);

        // Desc Button
        descButton.setEnabled(true);
        descButton.setBackground(Color.LIGHT_GRAY);
        descButton.setForeground(Color.BLACK);

        projectActionBar.setVisible(true);
    }

    /**
     * Event-Handler: Klick auf "Buy"
     */
    private void onBuyProject() {
        if (activeProject == null || !buyButton.isEnabled()) return;

        System.out.println("KAUFEN: " + activeProject.name);

        // 1. Datenmodell aktualisieren
        player.coins -= activeProject.cost;
        activeProject.owned = true;
        activeProject.buyable = false; // Kann nicht erneut gekauft werden?
        activeProject.sellable = true; // Kann jetzt verkauft werden?

        // 2. UI aktualisieren
        updateCoinsBadge();

        // Karte von "Available" nach "Owned" verschieben
        ProjectCardPanel card = projectCardMap.get(activeProject.id);
        if (card != null) {
            availableProjectsListPanel.remove(card);
            ownedProjectsListPanel.add(card);

            // Layout-Manager benachrichtigen
            availableProjectsListPanel.revalidate();
            availableProjectsListPanel.repaint();
            ownedProjectsListPanel.revalidate();
            ownedProjectsListPanel.repaint();
        }

        // Auswahl aufheben und Leiste ausblenden
        setActiveProject(null);
    }

    /**
     * Event-Handler: Klick auf "Sell"
     */
    private void onSellProject() {
        if (activeProject == null || !sellButton.isEnabled()) return;

        System.out.println("VERKAUFEN: " + activeProject.name);

        // 1. Datenmodell aktualisieren (Annahme: Verkauf gibt Kosten zurück)
        player.coins += activeProject.cost;
        activeProject.owned = false;
        activeProject.buyable = true;
        activeProject.sellable = false;

        // 2. UI aktualisieren
        updateCoinsBadge();

        // Karte von "Owned" nach "Available" verschieben
        ProjectCardPanel card = projectCardMap.get(activeProject.id);
        if (card != null) {
            ownedProjectsListPanel.remove(card);
            availableProjectsListPanel.add(card);

            availableProjectsListPanel.revalidate();
            availableProjectsListPanel.repaint();
            ownedProjectsListPanel.revalidate();
            ownedProjectsListPanel.repaint();
        }

        // Auswahl aufheben
        setActiveProject(null);
    }

    /**
     * Event-Handler: Klick auf "Description"
     */
    private void onShowDescription() {
        if (activeProject == null) return;
        // Zeigt ein einfaches Dialogfenster (Modalfenster)
        JOptionPane.showMessageDialog(frame,
                activeProject.description,
                activeProject.name + " - Beschreibung",
                JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Event-Handler: Klick auf "Roll"
     */
    private void onWheelRoll() {
        List<Integer> selectedIndices = new ArrayList<>();
        for(WheelSegment seg : wheelModel.segments) {
            if (seg.selected) {
                selectedIndices.add(seg.index);
            }
        }
        System.out.println("ROLL! Ausgewählte Segmente: " + selectedIndices);

        // Hier würde die Spiellogik stattfinden...

        // Nach dem Rollen die Auswahl zurücksetzen?
        for(WheelSegment seg : wheelModel.segments) {
            if(seg.state != WheelState.GREY) {
                seg.selected = false;
            }
        }
        wheelPanel.repaint();
        rollButton.setEnabled(false); // Wieder deaktivieren
    }

    /**
     * Wird vom WheelPanel aufgerufen, wenn ein Segment angeklickt wird
     */
    private void onWheelSegmentClick(int index) {
        if (index < 0 || index >= wheelModel.segments.size()) return;

        WheelSegment seg = wheelModel.segments.get(index);

        // Gesperrte ('GREY') Segmente sind nicht interaktiv
        if (seg.state == WheelState.GREY) {
            return;
        }

        // Auswahl umschalten (toggle)
        seg.selected = !seg.selected;

        // Prüfen, ob der Roll-Button aktiviert werden soll
        // (Regel: mind. 1 Segment ausgewählt)
        boolean anySelected = false;
        for (WheelSegment s : wheelModel.segments) {
            if (s.selected) {
                anySelected = true;
                break;
            }
        }
        // Spezifikation: "RollButton disabled unless context active and wheel selection rules met."
        // Wir nehmen an, "context active" ist wahr und die Regel ist "mind. 1 ausgewählt".
        rollButton.setEnabled(anySelected);

        // Rad neu zeichnen
        wheelPanel.repaint();
    }


    // --- Benutzerdefinierte UI-Komponenten-Klassen ---

    /**
     * Eine benutzerdefinierte JPanel-Klasse für eine einzelne Projektkarte.
     * Implementiert Hover-Effekte und Klick-Handler.
     */
    class ProjectCardPanel extends JPanel {
        private Project project;
        private boolean isHovered = false;
        private boolean isActive = false;
        private final Color defaultBg;
        private final Color hoverBg = new Color(235, 245, 255); // Helles Blau
        private final Border defaultBorder = new CompoundBorder(
                new LineBorder(Color.LIGHT_GRAY, 1, true), // abgerundet
                new EmptyBorder(5, 5, 5, 5)
        );
        private final Border activeBorder = new CompoundBorder(
                new LineBorder(Color.BLUE, 2, true), // Dicker blauer Rand
                new EmptyBorder(4, 4, 4, 4) // Ausgleich für 1px dickeren Rand
        );

        public ProjectCardPanel(Project project, Consumer<Project> onActivate) {
            this.project = project;
            this.setLayout(new BorderLayout(8, 8));
            this.setBorder(defaultBorder);
            this.defaultBg = getBackground();

            // Linke Seite: Farbiges Thumbnail
            JPanel colorThumb = new JPanel();
            colorThumb.setBackground(project.color);
            colorThumb.setPreferredSize(new Dimension(20, 20));
            colorThumb.setBorder(new LineBorder(Color.DARK_GRAY));

            // Mitte: Name
            JLabel nameLabel = new JLabel(project.name);
            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));

            // Rechts: Meta-Icons (hier als Text-Label)
            JPanel metaPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
            metaPanel.setOpaque(false);
            if (project.timesOwned > 1) {
                metaPanel.add(new JLabel("x" + project.timesOwned));
            }
            // Beispiel für "Rarity" Icon
            // metaPanel.add(new JLabel("⭐")); 

            this.add(colorThumb, BorderLayout.WEST);
            this.add(nameLabel, BorderLayout.CENTER);
            this.add(metaPanel, BorderLayout.EAST);

            // Maximale Höhe festlegen, damit sie nicht zu groß werden
            this.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

            // Mouse Listener für Hover und Klick
            this.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    isHovered = true;
                    updateAppearance();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    isHovered = false;
                    updateAppearance();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    // Ruft die setActiveProject-Methode in der Haupt-App auf
                    onActivate.accept(project);
                }
            });
        }

        public void setActive(boolean active) {
            this.isActive = active;
            updateAppearance();
        }

        private void updateAppearance() {
            if (isActive) {
                setBorder(activeBorder);
                setBackground(hoverBg);
            } else if (isHovered) {
                setBorder(defaultBorder);
                setBackground(hoverBg);
            } else {
                setBorder(defaultBorder);
                setBackground(defaultBg);
            }
        }
    }


    /**
     * Eine benutzerdefinierte JPanel-Klasse, die das 12-Segment-Rad zeichnet.
     * Implementiert Hover-Highlights und Klick-Logik.
     */
    class WheelPanel extends JPanel {
        private WheelModel model;
        private int hoveredSegment = -1; // Index 0-11

        private final Color COLOR_WHITE = Color.WHITE;
        private final Color COLOR_GREY = Color.LIGHT_GRAY;
        private final Color COLOR_LIGHTBLUE = new Color(173, 216, 230); // lightblue
        private final Color COLOR_DARKBLUE = new Color(0, 0, 139);      // darkblue
        private final Color COLOR_BORDER = Color.BLACK;

        public WheelPanel(WheelModel model) {
            this.model = model;

            this.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    int segment = getSegmentFromPoint(e.getX(), e.getY());
                    if (segment != -1) {
                        // Klick an die Haupt-App weiterleiten
                        onWheelSegmentClick(segment);
                    }
                }
            });

            this.addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    int segment = getSegmentFromPoint(e.getX(), e.getY());
                    if (segment != hoveredSegment) {
                        hoveredSegment = segment;
                        repaint(); // Neu zeichnen, um Hover-Effekt zu zeigen
                    }
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    mouseMoved(e); // Behandle Dragging wie Hover
                }
            });
        }

        /**
         * Berechnet, welches Segment (0-11) sich an einer Koordinate befindet.
         * Gibt -1 zurück, wenn außerhalb des Rads.
         */
        private int getSegmentFromPoint(int x, int y) {
            int width = getWidth();
            int height = getHeight();
            int cx = width / 2;
            int cy = height / 2;
            int radius = Math.min(width, height) / 2 - 5; // 5px Rand

            // Distanz vom Zentrum
            double dist = Math.sqrt(Math.pow(x - cx, 2) + Math.pow(y - cy, 2));

            // Ignorieren, wenn zu nah am Zentrum oder außerhalb des Radius
            if (dist < radius * 0.2 || dist > radius) {
                return -1;
            }

            // Winkel berechnen
            // Math.atan2(y, x) -> y ist (y - cy), x ist (x - cx)
            // atan2 gibt Winkel in Bogenmaß von -PI bis PI zurück
            // Wir passen ihn an Swings Arc-System an (0 Grad = 3 Uhr)
            double angleRad = Math.atan2(cy - y, x - cx); // Y-Achse umkehren
            double angleDeg = Math.toDegrees(angleRad);

            // Winkel auf 0-360 Grad normalisieren (0 Grad = 3 Uhr)
            if (angleDeg < 0) {
                angleDeg += 360;
            }

            // Segment-Größe ist 30 Grad (360 / 12)
            // Wir finden den Index
            int segment = (int) (angleDeg / 30);

            // Da Swing-Winkel anders sind, muss man evtl. justieren.
            // Segment 0: 0-30 Grad (bei 3 Uhr)
            // Segment 1: 30-60 Grad
            // ...
            // Segment 11: 330-360 Grad

            return segment;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int width = getWidth();
            int height = getHeight();
            int cx = width / 2;
            int cy = height / 2;
            int radius = Math.min(width, height) / 2 - 5; // 5px Rand

            if (radius <= 0) return; // Nichts zu zeichnen

            int segmentAngle = 30; // 360 / 12

            for (int i = 0; i < 12; i++) {
                WheelSegment seg = model.segments.get(i);
                int startAngle = i * segmentAngle;

                // Farbe bestimmen basierend auf der Spezifikations-Tabelle
                Color segmentColor;
                if (seg.state == WheelState.GREY) {
                    segmentColor = COLOR_GREY; // Gesperrt, ignoriert Hover/Auswahl
                } else if (seg.selected) {
                    segmentColor = COLOR_DARKBLUE; // Ausgewählt
                } else if (i == hoveredSegment) {
                    segmentColor = COLOR_LIGHTBLUE; // Gehovert
                } else {
                    segmentColor = COLOR_WHITE; // Standard
                }

                // Segment füllen
                g2d.setColor(segmentColor);
                g2d.fill(new Arc2D.Double(cx - radius, cy - radius, radius * 2, radius * 2,
                        startAngle, segmentAngle, Arc2D.PIE));

                // Rand zeichnen
                g2d.setColor(COLOR_BORDER);
                g2d.draw(new Arc2D.Double(cx - radius, cy - radius, radius * 2, radius * 2,
                        startAngle, segmentAngle, Arc2D.PIE));
            }

            // Innere Abdeckung für den "Donut"-Look
            int innerRadius = (int)(radius * 0.4);
            g2d.setColor(getBackground()); // Mit Hintergrundfarbe füllen
            g2d.fillOval(cx - innerRadius, cy - innerRadius, innerRadius * 2, innerRadius * 2);
            g2d.setColor(COLOR_BORDER);
            g2d.drawOval(cx - innerRadius, cy - innerRadius, innerRadius * 2, innerRadius * 2);

            // Aktuelle Zahl in die Mitte zeichnen
            String text = String.valueOf(model.currentSelectionNumber);
            g2d.setFont(g2d.getFont().deriveFont(Font.BOLD, 20f));
            FontMetrics fm = g2d.getFontMetrics();
            int textWidth = fm.stringWidth(text);
            int textHeight = fm.getAscent();
            g2d.drawString(text, cx - textWidth / 2, cy + textHeight / 2);

            g2d.dispose();
        }
    }
}