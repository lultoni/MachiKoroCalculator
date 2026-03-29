# CHANGELOG.md — MachiKoroCalculator

Implementierungsgeschichte: was gebaut wurde, warum, und welche Designentscheidungen getroffen wurden.

---

## M2: Funkturm-EV in immediateEV und evPerRound

**Problem:** `hasFunkturm` wurde bisher nur im Freizeitpark-Doppelwurf-Pfad (`bestSecondRollEV`) genutzt. Ein Spieler mit Funkturm aber ohne Freizeitpark bekam null Funkturm-Nutzen im EV-Modell.

**Lösung:** Neue private Methode `funkturmEV(boolean use2d6, IntToDoubleFunction payoutFn)`:
```
E[Funkturm] = E_baseline + Σ_{r : g(r) < E_baseline} P(r) × (E_baseline − g(r))
```
Der Spieler re-rollt optimal — nur wenn der erste Wurf unter dem Erwartungswert liegt. Das ergibt einen EV, der strikt höher als `E_baseline` und niedriger als ein erzwungenes Neu-Würfeln ist.

- **`immediateEV`**: wenn `hasFunkturm`, verwendet `funkturmEV(false, ...)` statt `weightedRollEV(false, ...)` für 1d6; bei Bahnhof zusätzlich `funkturmEV(true, ...)` für 2d6 (ohne Doubles-Freizeitpark-Bonus, da Funkturm dieselbe Würfelanzahl erzwingt).
- **`evPerRound`**: gleiche Logik im Eigenzug-Block.
- **`bestSecondRollEV`**: unverändert (Freizeitpark-Pfad; Funkturm+Freizeitpark erzwingt `forcedDice=2` für den zweiten Wurf wie bisher).

Sanity-Check: Weizenfeld+Bäckerei, 2 Spieler — `immediateEV` steigt von 0.667 auf 1.000 mit Funkturm allein; `evPerRound` von 1.000 auf 1.333.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## UI-Batch: Rang-Kontext, relative Farben, Tie-Handling, Spiellage-Assistent

### Rang-Kontext im Kartendetail
- **`topCardRank`-Label** — neue Zeile unterhalb des Metrik-Grids: "#X / Y erschwinglich · #Z / N gesamt". Zeigt wo die gewählte Karte im gesamten Ranking steht (nach ROI sortiert), sowohl unter den erschwingli­chen Karten als auch absolut.

### Relative Farben
- **`MetricColorScheme.rankedBackgroundFor(double rankPct)`** — neue Methode; nimmt Rang-Prozentsatz (0.0 = bester, 1.0 = schlechtester) statt absolutem Wert. Neue Farben `YELLOW_LIGHT` (0xFFF4CC) und `ORANGE_LIGHT` (0xFFE0B0) für mittleres/unteres Drittel.
- **`applyRankedMetricColor`** — alle 5 Metrik-Labels im Kartendetail nutzen jetzt rang-relative Farben: Platz 1 der jeweiligen Metrik = dunkelgrün, letzter Platz = orange. Tabellenspalten bleiben unverändert (absolute Schwellen).
- **`computeMetricRankPct`** — Hilfsmethode sortiert `lastRanking` nach der jeweiligen Metrik und gibt normierte Rang-Position zurück; beachtet `inverted`-Flag für P0/Varianz.

### Tie-Handling im Assistenten
- **`resolveWithTiebreaker`** — neue Methode; findet alle Einträge innerhalb `1e-6` des Bestwertes, wendet 3-stufigen Tiebreaker an (ROI → EV/Runde → Kosten), gibt `TieResult` (winner, tiebreakerNote, otherNames) zurück.
- **`TieResult`** record — `winner`, `tiebreakerNote` (warum dieser gewann), `otherNames` (übrige Gleichstands-Karten).
- **`buildTieSuffix`** — HTML-Suffix nach Erklärung: kursiv grau, zeigt Tiebreaker-Grund und "Auch: X, Y, ...".
- Alle 8 Einzel-Profile nutzen `resolveWithTiebreaker` statt einfachem `.max()/.min()`.

### Spiellage-Analyse (9. Profil, oben im Assistenten)
- **`GamePhaseContext`** record — Spielphase (Früh/Mittel/Endspiel), GP-Zähler, GP-Synergy-Flags (bahnhofSuggested, ekzSuggested, fpSuggested, ftSuggested + bahnhofEvGain).
- **`computePhaseContext`** — berechnet Phase aus `effectiveTurnCount` und Landmark-Besitz; prüft GP-Synergien via `portfolioEvPerRound`-Vergleich.
- **`addContextProfile`** — gewichtete Gesamt-Empfehlung: pro Phase eigene Gewichte [ROI/EV/Safe/LowVar/Cheap/WinProb/Aggro/GPRush]; Gegner-Druck-Modifikator (+0.3 auf Aggro+GPRush wenn Gegner ≥3 GPs); normRank-Scoring bestimmt finale Empfehlung; zeigt Faktoren mit Gewicht ≥ 0.5 und GP-Hinweise.
- **Rendering** — blauer Hintergrund-Block (0xF0F4FF), TitledBorder; Phasen-Header, Empfehlung in fett, Faktorliste, GP-Hinweise in blau.
- **`GameSession.getEffectiveTurnCount()`** — neuer public Getter.
- **`ProbabilityCalc.portfolioEvPerRound(GameState, int)`** — neuer public Wrapper für `CardIncome.playerEvPerRound`.
- **`Strings`** — neue Strings: `rankLabel`, `assistantTiebreakerNote`, `assistantAlso`, `assistantContextTitle/Phase/Recommend/Factor/GPHint`, `assistantPhaseEarly/Mid/Late`, `assistantContextNoAffordable`.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## N1: Game Assistant — 4th Tab mit 8 Strategieprofilen

Deterministischer, regelbasierter Spielassistent als vierter Tab im rechten Panel.

- **4. Tab "Assistent"** — `JScrollPane` über `assistantPanel` (BoxLayout Y); `rebuildAssistantPanel()` wird am Ende von `rebuildTable()` aufgerufen und bei `showGameOver()` geleert.
- **8 Strategieprofile** — jedes Profil wählt den besten erschwingli­chen Eintrag aus `lastRanking` nach eigenem Kriterium:
  - **Bestes Investment** — höchster `roiOverHorizon`
  - **Maximaler Ertrag** — höchstes `evPerRound`
  - **Sicherheitsstrategie** — niedrigstes `probNoIncomeRound` (P0)
  - **Niedrige Varianz** — niedrigste `variance`
  - **Sparsam** — niedrigster Kartenpreis
  - **Gewinnwahrscheinlichkeit** — höchstes `winProbDelta` (zeigt Hinweis wenn nicht berechnet)
  - **Aggressiv** — höchstes `evPerRound` unter `rot`/`lila`-Karten
  - **GP Rush** — günstigstes ungebautes Großprojekt (erschwinglich oder nicht)
- **Rendering** — jede Zeile: fetter Profilname + HTML-Label mit Karte und 1-2 Sätzen Begründung; durch graue Trennlinie getrennt.
- **i18n** — `Strings.tabAssistant()`, `assistantProfileROI/EV/...()`, `assistantExplainROI/EV/...()`, `assistantNoAffordable()`, `assistantNoWinProb()` in DE und EN.
- **`RankingOptions.DEFAULT_HORIZON = 10`** — neue Klassenkonstante für externe Referenz aus UI-Code.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## N0: Bürohaus-Tausch im UI — Dialog, Verlauf, Undo, Persistence

Der Bürohaus-Tausch (lila, Roll=6) war bisher nur in der Monte-Carlo-Simulation automatisch implementiert. In der echten Spielsession passierte nichts. Jetzt:

- **Swap-Dialog** — nach `session.applyTurn()` in `MainWindow.onConfirmTurn`: wenn Roll=6 und der aktive Spieler Bürohaus besitzt, wird `ProbabilityCalc.bürohausSwapNote` aufgerufen. Falls ein lohnender Tausch existiert, erscheint `JOptionPane.showConfirmDialog` mit Empfehlung und EV-Gewinn. Spieler kann "Ja" oder "Nein" wählen.
- **`GameSession.applyBürohausSwap(pi)`** — neue öffentliche Methode: ruft `BürohausLogic.executeSwap` auf dem State auf und patcht den letzten `TurnRecord` mit den Feldern `swappedAway`/`swappedIn`.
- **`TurnRecord` erweitert** — zwei neue optionale Felder (`swappedAway`, `swappedIn`, beide `Project` oder null) und ein 7-arg-Konstruktor. Alle kürzeren Konstruktoren delegieren mit `null`-Defaults. Vollständig rückwärtskompatibel.
- **Undo-Korrektheit** — `undoLastTurn()` replayed die History; bei Turns mit `swappedAway != null` wird `BürohausLogic.executeSwap` nach `applyTurn` erneut aufgerufen, um den Swap-State wiederherzustellen.
- **Persistence** — `GameSessionPersistence` serialisiert `swappedAway`/`swappedIn` als optionale Felder (nur wenn nicht null). Beim Laden wird `executeSwap` nach `applyTurn` für Turns mit Swap-Daten aufgerufen. Alte Saves ohne diese Felder laden korrekt.
- **Zugverlauf** — `TurnEntryPanel` zeigt eine neue Zeile "↔ [abgegebene Karte] → [erhaltene Karte]" kursiv in grau, wenn ein Tausch stattfand.
- **i18n** — `Strings.bürohausSwapTitle()` und `bürohausSwapPrompt()` in DE/EN.
- **`BürohausLogic`** — `findCandidates` und `SwapCandidates` von `private` auf package-private gesetzt, damit `GameSession` darauf zugreifen kann. `ProbabilityCalc.bürohausSwapNote` und `bürohausSwapEV` auf `public` gesetzt.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## M1: Math-Audit — drei Näherungen durch exakte Berechnungen ersetzt

- **A1 → Schritt-bewusste Münzprojektion in `evPerRound`** — Statt eines einzelnen Vorwärtsprojektions-Schritts für alle Spieler wird jetzt pro Gegner-Position das akkumulierte Blau-Einkommen des aktiven Spielers berechnet (`step × bluePerOppTurn`). Ergebnis: Rote-Karten-Klammerung ist für frühe vs. späte Gegner im Rundenzyklus korrekt.
- **A2 → Kontextbewusste Bürohaus-Swap-Bewertung in `BürohausLogic`** — `findCandidates` nutzt jetzt `contextualCardEvPerRound` statt `singleCardEvPerRound`. Sowohl die eigene schlechteste Karte als auch die Karte des Gegners werden im **echten Kontext des aktiven Spielers** bewertet (reale Einkaufszentrum-Flag, food/animal/production-Anzahl). Synergien (Markthalle mit vielen Food-Karten, Molkerei mit Bauernhöfen) werden korrekt berücksichtigt.
- **A3 → Inline-Kontextevaluation im GameSimulator** — `STATIC_EV_PER_COST`-Tabelle entfernt. `greedyBuy` berechnet jetzt pro Kaufkandidat `contextualCardEvPerRound(card, playerStats, n, oppCoins)` inline (~12 `get_I`-Aufrufe pro Karte, allokationsfrei). Die Spielsimulation berücksichtigt jetzt korrekt, dass ein Spieler mit 3 Food-Karten Markthalle viel höher bewertet als ein Spieler ohne.
- **`CardIncome.contextualCardEvPerRound`** — neue package-private Methode; 2d6-Pass + 1d6-Pass (max), skaliert nach Kartenfarbe (Blau ×N, Rot ×(N-1)). Wird von `BürohausLogic` und `GameSimulator` geteilt.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

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
