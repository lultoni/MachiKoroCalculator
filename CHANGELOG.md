# CHANGELOG.md — MachiKoroCalculator

Implementierungsgeschichte: was gebaut wurde, warum, und welche Designentscheidungen getroffen wurden.

---

## Rechtes Panel: drei Tabs (Erschwinglich / Nicht erschwinglich / Alle)

- **`ProbabilityCalc.rankAllProjects`** — neue öffentliche Methode, die alle Kandidaten (erschwinglich und nicht) berechnet und per `RankEntry.affordable`-Flag markiert. Win-Prob-Delta wird nur für erschwingliche Karten berechnet.
- **`RankEntry.affordable`** — neues Boolean-Feld (Standard: `true`); von `rankAllProjects` gesetzt.
- **`JTabbedPane` im rechten Panel** — drei JTable-Instanzen (`rankTable`, `rankTableUnaffordable`, `rankTableAll`), jede mit eigenem `DefaultTableModel`. Tab-Klick wählt automatisch ersten erschwingli­chen Eintrag im Kartendetails-Panel.
- **Dim-Renderer für "Alle"-Tab** — `CardNameRendererWithDim` und `NumericCellRendererWithDim`: nicht-erschwingliche Zeilen werden kursiv und grau gerendert.
- **`selectFirstAffordable()`** — Helper wählt ersten erschwingli­chen Eintrag und aktualisiert Kartendetails; ersetzt mehrfach duplizierte inline-Logik.
- **`refreshAll` / `refreshAfterRollChange`** nutzen jetzt `rankAllProjects` statt `rankPurchasableProjects`.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## Bugs + Code-Qualität: Würfelzahlen > 6, Panel-Breite, Lokalisierung, Metrik-Färbung

- **`DiceFacePanel` Fallback** — `paintComponent` rendert die Würfelborder/-schatten normal, zeichnet bei Werten > 6 aber eine zentrierte Zahl statt Dots (Apfelplantage=10, Bergwerk=9, Markthalle=11/12)
- **Minimale Panel-Breite** — `JFrame.setMinimumSize(1020, 600)`, rechtes Panel `setMinimumSize(430, 0)` — Reload-Button und MC-Status-Text nicht mehr abgeschnitten
- **`Strings.coinsUnit()`** — `refreshRollPreview()` nutzt `Strings.coinsUnit()` statt hartkodiertem `"coins"` — korrekt lokalisiert in DE und EN
- **`MetricColorScheme`** — neues package-private Enum: 6 Konstanten (COST, EV, ROI, P0, VARIANCE, WIN_PROB_DELTA) mit Schwellwerten, `inverted`-Flag für P0/Varianz (kleiner = besser). `backgroundFor()` / `foregroundFor()` liefern Farbtöne. `NumericCellRenderer` nimmt Scheme-Instanz; jede Tabellenspalte hat eigenen Renderer. Neuer `applyMetricColor()`-Helper in `MainWindow` für Kartendetails-Panel.
- **Language Deep Clean** — `Strings`: `rightPanelTitle` → "Verfügbare Karten" / "Available Cards", `leftPanelTitle` → "Aktueller Zug-Tracker", `gameOverDesc` nutzt `grossProjekt()`, `colTipEV` DE auf EN-Detailniveau gebracht
- **Dead Code entfernt** — `UIUtils.java` (unbenutztes `capitalize()`) und `DICE.png` (Orphan-Ressource) gelöscht

**Tests:** 228 bestanden, 0 fehlgeschlagen.

---

## DE/EN Lokalisation

Vollständige Deutsch/Englisch-Lokalisation.

- **`Strings.java`** — zentrale String-Registry mit `Locale`-Enum (DE/EN), `setLocale()`, privatem `s(de, en)`-Dispatcher und statischen Accessor-Methoden für jeden UI-String
- **`projects.json` + `Project`** — alle 19 Karten bekamen `name_en` + `description_en` (offizielle englische Namen); `Project`-Konstruktor auf 9 Args erweitert; `getLocalizedName()` / `getLocalizedDescription()` locale-abhängig; `ProjectLoader` von Gson-Auto-Mapping auf manuelles Field-Parsing umgestellt
- **GUI-Verdrahtung** — alle UI-Strings in `SetupWindow`, `MainWindow`, `SnapshotDialog`, `TurnEntryPanel` über `Strings.*`; Kartennamen/-beschreibungen über `Project.getLocalizedName/Description()`
- **Sprachwechsel** — `SetupWindow`: DE/EN-Radiobuttons, `rebuildUI()` in-place; `MainWindow`: `JMenuBar` mit Language-Menü (`JRadioButtonMenuItem`), `buildUI()` + `refreshAll()` in-place
- **`projectFromLabel`-Fix** — vorheriges `toLowerCase()` funktionierte nur auf Deutsch ("Weizenfeld" → "weizenfeld"). Ersetzt durch Reverse-Lookup via `getLocalizedName()` — korrekt in beiden Locales. `CardNameRenderer` nutzt denselben Ansatz.

**Tests:** 228 bestanden, 0 fehlgeschlagen.

---

## Würfel-UI-Überarbeitung: programmatische Würfelgesichter, Selector-Strips, neu gestalteter Verlauf

- **`DiceFacePanel`** — zeichnet Würfelgesicht (Wert 1–6) mit Dots per `Graphics2D` inkl. Antialiasing und Schlagschatten. Drei Modi: reine Anzeige, selektiert, selektierbar-aber-nicht-selektiert. `DICE.png` wird nicht mehr verwendet.
- **`DiceSelectorPanel`** — horizontaler Strip von 6 `DiceFacePanel`s, Einfachauswahl. Optional (zweiter Strip): Klick auf selektierten Würfel deselektiert ihn (→ 1W6 trotz Bahnhof möglich)
- **Wurfeingabe** — Spinner ersetzt durch zwei `DiceSelectorPanel`s; erster immer sichtbar, zweiter nur bei Bahnhof. `getCurrentRoll()` summiert beide Strips.
- **`TurnEntryPanel`** — ersetzt HTML-`JLabel`-Verlauf. Zeigt: Spielername (farbig), gerendertes Würfelgesicht, DOUBLES-Badge, Münzdeltas pro Spieler (grün/rot), Kaufinfo. Kein "→ saved" mehr bei leerem Kauf.
- **Aktivierungswürfel in Kartendetails** — `topCardCostRow` (JPanel) statt `topCardCost` (JLabel); `buildActivationDice()` hängt einen `DiceFacePanel` pro Aktivierungswert an. GPs zeigen " · Großprojekt" kursiv.
- **Metrik-Legende** — ausklappbares Panel unterhalb des Metrik-Grids mit Kurzbeschreibungen für EV/round, ROI, P(0), Var, Win Δ.

**Tests:** 228 bestanden, 0 fehlgeschlagen.

---

## Münz-Icon, Win-Prob-Interaktion, Game-Over-Fix

- **Deep Analysis × Win Prob** — `onToggleDeepAnalysis` setzt `rankOpts.mcSimulations > 0` nur wenn **beide** Flags aktiv sind; `onToggleWinProb` setzt korrekten MC-Count beim Einblenden, 0 beim Ausblenden
- **Münzanzeige** — `COIN.png`-Icon (18×18) + Zahl; `coinsAfterLabel` zeigt Post-Roll-Delta (+N grün / −N rot), immer sichtbar (kein Layout-Shift)
- **`showGameOver`** — nutzt jetzt `setWinProbRowVisible()` statt direktem `setVisible(false)` am Label

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## UI-Bugs + erweiterter Zugverlauf

- **Win-Prob-Row immer sichtbar** — `populateCenter` ruft `setWinProbRowVisible(showWinProb)` nach dem Setzen der Werte auf
- **Sortierung nach Table-Rebuild verloren** — `rebuildTable` speichert und stellt `sorter.getSortKeys()` wieder her; Column-Indizes geclampet für den Win-Δ-Spaltenfall
- **Deep Analysis zeigte Win-Prob-Spalte auto** — `onToggleDeepAnalysis` setzt `showWinProb` nicht mehr; "Show Win Prob Δ" ist alleiniges Gate
- **Linkes Panel Resize** — `BorderLayout`: Controls in `NORTH` (fixiert), History-`JScrollPane` in `CENTER` (füllt freien Platz)
- **`TurnRecord.coinDeltas`** — `int[]`-Feld (5-Arg-Konstruktor; kürzere Konstruktoren: `null`); `GameSession.applyTurn` berechnet und speichert Deltas; JSON-Serialisierung rückwärtskompatibel

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## UI-Polish-Batch: BoundedSpinner, Freizeitpark, GP-Ranking, MC-Controls

- **`BoundedSpinner`** — deaktiviert +/−-Buttons an Modellgrenzen; alle Spinner in `MainWindow` und `SnapshotDialog` nutzen ihn
- **Freizeitpark-Doppelwürfe** — `TurnRecord.isDoubles`; `GameSession.bonusTurnPending` + `effectiveTurnCount`; "Doubles?"-Checkbox ein/ausgeblendet je nach Kartenbesitz
- **GPs im Ranking + Kaufdropdown** — `rankPurchasableProjects` kombiniert `unbuilt_projects` mit nicht-besessenen GPs aus `ProjectLoader`
- **Sortierbare Tabelle** — `TableRowSorter` mit typ-bewussten Spalten; `NumericCellRenderer` färbt > 0.5 grün, < −0.5 rot
- **Deep Analysis** — `BoundedSpinner` (100–10 000), "⟳"-Reload-Button unabhängig vom Win-Prob-Toggle; MC per `SwingWorker` off-EDT
- **SnapshotDialog Startkarten** — Weizenfeld/Bäckerei-Spinner max=7 (1 Startkopie + 6 Markt); andere blau/grün/rot max=6
- **Panels umbenannt** — "Current Turn" → "Current Turn Tracker", "Best Purchase" → "Card Details"

**Tests:** 208 bestanden, 0 fehlgeschlagen.

---

## Kaufliste und Ranking auf Post-Roll-Münzen umgestellt

`MainWindow.postRollState()` kopiert den Spielstand und wendet `computeAllDeltasForRoll` an. Kaufdropdown, Ranking und Baseline-Win-Prob verwenden diesen Post-Roll-Zustand. Münzlabel zeigt "N → M (after roll)". `refreshAfterRollChange()` aktualisiert Liste + Vorschau live beim Würfelwechsel.

**Tests:** 165 bestanden, 0 fehlgeschlagen.

---

## File-Split: BürohausLogic + GameSessionPersistence

- **`BürohausLogic`** — `swapEV`, `swapNote`, `executeSwap` aus `ProbabilityCalc` extrahiert; gemeinsamer `findCandidates()`-Helper eliminiert duplizierte Scan-Schleifen. Public-API unverändert.
- **`GameSessionPersistence`** — 140 Zeilen JSON-Serialisierung + 11 Gson-Imports aus `GameSession` extrahiert. `GameSession.save/load` sind dünne Wrapper. `GameSession` hat jetzt nur noch 3 Standard-Imports.

**Tests:** 165 bestanden, 0 fehlgeschlagen.

---

## Frühphasen (kompakt)

### Supply-Modell-Fix + SnapshotDialog Multi-Copy + Würfelbereich + Roll-Preview

- Supply: Karte bleibt kaufbar bis alle 6 Kopien vergeben sind (statt nach erster Kopie); `GameStateBuilder.build()` und `GameState.initial()` korrigiert; `undoLastTurn()` replayed korrekt
- `SnapshotDialog`: blau/grün/rot als `JSpinner(0–6)` statt Checkbox; `cardControls Component[][]` statt `projectChecks JCheckBox[][]`
- Würfelbereich: `updateRollInput(Player)` setzt Range 1–6 / 1–12 + Default je nach Bahnhof-Besitz, bei jedem Turn-Wechsel
- Roll-Preview: `refreshRollPreview()` zeigt Münzdeltas pro Spieler sofort beim Würfelwechsel

### Baseline-Win-Prob-Anzeige

`ProbabilityCalc.computeBaselineWinProb()` (public); `refreshAll()` zeigt "Current win prob: X.X%" im Center-Panel via `baselineWinProbLabel`.

### Game-Over-Erkennung

`GameSession.isFinished()` / `getWinnerIndex()`; `onConfirmTurn` ruft `showGameOver()` nach dem 4. GP.

### Regeltreue: Einkommensreihenfolge + Gegenuhrzeigersinn-Zahlung

`computeNetGainForRoll`: Rot → Blau/Grün → Lila. Gegner gegen den Uhrzeigersinn iteriert: `(playerIndex - step + n) % n`. `computeAllDeltasForRoll` als Single Source of Truth; `GameSession.applyTurn` und `GameSimulator.applyRoll` nutzen es.

### Bürohaus Tausch-Logik

`bürohausSwapEV` = `max(0, bestOppCardEV − worstOwnCardEV)`; in `immediateEV` bei `P(roll=6)` eingebaut. `bürohausSwapNote` liefert "Tausche X gegen Ps Y" für Kartendetails. `executeBürohausSwap` mutiert `GameState` für MC-Simulation.

### Monte Carlo Deep Mode (Phase 5)

`GameSimulator`: stateless, greedy Policy (Landmarks zuerst, dann höchstes `evPerRound/cost`), Supply-Tracking (`Map<String,Integer>`), `MAX_TURNS=200`. `mcWinRate()` via `IntStream.parallel()` + `ThreadLocalRandom`. MC-Baseline einmal in `rankPurchasableProjects` berechnet, für alle Kandidaten wiederverwendet.

### Core Math Engine (Phase 2)

P1/P2-Tabellen precomputed. `get_I` für alle 19 Karten implementiert. Formeln: `evPerRound`, `roiOverHorizon` (geometrische Reihe, γ=0.95, T=10), Varianz, Softmax-Win-Prob, `rankPurchasableProjects`.

### Datenmodell (Phase 1)

`Project` unveränderlich (id-basiertes equals/hashCode). `Player.copy()` shallow-safe. `GameState.copy()` tief. `ProjectLoader` static cache. `GameStateBuilder` fluent.

---

## Designentscheidungen

| Frage | Entscheidung |
|-------|-------------|
| Bürohaus-EV | Heuristik: `max(0, bestOppCardEV − worstOwnCardEV)`, in `immediateEV` bei `P(roll=6)` |
| UI-Modell | Turn-by-turn mit Snapshot-Edit-Möglichkeit |
| Diskontfaktor | 0.95 pro Zug (konfigurierbar via `RankingOptions`) |
| MC-Standard | Off (analytisch); per Deep-Analysis-Toggle, Standard 1000 Sims |
| Supply-Modell | `unbuilt_projects` in `GameState` für Ranking; `Map<String,Integer>` in `GameSimulator` |
| Stadion-Regel | 2 Münzen von **jedem** Gegner (kein Gesamtlimit) |
| Fernsehsender-Regel | Bis zu 5 Münzen vom **einzelnen reichsten** Gegner |
