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

### M2 · Funkturm-EV fehlt vollständig in `immediateEV` und `evPerRound` (High)

**Problem:** In `immediateEV` und `evPerRound` wird `hasFunkturm` ausschließlich im Freizeitpark-Doppelwurf-Pfad verwendet (`bestSecondRollEV`). Ein Spieler mit Funkturm aber ohne Freizeitpark bekommt **null Funkturm-Nutzen** im EV-Modell — das ist ein echter Bug.

**Korrekte Formel:**
```
E[Funkturm-Zug, 1d6] = Σ_r P1[r] × max(g(r), E_baseline_1d6)
                     = E_baseline + Σ_{r: g(r) < E_baseline} P1[r] × (E_baseline − g(r))
```
Mit Bahnhof: analog für 2d6 → dann `max(FunkturmEV_1d6, FunkturmEV_2d6)`.

Die aktuelle Berechnung `max(EV_1d6, EV_2d6)` ist der EV, wenn man **immer** neu würfelt — aber Funkturm erlaubt nur ein Neuwerfen, wenn das erste Ergebnis schlecht war. Der korrekte EV ist höher als `E_baseline` aber niedriger als `bestDiceEV`.

**Betroffene Methoden:**
- `ProbabilityCalc.immediateEV` — Funkturm-EV fehlt für den Nicht-Freizeitpark-Fall
- `ProbabilityCalc.evPerRound` — gleicher Fehler in der Eigenzug-Berechnung

**Implementierungsschritte:**
1. Nach dem `ev1`/`ev2`-Block in `immediateEV`: wenn `hasFunkturm`, berechne `FunkturmEV(1d6)` und ggf. `FunkturmEV(2d6)`, ersetze `evTotal` durch das Maximum.
2. Gleiche Logik in `evPerRound` im Eigenzug-Block.
3. `bestSecondRollEV` bleibt unverändert (nur Freizeitpark-Pfad).

---

### M3 · `REMAINING_TURNS_ESTIMATE = 12` ist ein statischer Hardcode-Wert (Medium)

**Problem:** In `WinProbabilityCalc` ist `REMAINING_TURNS_ESTIMATE = 12.0` ein Klassenkonstante. Die Win-Prob-Formel lautet:
```
score(p) = playerEvPerRound(p) × 12 + Σ LANDMARK_WEIGHT
```
Im Frühspiel (alle Spieler bei 0 Landmarks) sind 20+ Züge übrig → 12 unterschätzt den EV-Term. Im Endspiel (Gegner bei 3 Landmarks) sind nur noch ~3 Züge übrig → 12 überschätzt dramatisch.

**Korrekte Ansatz:** `effectiveTurnCount` aus `GameSession` an `rankAllProjects` weitergeben und die Schätzung ableiten:
```
remainingTurns = max(3, TOTAL_EXPECTED_TURNS − effectiveTurnCount / n)
```
wobei `TOTAL_EXPECTED_TURNS ≈ 25` (kalibrierbar aus MC-Statistiken).

Alternativ: Landmark-Fortschritt als Proxy — Gesamtzahl bereits gebauter Landmarks über alle Spieler.

**Betroffene Datei:** `WinProbabilityCalc.computeScores`, `RankingOptions` (neues optionales Feld `turnsElapsed`).

---

### M4 · Alle Landmark-Gewichte sind gleich (`LANDMARK_WEIGHT = 2.0`) (Low–Medium)

**Problem:** In `WinProbabilityCalc.computeScores` erhält jedes Großprojekt den gleichen Bonus `+2.0`. Aber die Landmarks haben sehr unterschiedliche EV-Beiträge:
- Bahnhof (4¢): moderate Würfelwahl-Verbesserung (~0.5–1.5 Münzen/Runde je nach Portfolio)
- Einkaufszentrum (10¢): signifikanter Multiplikator für Grün-/Store-Karten
- Freizeitpark (16¢): erheblicher Bonus durch Doppelwurf-EV
- Funkturm (22¢): Neuwerf-EV (nach M2-Fix relevant)

**Korrekte Berechnung:**
```
LANDMARK_WEIGHT(L) ≈ immediateEV_with_L − immediateEV_without_L × REMAINING_TURNS
```
Das ist mit bestehenden Methoden berechenbar, aber zu langsam für jeden `computeScores`-Aufruf.

**Praktikabler Ansatz:** Precomputed Tabelle mit 4 landmark-spezifischen Gewichten, kalibriert aus MC-Daten oder aus dem EV-Delta bei einem Referenz-Spieler. Gewichte könnten z.B. `[1.5, 2.5, 3.5, 4.0]` sein statt `[2.0, 2.0, 2.0, 2.0]`.

---

### M5 · "Sparen/Warten" wird nie als Option gerankt (Medium)

**Problem:** `rankPurchasableProjects` und `rankAllProjects` listen nie eine "Nichts kaufen"-Option. Der Spieler sieht nicht, ob Abwarten (Münzen akkumulieren für eine bessere Karte) besser wäre als der beste aktuell erschwingliche Kauf.

**Korrekte Formel für den "Warten"-Eintrag:**
```
ROI(warten, k Züge) = ROI(beste_nächste_Karte)
                     - k × evPerRound_aktuell   [verpasstes Einkommen]
```
Der Breakeven-Punkt liegt bei:
```
k* = (ROI(C') − ROI(C_jetzt)) / evPerRound_aktuell
```
Wenn `k* < 1` → sofort kaufen ist besser. Wenn `k* > 1` → warten lohnt sich.

**Implementierung:** Einen synthetischen `RankEntry` mit `project = null` (oder einem Dummy-Projekt "Warten"), dessen `roiOverHorizon` = ROI der besten nächsten erschwinglichen Karte minus einem Zug verpasstes Einkommen. Würde eine neue Zeile "≡ Warten" im Ranking-Tab ergeben.

Erfordert UI-Anpassung: Die Zeile ist nicht kaufbar, hat keinen Kartendetail-Eintrag, und zeigt stattdessen "Spare auf: [Kartenname]" an.

---

## Future Features (nicht priorisiert)

- **Erweiterungskarten** — Hafen/Millionärsreihe. Architektur ist bereit (JSON-getrieben, `get_I` dispatcht per ID).
- **Gegner-Archetypen** — Simulierte Spieler folgen aktuell einer greedy Policy. Verschiedene Archetypen würden realistischere Gewinnraten liefern. Basis: Strategieprofile aus N1.
