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
- ~~Hilfsmethoden `buildStatsWithCard`, `buildStatsWithCards`, `buildStatsWithEkz`, `applyToStats` duplizieren Logik aus `CardIncome.PlayerStats.of()` — zusammenführen oder delegieren~~ ✓

**Voraussetzung:** Bestehende Tests müssen weiterhin bestehen; keine Verhaltensänderung.

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

### N2 · ~~Bahnhof-Würfelwahl explizit im UI anzeigen~~ ✓ (behoben)

---

### N3 · ~~Assistent-Gewichte: wirtschaftsbasierte Phasenerkennung~~ ✓ (behoben)

---

### N4 · Assistent-Kalibrierung via Snapshot-Labeling (Future, aufwändig)

Konzept für datengetriebene Gewichtskalibrierung. Besteht aus drei Teilsystemen:

---

#### N4a · ~~`SnapshotCard` — neues Spieler-Snapshot-Widget~~ ✓ (behoben)

---

#### N4b · ~~Snapshot-Generator~~ ✓ (behoben)

---

#### N4c · ~~Labeling-UI~~ ✓ (behoben)

---

#### N4d · Fitting → `AssistantConfig`

- `PhaseFitter.fit(List<LabeledSnapshot>)` — lineare Regression über alle Parameter (avgPortfolioEV, ownGPs, maxOppGPs, ownCoins, portfolioSize, ...) gegen die drei Label-Werte
- Gibt neue `AssistantConfig`-Werte zurück (Schwellwerte + Gewichte)
- Mindest-Labels für sinnvolle Regression: ~50
- Aufwand: ~3–5 Tage Implementierung

**Voraussetzungen:** N3 (`AssistantConfig`) + N4a (`SnapshotCard`) + N4b müssen zuerst implementiert sein.

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
| **2-Turn Lookahead** — beste zwei Käufe in Folge, O(n²) | ~100 Zeilen | Hoch: erkennt Ketten wie "Bahnhof → dann lohnen sich 2d6-Karten" | ✓ |
| **MC-Policy verbessern** — statt greedy `evPerRound/cost` die `roiOverHorizon`-Rangliste nutzen | ~~30 Zeilen~~ | ~~Mittel: realistischere Win-Raten, da Spieler die tatsächlich beste Strategie spielen~~ | ✓ |

---

## Offene Math-Items (Prio mittel)

### M6 · ~~Landmark-Synergien in 2-Turn-Lookahead~~ ✓ (behoben)

`computeTwoTurnNote` überspringt aktuell alle Landmarks (`is_grossprojekt`-Filter). Das bedeutet, dass z.B. die Kette „Bahnhof → Bergwerk wird besser" nie als Note erscheint.

**Fix:** Landmarks als `cardB`-Kandidaten zulassen wenn das Portflio nach Kauf von `cardA` die Schwelle überschreitet:
- Wenn `cardA == bahnhof`: Alle 2d6-Karten (Aktivierung ≥ 7) als beste B suchen, da `statsAfterA.hasBahnhof = true` die EV korrekt erhöht
- Wenn `cardB == bahnhof`: nicht sinnvoll (Bahnhof ist kein Einkommen direkt)
- Einschränkung: Landmark `cardB` darf nur vorgeschlagen werden wenn der Spieler sie noch nicht besitzt

**Scope:** ~20 Zeilen in `computeTwoTurnNote`.

---

### M7 · MC-Rollout-Policy: Boltzmann-Exploration Toggle (Medium)

Aktueller greedy Simulator (`GameSimulator.greedyBuy`) wählt immer die Karte mit höchstem `evPerRound/cost`. Das führt zu systematisch verzerrten Win-Raten: Spieler, die eine „zweitbeste" Karte kaufen (wie der User), wirken schlechter als sie sind.

**Geplante Änderung:**
- `RankingOptions.mcExplorationTemp` (double, default 0.0 = greedy): Boltzmann-Temperatur T
- Bei T > 0: `P(buy X) ∝ exp(score(X) / T)` — höheres T = mehr Zufall, T → ∞ = uniform random
- Empfohlener Standardwert: T = 0.5–1.0 (sanfte Streuung um greedy-Optimum)
- Toggle in MainWindow: "Exploration" Checkbox neben MC-Slider (nur wenn MC aktiv)

**Zusatz:** MC-Limit auf 10.000 Sims/Kandidat erhöhen (Benchmark: ~28k parallel Sims/sec → <2s bei 10k; 100k = ~50s, zu langsam für UI).

**Scope:** ~80 Zeilen: `GameSimulator` (Boltzmann-Sampling), `RankingOptions` (neues Feld), `MainWindow` (Toggle + erhöhtes Default-Limit).

---

### M8 · ~~Bahnhof-Gate im Simulator~~ ✓ (behoben)

`GameSimulator.greedyBuy()` kauft Landmarks blindly in Kosten-Reihenfolge. ~~Das führt dazu, dass simulierte Spieler den Bahnhof früh kaufen, auch wenn sie keine Karten mit Aktivierung ≥ 7 besitzen — was den Bahnhof-Kauf faktisch wertlos macht.~~

~~**Fix:** Bahnhof nur kaufen wenn `hasHighRangeCard(player)`:~~
```java
private static boolean hasHighRangeCard(Player p) {
    return p.getOwned_projects().stream()
        .anyMatch(c -> !c.isIs_grossprojekt()
                    && c.getDice_activation().stream().anyMatch(r -> r >= 7));
}
```
~~Vor Bahnhof-Kauf: `if (lm.getId().equals("bahnhof") && !hasHighRangeCard(player)) continue;`~~

~~**Scope:** ~10 Zeilen in `GameSimulator.greedyBuy`.~~
