# PLAN.md — MachiKoroCalculator Active Backlog

Open items only. For history see `CHANGELOG.md`, for math see `ARCHITECTURE.md`.

---

## Code-Qualität

### C4 · File Split Priority 2 (Low, deferred)

`MainWindow` ist groß. Sinnvolle Aufteilung wenn ein UI-Test-Layer existiert:
- `UIDataModel` (~50 Zeilen): hält `session`, `rankOpts`, `lastRanking`, `showWinProb`
- `RankingUIRenderer` (~100 Zeilen): `rebuildTable`, `populateCenter`, `clearCenter`, `buildNote`
- `GameController` (dünn): Turn-Anwendung, Undo, Snapshot, Save/Load-Dispatch

---

### C5 · Deep Code Optimization (Medium, unpriorisiert)

Vollständige Optimierungsrunde über alle Schichten:

**Performance:**
- Hot-Path-Profiling: `rankAllProjects` → `evPerRound` → `computeNetGainForRoll` — sind alle Schleifen wirklich nötig?
- `computeNetGainForRoll` wird pro `evPerRound`-Aufruf mehrfach für dieselbe `PlayerStats` aufgerufen — memoization oder batch-Berechnung prüfen
- `GameState.copy()` in `evPerRound` und `immediateEV` für jeden Kandidaten — alternativ: Stats-only-Pfad ohne Copy

**Klassen-Aufteilung:**
- `ProbabilityCalc.java` (~950 Zeilen) → könnte in `EV.java` (immediateEV/evPerRound) und `Ranking.java` (rank*, addWaitEntry, computeSynergy) aufgeteilt werden
- `MainWindow.java` → bereits als C4 geplant
- `CardIncome.java` (~450 Zeilen) → `get_I` könnte in eigene Klasse (`CardPayoutTable`) da es reiner switch-Dispatch ist

**Code-Qualität:**
- Redundante Bahnhof/Funkturm/Freizeitpark-Prüflogik taucht in `immediateEV`, `evPerRound`, `computeVarianceOwnTurn`, `computeProbNoIncomeOwnTurn` auf — DRY-Kandidat
- Hilfsmethoden `buildStatsWithCard`, `buildStatsWithCards`, `buildStatsWithEkz`, `applyToStats` duplizieren Logik aus `CardIncome.PlayerStats.of()` — zusammenführen oder delegieren

**Voraussetzung:** Bestehende 224 Tests müssen weiterhin bestehen; keine Verhaltensänderung.

---

## UI-Verbesserungen

### U1 · ~~Rechtes Panel: umbenennen + Tabs~~ ✓ (behoben)

---

### U2 · Kategorie-Icons im UI (Low)

Die 8 Kategorien (`food`, `store`, `animal`, `production`, `market`, `factory`, `cafe`, `office`) mit kleinen Icons darstellen.

**Schritte:**
a. Icons (16×16) in `src/resources/category_icons/` — entweder Bilddateien oder programmatisch gezeichnet
b. Kartendetails: Icon neben Farb-Tag
c. Tabelle: Icon in der Kartenspalte (eigene Spalte oder Composite-Renderer)
d. `IconTextRenderer`: Custom-Renderer der Kategorienamen durch Icons ersetzt — für Textbeschreibungen im Zugverlauf (`TurnEntryPanel`) und im Game Assistant (→ N1)

*Technisch:* Swing unterstützt keine inline-Images in JLabel-HTML. Entweder Panel mit FlowLayout (Label + Icon + Label) oder `paintComponent`-Renderer.

---

### U3 · ~~Trigger-Modus-Anzeige in Kartendetails~~ ✓ (behoben)

---

## Neue Features

### N0 · ~~Bürohaus-Tausch im UI~~ ✓ (behoben)

---

### N1 · ~~Game Assistant~~ ✓ (behoben)

---

### N2 · Bahnhof-Würfelwahl explizit im UI anzeigen (Medium)

**Problem:** Ob 1d6 oder 2d6 optimal ist, wird intern korrekt berechnet (`bestDiceEV`), aber dem Nutzer nie mitgeteilt. Außerdem ignorieren Kartenempfehlungen ob ein Spieler bewusst im 1–6-Bereich bleibt (Low-Range-Strategie).

**Fehlende Teile:**

a. **UI-Hinweis in Kartendetails / Assistent:** Wenn der Spieler Bahnhof besitzt, zeige welche Würfelwahl aktuell optimal ist und warum (z.B. "1d6 optimal — dein Portfolio aktiviert hauptsächlich auf 1–6"). `ProbabilityCalc.bestDiceChoice(gs, pi)` gibt zurück: `1d6`, `2d6`, oder `indifferent`.

b. **Würfelwahl-Bewusstsein im Assistenten:** Das Spiellage-Profil und Synergy-Lookahead sollten unterscheiden:
   - "Low-Range-Strategie" (Spieler hat Bahnhof aber 1d6 ist besser): Karten auf 1–6 hochgewichten
   - "High-Range-Expansion" (2d6 besser): Karten auf 7–12 empfehlen

c. **Akzeptierte Näherung A2 im Simulator** bleibt bestehen (Heuristik ist OK für MC-Speed). Die analytische Berechnung (`bestDiceEV`) ist bereits exakt — nur Exposition fehlt.

**Technisch:** `CardIncome.bestDiceEV(hasBahnhof, payoutFn)` ist bereits vorhanden. Neuer public wrapper `ProbabilityCalc.optimalDiceCount(gs, pi) → int` (1 oder 2). Im Assistenten: neuer Hint wenn Würfelwahl nicht trivial.

---

### N3 · Assistent-Gewichte: wirtschaftsbasierte Phasenerkennung (Medium)

Ersetzt die aktuelle feste `"Frühphase"/"Mittelspiel"/"Endspiel"`-Logik durch kontinuierliche, messwertbasierte Phasenerkennung.

**Phasendefinitionen (aus User-Feedback):**

| Phase | Bedingung |
|-------|-----------|
| Frühphase | `avgPortfolioEV < 1.2` UND `eigene_coins + 2×eigene_evPerRound < 10` (EKZ nicht in Reichweite) |
| Endspiel | `max(eigene GPs, max Gegner-GPs) >= 3` |
| Mittelspiel | alles andere |

Optional: Portfolio-Größe (Kartenzahl aller Spieler) als zusätzlicher Indikator.

**Rückstand-Modifier:**
- `opponentTurnsToWin = (cost_4th_GP - best_opp_coins) / best_opp_evPerRound`
- ≤ 3 Züge → Notfall: GP-Rush +0.5, Aggro +0.3
- ≤ 6 Züge → Druck: GP-Rush +0.2, Aggro +0.1

**Neue Klasse `AssistantConfig`** (package-private, `gui.newui`): zentralisiert alle Phasen-Schwellwerte und Gewichte als benannte Konstanten, damit sie schnell justierbar sind. Kein hardcodierter Magic-Number-Streuer mehr in `rebuildAssistantPanel`.

---

### N4 · Assistent-Kalibrierung via Snapshot-Labeling (Future, aufwändig)

Konzept für datengetriebene Gewichtskalibrierung:

**Idee:** Zufällige realistische `GameState`-Snapshots generieren → Nutzer gibt pro Snapshot eine kontinuierliche Einschätzung (z.B. Slider 0.0 = Frühphase, 1.0 = Endspiel) → aus allen Labels via linearer Regression / einfachem ML die optimalen Phasenparameter und Gewichte ableiten.

**Scope-Einschätzung:**
- Snapshot-Generator: `GameSimulator.simulate()` bis zu zufälligen Turns stoppen → fertige GameState-Objekte
- Label-UI: eigener Dialog mit Snapshot-Anzeige (Spielerzustand, GPs, EV, Münzen) + Slider
- Fitting: kleinste Quadrate über die gesammelten Labels → neue `AssistantConfig`-Werte
- Aufwand: ~3–5 Tage Implementierung, viele Labels nötig (>50 für sinnvolle Regression)

**Voraussetzung:** N3 (`AssistantConfig`) muss zuerst implementiert sein, damit die gelernten Werte direkt einsetzbar sind.

---

## Akzeptierte Näherungen (kein Handlungsbedarf)

| # | Thema | Erklärung |
|---|-------|-----------|
| A1 | Bürohaus — step-aware Projektion | Blaues Einkommen wird jetzt schrittsweise pro Gegner-Position akkumuliert; Rundungsfehler durch integer-Projektion sind vernachlässigbar. |
| A2 | Bahnhof-Würfelwahl im Simulator | `GameSimulator.rollDice()` wählt 2d6 wenn der Spieler eine Karte mit Aktivierung ≥ 7 hat — Heuristik statt exakter EV-Berechnung. Akzeptabler Trade-off für Simulationsgeschwindigkeit. |

---

## Math-Audit (High — Priorität nach N1)

### M1 · ~~Tiefenanalyse der Berechnungsarchitektur — Näherungen entfernen~~ ✓ (behoben)

---

### M2 · ~~Funkturm-EV fehlt vollständig in `immediateEV` und `evPerRound`~~ ✓ (behoben)

---

### M3 · ~~`REMAINING_TURNS_ESTIMATE = 12` ist ein statischer Hardcode-Wert~~ ✓ (behoben)

---

### M4 · ~~Alle Landmark-Gewichte sind gleich (`LANDMARK_WEIGHT = 2.0`)~~ ✓ (behoben)

---

### M5 · ~~"Sparen/Warten" wird nie als Option gerankt~~ ✓ (behoben)

---

## Future Features (nicht priorisiert)

- **Erweiterungskarten** — Hafen/Millionärsreihe. Architektur ist bereit (JSON-getrieben, `get_I` dispatcht per ID).
- **Gegner-Archetypen** — Simulierte Spieler folgen aktuell einer greedy Policy. Verschiedene Archetypen würden realistischere Gewinnraten liefern. Basis: Strategieprofile aus N1.

---

## Future Strategy — Konzept

### Was wird bereits berücksichtigt?
- `roiOverHorizon` diskontiert zukünftige Erträge über 10 Runden (γ=0.95) — time-value of money
- Win-Prob via MC spielt komplette Spiele durch → berücksichtigt indirekt alle Folgekäufe
- `contextualCardEvPerRound` bewertet Synergien (Markthalle+food, Molkerei+animal) — impliziter 1-Step-Synergy-Lookahead
- GP-Synergy-Hints im Spiellage-Assistenten zeigen wenn Bahnhof/EKZ/FP/Funkturm sich gerade lohnen würden
- MC-Simulation korrekt implementiert: `computeAllDeltasForRoll` in `GameSimulator.applyRoll`, Freizeitpark, Bürohaus-Swap, Supply-Tracking — verifiziert ✓

### Mögliche Verbesserungen (nicht priorisiert)

| Verbesserung | Aufwand | Mehrwert |
|---|---|---|
| **Synergy-Lookahead** — für jede Karte berechne `evPerRound_after_best_synergy_card` | ~~50 Zeilen~~ | ~~Mittel: "Molkerei wird besser wenn du noch Bauernhöfe kaufst"~~ | ✓ |
| **2-Turn Lookahead** — beste zwei Käufe in Folge, O(n²) | ~100 Zeilen | Hoch: erkennt Ketten wie "Bahnhof → dann lohnen sich 2d6-Karten" |
| **MC-Policy verbessern** — statt greedy `evPerRound/cost` die `roiOverHorizon`-Rangliste nutzen | ~~30 Zeilen~~ | ~~Mittel: realistischere Win-Raten, da Spieler die tatsächlich beste Strategie spielen~~ | ✓ |
