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

### U3 · Trigger-Modus-Anzeige in Kartendetails (Low)

Auf echten Machi-Koro-Karten steht, ob die Karte für alle oder nur den aktiven Spieler gilt:

| Farbe | Symbol | Bedeutung |
|-------|--------|-----------|
| Blau | 3 Personen | Triggert immer — alle Spieler |
| Grün | 1 Person | Nur eigener Zug |
| Rot | 1 Person + Hinweis | Nur fremde Züge |
| Lila | 1 Person | Nur eigener Zug, einmalig |

Programmatisch als kleine Kreise/Ovale zeichnen; für Rot zusätzlich Hinweis "Wird in fremden Zügen ausgelöst."

---

## Neue Features

### N0 · ~~Bürohaus-Tausch im UI~~ ✓ (behoben)

---

### N1 · ~~Game Assistant~~ ✓ (behoben)

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
| **Synergy-Lookahead** — für jede Karte berechne `evPerRound_after_best_synergy_card` | ~50 Zeilen | Mittel: "Molkerei wird besser wenn du noch Bauernhöfe kaufst" |
| **2-Turn Lookahead** — beste zwei Käufe in Folge, O(n²) | ~100 Zeilen | Hoch: erkennt Ketten wie "Bahnhof → dann lohnen sich 2d6-Karten" |
| **MC-Policy verbessern** — statt greedy `evPerRound/cost` die `roiOverHorizon`-Rangliste nutzen | ~30 Zeilen | Mittel: realistischere Win-Raten, da Spieler die tatsächlich beste Strategie spielen |
