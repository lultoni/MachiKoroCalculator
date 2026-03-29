# PLAN.md — MachiKoroCalculator Active Backlog

Open items only. For history see `CHANGELOG.md`, for math see `ARCHITECTURE.md`.

---

## Grundvision — Der ideale Calculator

### Ziel

Gegeben ein beliebiger Spielzustand (Münzen, Karten, Spieleranzahl, Würfelwurf) soll der Calculator die **optimale Aktion** empfehlen — nicht die heuristisch beste Karte isoliert, sondern die Entscheidung die den erwarteten Gewinn (Siegwahrscheinlichkeit) maximiert.

Das Ziel ist ein stochastischer Entscheidungsbaum im Expectimax-Stil: Für jeden möglichen Würfelwurf und jede mögliche Kaufaktion wird der resultierende Zustand rekursiv bewertet, gewichtet nach Wahrscheinlichkeit. Die Empfehlung ist die Aktion am Wurzel-Knoten mit dem höchsten erwarteten Sieg-Wert.

---

### Das Problem: Branching-Faktor und Suchtiefe

Ein naiver vollständiger Suchbaum ist nicht realisierbar. Ein Zug besteht aus zwei Knoten-Ebenen:

| Phase | Zufallsknoten (Würfel) | Entscheidungsknoten (Kauf) | 2-Ebenen-Größe/Zug |
|-------|------------------------|---------------------------|----------------------|
| Frühspiel (viele ungebaute Karten) | 6–11 Outcomes | ~20 Optionen | **~120–220** |
| Mittelspiel | 6–11 Outcomes | ~8 Optionen | ~50–90 |
| Endspiel (wenig Angebot) | 6–11 Outcomes | ~2–4 Optionen | ~15–45 |

Ein Spiel dauert im Schnitt ~30 Züge pro Spieler (120 Gesamtzüge bei 4 Spielern). Vollständige Enumeration bis Spielende: **unmöglich**. Bereits 2 eigene Züge tief (= 1 volle Runde bei 4 Spielern) ergibt ~220² ≈ 48.000 Teilbäume.

**Trotzdem ist ein guter Näherungsansatz realisierbar** — analog zu Backgammon-Engines (Expectimax + Stellungsbewertung), nicht vollständig suchend. Der korrekte theoretische Rahmen ist **Expectiminimax** für stochastische N-Spieler-Spiele, nicht alpha-beta (das gilt nur für 2-Spieler-Zero-Sum ohne Zufall).

---

### Architektur-Vision: Dreistufiges Hybrid-System

```
┌─────────────────────────────────────────────────────────────┐
│  Stufe 1: Expectimax-Rollout-Tree (kurze Tiefe, d Runden)   │
│  → Eine Runde = eigener Zug + N-1 simulierte Gegner-Züge    │
│  → Knoten: Chance (Würfel, gewichtet) + Decision (Kauf)     │
│  → Supply-State wird durch alle Tiefen mitgeführt           │
│  → Leaf evaluation via Stufe 2                              │
│  → Top-k Kauf-Kandidaten mit Stufe 3 (MC) validiert         │
├─────────────────────────────────────────────────────────────┤
│  Stufe 2: Analytische Leaf-Evaluation                       │
│  → portfolioEV-based softmax win-probability                │
│  → LANDMARK_WEIGHTS + dynamic remaining-turns               │
│  → Coin-Vorteil-Term + Endspiel-Proximity-Bonus             │
│  → Günstig: <0.1ms per Knoten                               │
├─────────────────────────────────────────────────────────────┤
│  Stufe 3: MC-Validierung (Budget-bewusstes Sampling)        │
│  → MC-Spiele ab Post-Buy-Zustand (Tiefe 0)                  │
│  → Boltzmann-sampled buy policy (T ≈ 0.7, nicht kalibriert) │
│  → Budget nach Kandidaten-Nähe aufteilen, nicht fix Top-3   │
│  → Teuer: ~1–5ms per Kandidat → nur für Top-k Root-Optionen │
└─────────────────────────────────────────────────────────────┘
```

**Entscheidungsfluss:**

1. Spieler hat gewürfelt → Münz-Deltas sind bekannt (Post-Roll-State)
2. **Stufe 1** expandiert alle Kaufoptionen an der Wurzel (inkl. Sparen)
3. Für jede Kaufoption: Expectimax-Baum der Tiefe `d` vollständig expandieren; Blätter via **Stufe 2** bewerten
4. Rangliste der Kaufoptionen nach erwartetem Sieg-Wert; Top-k (k ≤ 5) mit **Stufe 3** (MC) validiert
5. Stufe-3-Ergebnisse **ersetzen** die Stufe-2-Schätzung für die untersuchten Optionen; Ranking wird danach neu sortiert
6. Empfehlung = Aktion mit höchstem finalem `E[Siegwahrscheinlichkeit]`

---

### Stufe 1 im Detail: Expectimax-Rollout-Tree

**Eingabe:** Aktueller `GameState` nach Würfelwurf (Münzen schon verteilt); `depth d` in Runden; `topK` Kandidaten je Entscheidungsknoten.

**Tiefe-Definition:** Eine Tiefe = **eine vollständige Runde** = eigener Zug + (N−1) simulierte Gegner-Züge mit Boltzmann-Policy. Ohne Gegner-Züge zwischen den eigenen Kaufentscheidungen wären Blatt-Zustände bei d>1 unmöglich — die Münzstände und Portfolios der Gegner wären eingefroren, alle roten Karten zwischen den eigenen Zügen würden nicht auslösen.

**Knoten-Typen (pro Runde, pro Spieler):**
- **Zufallsknoten (Würfel):** Alle Würfelergebnisse werden probabilistisch gewichtet (`P1` oder `P2`); bei Freizeitpark-Besitz: Pasch-Outcomes (6 von 36) führen zu einem zusätzlichen Zufallsknoten (Bonus-Zug)
- **Entscheidungsknoten (Kauf):** Spieler wählt aus Top-k Optionen (eigener Spieler: exakt; Gegner: Boltzmann-Policy); Bürohaus auf Roll=6 wird als separater Tausch-Entscheidungsknoten behandelt
- **Funkturm-Knoten:** Entscheidungsknoten ob neu gewürfelt wird (wenn erster Roll schlechter als Erwartung); expandiert den aktuellen Chance-Knoten um einen weiteren Zufallsknoten

**Supply-State:** Jeder Tree-Knoten erbt eine Kopie des `unbuilt_projects`-Zustands. Käufe in Tiefe 1 reduzieren die verfügbaren Optionen in Tiefe 2 korrekt. Ohne Supply-Tracking würde der Baum Karten als kaufbar ausweisen, die erschöpft sind.

**Pruning-Strategien:**

| Strategie | Reduktion | Begründung |
|-----------|-----------|------------|
| Top-k Kaufoptionen per Entscheidungsknoten (k=5) | ~75% | Heuristisches Forward-Pruning; eliminiert Optionen mit <50% des besten `portfolioDeltaEV`. **Achtung:** Kann global-optimale Züge ausschließen (Horizont-Effekt). Akzeptabler Trade-off. |
| Endspiel-Tiefenerweiterung | variabel | Wenn beliebiger Spieler ≤ 8 Münzen vom Sieg entfernt: Suchtiefe +1 für diesen Ast (Quiescence-Analogie). Verhindert Endspiel-Blindheit. |
| Frühzeitiger Abbruch wenn Δ-Sieg < ε = 0.01 | ~20–30% | Irrelevante Äste nicht weiter expandieren; ε=0.01 (1%) da Stufe-2-Rauschen < 1% nicht sinnvoll auflösbar |

**Was entfernt wurde:**
- ~~Symmetrie-Pruning~~ — Kollisionsrate in kurzen Bäumen mit geteiltem Supply nahe null; praktisch wertlos.

**Geschätzte Kosten bei d=1 (1 Runde), k=5, 4 Spieler:**
- Eigener Zug: 5 Kaufoptionen × 8.5 Ø-Würfelergebnisse = ~42 Teilbäume
- Je Kaufoption: N−1 = 3 Gegner-Züge simuliert (Boltzmann-Policy, je ~1ms)
- Blätter: ~42 × 5 = ~210 Blattknoten
- Mit Stufe-2-Evaluation (0.1ms): ~21ms → **akzeptabel**
- Stufe-3 für Top-k=5 Kaufoptionen (500 sims je): ~5 × 18ms = ~90ms → im Hintergrund

---

### Stufe 2 im Detail: Analytische Leaf-Evaluation

Basis implementiert in `WinProbabilityCalc.computeBaselineWinProb`:

```
score(player p) = portfolioEvPerRound(p) × remainingTurns(leafState)
                + Σ LANDMARK_WEIGHT(p)
                + coinAdvantage(p, leafState)        ← neu
                + endgameProximityBonus(p, leafState) ← neu

P_win(i) = softmax(scores)[i]
```

**Wichtige Präzisierungen:**

- `remainingTurns` muss aus dem **Blatt-Zustand** geschätzt werden, nicht als globale Konstante. Verwendete Näherung: `max(3, 25 − turnsElapsed / n)`, wobei `turnsElapsed` die tatsächliche Züge-Tiefe des Blattes ist.
- `coinAdvantage(p)` = `(coins_p − avg_coins_opponents) / 10` als direkt kodierter Münz-Term (aktuell nur indirekt über EV reflektiert).
- `endgameProximityBonus(p)` = großer positiver Term wenn Spieler p das letzte Großprojekt im nächsten Zug kaufen kann; verhindert Endspiel-Blindheit der Softmax-Näherung.
- **Unit-Kalibrierung:** `portfolioEvPerRound × remainingTurns` ist in Münzen (~20–80 typisch). `LANDMARK_WEIGHTS` müssen in derselben Größenordnung liegen. Aktuelle Werte (Bahnhof=1.5, EKZ=3.0, FZP=1.5, FT=4.0) sind zu klein — effektive Landmark-Beiträge sollten auf ~5–20 skaliert werden um bedeutsam zu bleiben.

**Bekannte Schwächen (akzeptiert):**
- Koalitions-Dynamik nicht modelliert: Ein Spieler mit dominantem Portfolio wird in 4-Spieler-Runden kollektiv angegriffen (Fernsehsender, Stadion, Bürohaus). Softmax überschätzt systematisch den führenden Spieler. → Mitigation: MC-Stufe 3 fängt dies implizit auf.
- Portfoliodiversität (Varianz-Reduktion) nicht explizit modelliert.

**Qualität:** Gut für relative Rangordnung in Spielmitte (3–20 Züge). Schwach ohne die beiden neuen Terme nahe Spielende.

---

### Stufe 3 im Detail: MC-Validierung mit adaptivem Budget

**Zweck:** Stufe 2 liefert eine schnelle Vorrangliste. Stufe 3 validiert die Top-k Kaufoptionen an der **Wurzel** (Post-Buy, Tiefe 0) mit MC-Simulationen und **überschreibt** deren Stufe-2-Schätzung.

**Was Stufe 3 leistet und was nicht:**
- Stufe 3 läuft **nicht** von Blatt-Zuständen des Rollout-Trees, sondern von Post-Buy-Zuständen an der Wurzel.
- Das ist kein Widerspruch zu Stufe 1: Der Rollout-Tree (Stufe 1) verfeinert das Kurzzeit-Ranking über 1–2 Runden. Stufe 3 validiert das Langzeit-Endergebnis der Top-Kandidaten aus diesem Ranking unabhängig via Simulation.
- Stufe 3 ist **kein MCTS** (kein UCB1, kein Backpropagation). Es ist bewusstes Budget-Allocation für Flat-MC. Der Unterschied zu echtem MCTS: Stufe-2-Fehler die einen Kandidaten unter Top-k drücken werden nicht korrigiert. Dieser bekannte Bias wird akzeptiert.

**Budget-Allocation:**
- Nicht fix "Top-3 je 100 Sims", sondern adaptiv: wenn Top-k Kandidaten innerhalb von ε = 0.02 Win-Prob liegen, Budget gleichmäßig aufteilen; wenn ein Kandidat klar dominiert (>0.05 Vorsprung), Budget auf die restlichen konzentrieren.
- Gesamtbudget: ~2.500 Sims pro Kandidat (≈ 45ms je Kandidat bei 28k sims/s parallel), Top-5 = ~225ms → akzeptables UI-Budget im Hintergrund.

**MC-Policy (Boltzmann):**
```
P(buy X) ∝ exp(score(X) / T)

wobei:
  score(X) = contextualCardEvPerRound(X) × ROI_GEOMETRIC_SUM − X.cost
  T = Temperatur (0 = greedy, 0.5–1.0 = leichte Exploration, ∞ = uniform)
```

**T-Kalibrierung:** T=0.7 ist **nicht empirisch kalibriert** — es ist eine konservative Schätzung für "Spieler machen gelegentlich suboptimale Züge." Es gibt keinen Grund warum menschliches Spielverhalten einer Boltzmann-Verteilung über diesen Score folgt. Mögliche Verbesserung: Mehrere Gegner-Archetypen (Landmark-Rusher, Einkommens-Maximierer, Blockierer) statt einheitlichem T.

**Performance-Budget (gemessen):**
- ~28.000 parallele Sims/Sekunde
- 2.500 Sims: ~90ms pro Kandidat → realistisch für Top-5 im Hintergrund
- 10.000 Sims: ~350ms → realistisches UI-Budget für einzelne Deep-Analysis
- 100.000 Sims: ~3.5s → nutzbar als „Deep Analysis" on demand

---

### Was ist aktuell implementiert vs. was fehlt

| Komponente | Status | Qualität |
|-----------|--------|----------|
| `get_I` — alle 19 Karten | ✓ vollständig | Exakt |
| `computeAllDeltasForRoll` — single turn resolution | ✓ vollständig | Exakt (Rot→Blau/Grün→Lila, counter-clockwise) |
| `evPerRound` — 1-Runden-EV mit step-aware Münzprojektion | ✓ vollständig | Gut (Näherung bei Rot) |
| `roiOverHorizon` — diskontierter ROI | ✓ vollständig | Gut |
| `computeBaselineWinProb` — analytische Siegwahrscheinlichkeit | ✓ vollständig | Gut (softmax mit Coin-Term, Endspiel-Bonus, kalibrierte Landmark-Gewichte) |
| `mcWinRate` — MC-Simulation | ✓ vorhanden | Mittel (Boltzmann-Policy, aber unkalibriert) |
| `computeSynergyNote` — Synergie-Hinweis (1 Partner) | ✓ vorhanden | Begrenzt (per-Karte, nicht Portfolio) |
| `computeTwoTurnNote` — 2-Turn-Lookahead | ✓ vorhanden | Begrenzt (analytisch, nur Bahnhof als Landmark) |
| **Stufe 1: Expectimax-Rollout-Tree mit Gegner-Zügen** | ✓ implementiert | `RolloutTree.evaluate()` + UI-Tab |
| **Stufe 2: coinAdvantage + endgameProximityBonus** | ✓ implementiert | `computeScores` |
| **Stufe 2: LANDMARK_WEIGHTS Neukalibrierung** | ✓ implementiert | Bahnhof=24, EKZ=36, FZP=24, FT=48 |
| **Stufe 3: Adaptives Budget-Splitting** | ✓ implementiert | `adaptiveMCRefinement` in `rankPurchasableProjects` |
| **Stufe 3: Boltzmann-MC-Policy** | ✓ M7 | Implementiert |
| **Portfolio-Synergie** `portfolioDeltaEV` | ✓ implementiert | Implementiert |
| **Gegner-Modellierung** (adaptive Archetypen) | ❌ fehlt | Future Feature |

---

### Prioritäten für nächste Entwicklungsschritte

#### Schritt 1 — M7: Boltzmann-MC-Policy ✓ (implementiert)

**Scope:** `RankingOptions.mcExplorationTemp`, `GameSimulator.boltzmannBuy()`, UI-Toggle. ✓ Erledigt.

---

#### Schritt 2 — Portfolio-Synergie: `portfolioDeltaEV` ✓ (implementiert)

**Implementiert:** `ProbabilityCalc.portfolioDeltaEV(gs, pi, cardA)` + `RankEntry.portfolioDeltaEV` + UI-Spalte + Card-Details-Zeile. ✓ Erledigt.

---

#### Schritt 3 — Leaf-Evaluator verbessern ✓ (implementiert)

**Implementiert:** `coinAdvantage`-Term (`COIN_ADVANTAGE_SCALE=5.0`), `endgameProximityBonus` (×2.5 wenn 3 LMs und Coins ≥ letzte LM-Kosten), LANDMARK_WEIGHTS neukalibriert (Bahnhof=24, EKZ=36, FZP=24, FT=48, Default=20). ~30 Zeilen in `WinProbabilityCalc.computeScores`.

---

#### Schritt 4 — Rollout-Tree Enumerator ✓ (implementiert)

**Implementiert:** `RolloutTree.evaluate(gs, pi, depth, topK)` in `logic.probability`. `RolloutResult` record. Kandidaten-Pruning via `portfolioDeltaEV`, Gegner-Simulation via `GameSimulator.boltzmannBuy(T=0.7)`, Blatt-Evaluation via `computeBaselineWinProb`. Sonderfälle: Bahnhof (1d6 vs 2d6), Freizeitpark (Pasch → Bonus-Zug), Funkturm (Re-Roll wenn schlechter als Baseline). UI: 5. Tab "Rollout" in `MainWindow` mit Tiefe/Top-K-Spinnern und Run-Button (SwingWorker-Hintergrundausführung).

---

#### Schritt 5 — Adaptives MC-Budget (Stufe 3) ✓ (implementiert)

**Implementiert:** `adaptiveMCRefinement` in `ProbabilityCalc.rankPurchasableProjects`. Konstanten: `MC_TOP_K=5`, `MC_EQUAL_BUDGET_EPSILON=0.02`, `MC_DOMINANT_LEAD_THRESHOLD=0.05`, `MC_SIMS_PER_CANDIDATE_EQUAL=2500`. Budget-Logik: wenn Spread ≤ 0.02 oder kein dominanter Anführer → alle Top-k validieren; sonst Anführer überspringen, Verfolger validieren. MC-Ergebnisse überschreiben Stufe-2-Schätzungen.

---

### Akzeptierte Näherungen (kein sofortiger Handlungsbedarf)

| # | Thema | Erklärung |
|---|-------|-----------|
| A1 | Bürohaus — step-aware Projektion | Blaues Einkommen wird schrittsweise akkumuliert; integer-Rundungsfehler vernachlässigbar. |
| A2 | Bahnhof-Würfelwahl im Simulator | `rollDice()` wählt 2d6 wenn Karte ≥ 7 vorhanden — Heuristik statt exakter EV. Akzeptabler Trade-off. |
| A3 | `contextualCardEvPerRound` — per-Karte-Max statt Portfolio-optimal | Bahnhof-Entscheidung per Karte als max(EV_1d6, EV_2d6). Wird durch portfolioDeltaEV gemildert. |
| A4 | Softmax-Win-Prob ignoriert Koalitions-Dynamik | Führender Spieler wird systematisch überschätzt (kollektive Angriffe nicht modelliert). Mitigation: MC-Stufe 3 fängt dies implizit auf. |
| A5 | Boltzmann T=0.7 unkalibriert | Keine empirische Basis; konservative Schätzung für "gelegentlich suboptimale Gegner". Akzeptabel solange kein Spiellog-Datensatz verfügbar. |
| A6 | Stufe 3 kein echtes MCTS | Kein UCB1, kein Backpropagation. Kandidaten die Stufe 2 unterschätzt bekommen keine MC-Korrektur. Bekannter Bias, akzeptiert. |
| A7 | Top-k Pruning (Horizont-Effekt) | Globale Optima außerhalb Top-k werden niemals untersucht (Blockade-Käufe, Supply-Denial). Dokumentierter Trade-off gegen Laufzeit. |

---

## Offene Items (bestehend)

### Code-Qualität

#### C4 · File Split Priority 2 (Low, deferred)

`MainWindow` ist groß. Sinnvolle Aufteilung wenn ein UI-Test-Layer existiert:
- `UIDataModel` (~50 Zeilen): hält `session`, `rankOpts`, `lastRanking`
- `RankingUIRenderer` (~100 Zeilen): `rebuildTable`, `populateCenter`, `clearCenter`, `buildNote`
- `GameController` (dünn): Turn-Anwendung, Undo, Snapshot, Save/Load-Dispatch

---

#### C5 · ~~Deep Code Optimization~~ ✓ (behoben)

`buildRollGainCache(state, playerIndex)` → `double[13]` prefill, shared across `computeOwnTurnEV`, `computeVariance*`, `computeProbNoIncome*`, `optimalDiceCount`. `computeOwnTurnEV(state, pi, cache, ...)` extrahiert das duplizierte Bahnhof/FZP/FT-Entscheidungsblock aus `immediateEV` und `evPerRound`. Ergebnis: `computeNetGainForRoll` wird von ~84 auf 12 Aufrufe pro `immediateEV`/`evPerRound`-Aufruf reduziert.

---

### UI-Verbesserungen

#### U2 · Kategorie-Icons im UI (Low)

Die 8 Kategorien mit kleinen Icons (16×16) in `src/resources/category_icons/` darstellen. Swing unterstützt keine inline-Images in JLabel-HTML — Panel mit FlowLayout oder `paintComponent`-Renderer nötig.

---

### Neue Features

#### N4d · Fitting → `AssistantConfig`

- `PhaseFitter.fit(List<LabeledSnapshot>)` — lineare Regression gegen drei Label-Werte
- Mindest-Labels für sinnvolle Regression: ~50
- **Voraussetzungen:** N3 + N4a + N4b (alle implementiert ✓)

---

### Math-Items

#### M7 · ~~MC-Rollout-Policy: Boltzmann-Exploration Toggle~~ ✓ (behoben)

**Implementiert:** `RankingOptions.mcExplorationTemp`, `GameSimulator.simulate(state, rng, temperature)`, `GameSimulator.boltzmannBuy(...)`. UI: T-Spinner neben N-Spinner in der Button-Bar.

---

## Future Features (nicht priorisiert)

- **Erweiterungskarten** — Hafen/Millionärsreihe. Architektur ist bereit (JSON-getrieben).
- **Gegner-Archetypen** — Verschiedene Buy-Strategien (Landmark-Rusher, Einkommens-Maximierer, Blockierer) für realistischere und differenziertere Win-Raten statt einheitlichem Boltzmann-T.
- **Echtes MCTS** — UCB1-basierter Baum mit Backpropagation; würde Stufe-2-Bias-Korrekturen ermöglichen die das aktuelle System strukturell nicht leisten kann.
- **Boltzmann-Kalibrierung** — T aus echten Spiellogs schätzen (Logistic Regression: welches T sagt beobachtete Kaufentscheidungen am besten vorher?).

---

## Geschichte abgeschlossener Items

### Math-Audit ✓
M1 (Architektur-Audit) · M2 (Funkturm-EV) · M3 (dynamisches remainingTurns) · M4 (Landmark-Gewichte) · M5 (Warten-Option) · M6 (Bahnhof-Synergie im 2-Turn-Lookahead) · M7 (Boltzmann-MC-Policy) · M8 (Bahnhof-Gate im Simulator)

### Code-Qualität ✓
C5 (buildRollGainCache + computeOwnTurnEV — DRY-Refactoring hot path)

### Features ✓
N0 (Bürohaus-Tausch) · N1 (Game Assistant) · N2 (Bahnhof-Würfelwahl) · N3 (Phasenerkennung) · N4a–N4c (Snapshot/Labeling-System)

### UI ✓
U1 (rechtes Panel) · U3 (Trigger-Modus-Anzeige)

### Future Strategy ✓
Synergy-Lookahead · 2-Turn Lookahead · MC-Policy (greedy → roiOverHorizon) · Stufe-2 (coinAdvantage + endgameProximityBonus + LANDMARK_WEIGHTS) · Stufe-1 RolloutTree · Stufe-3 Adaptives MC-Budget
