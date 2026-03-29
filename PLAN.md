# PLAN.md — MachiKoroCalculator Active Backlog

Open items only. For history see `CHANGELOG.md`, for math see `ARCHITECTURE.md`.

---

## Grundvision — Der ideale Calculator

### Ziel

Gegeben ein beliebiger Spielzustand (Münzen, Karten, Spieleranzahl, Würfelwurf) soll der Calculator die **optimale Aktion** empfehlen — nicht die heuristisch beste Karte isoliert, sondern die Entscheidung die den erwarteten Gewinn (Siegwahrscheinlichkeit) maximiert.

Das Ziel ist ein stochastischer Entscheidungsbaum: Für jeden möglichen Würfelwurf und jede mögliche Kaufaktion wird der resultierende Zustand rekursiv bewertet, gewichtet nach Wahrscheinlichkeit. Die Empfehlung ist die Aktion am Wurzel-Knoten mit dem höchsten erwarteten Sieg-Wert.

---

### Das Problem: Branching-Faktor und Suchtiefe

Ein naiver vollständiger Suchbaum ist nicht realisierbar:

| Phase | Würfelergebnisse | Kaufoptionen | Branching/Zug |
|-------|-----------------|--------------|---------------|
| Frühspiel (viele ungebaute Karten) | 6–11 | ~20 | **~120–150** |
| Mittelspiel | 6–11 | ~8 | ~50–80 |
| Endspiel (wenig Angebot) | 6–11 | ~2–4 | ~15–25 |

Ein Spiel dauert im Schnitt ~30 Züge pro Spieler (120 Gesamtzüge bei 4 Spielern). Vollständige Enumeration bis Spielende: **unmöglich**. Bereits 3 Züge Tiefe = ~150³ ≈ 3,4 Mio Knoten im Frühspiel.

**Trotzdem ist ein guter Näherungsansatz realisierbar** — analog zu Schach-Engines die alpha-beta mit Stellungsbewertung kombinieren, nicht vollständig suchen.

---

### Architektur-Vision: Dreistufiges Hybrid-System

```
┌─────────────────────────────────────────────────────────┐
│  Stufe 1: Exakter Rollout-Tree (kurze Tiefe, aktueller  │
│           Spieler, ~2–3 Züge)                            │
│  → Enumerate all (roll × buy) pairs for depth d         │
│  → Leaf evaluation via Stufe 2 or 3                     │
├─────────────────────────────────────────────────────────┤
│  Stufe 2: Analytische Leaf-Evaluation                   │
│  → portfolioEV-based softmax win-probability            │
│  → LANDMARK_WEIGHTS + dynamic remaining-turns           │
│  → Cheap: <0.1ms per node                               │
├─────────────────────────────────────────────────────────┤
│  Stufe 3: MC-Rollout (tiefere Validierung)              │
│  → Parallel MC games from leaf states                   │
│  → Boltzmann-sampled buy policy (nicht greedy)          │
│  → Teuer: ~1–5ms per node → nur für Top-k Blätter       │
└─────────────────────────────────────────────────────────┘
```

**Entscheidungsfluss:**

1. Spieler hat gewürfelt → Münz-Deltas sind bekannt
2. **Stufe 1** enumiert alle Kaufoptionen (inkl. Sparen) für diesen exakten Post-Roll-Zustand
3. Für jede Kaufoption: Leaf-Evaluation via **Stufe 2** (analytisch, schnell)
4. Top-3 Optionen werden zusätzlich mit **Stufe 3** (MC, genau) bewertet
5. Empfehlung = Aktion mit höchster `E[Siegwahrscheinlichkeit]`

---

### Stufe 1 im Detail: Rollout-Tree

**Eingabe:** Aktueller `GameState` nach Würfelwurf (Münzen schon verteilt)

**Aufgabe:** Für eine Suchtiefe `d` alle Zustandsfolgen enumieren.

**Knoten-Typen:**
- **Entscheidungsknoten (Kauf):** Spieler wählt Aktion; alle Optionen werden verzweigt
- **Zufallsknoten (Würfel):** Alle Würfelergebnisse werden probabilistisch gewichtet (`P1` oder `P2`)

**Pruning-Strategien:**

| Strategie | Reduktion | Begründung |
|-----------|-----------|------------|
| Top-k Kaufoptionen per Knoten (k=5) | ~75% | Schlechte Karten (<50% des besten ROI) kaum relevant |
| Symmetrie-Pruning: gleiche Portfolios → zusammenführen | variabel | Wenn Spieler A+B gleiche Karten kauft: nur 1 Ast |
| Tiefe 1–2 für 4 Spieler, Tiefe 2–3 für 2 Spieler | — | Tiefe 2 bei 4 Spielern ≈ 150² = 22.500 Knoten |
| Frühzeitiger Abbruch wenn Δ-Sieg < ε = 0.001 | ~30% | Irrelevante Äste nicht weiter expandieren |

**Geschätzte Kosten bei d=2, k=5:**
- ~5 Kaufoptionen × 6–11 Würfelergebnisse × 5 Optionen = ~275 Blätter
- Mit Stufe-2-Evaluation (0.1ms): ~28ms → **akzeptabel**
- Mit Stufe-3-Top-3 (100 sims): ~3 × 50ms = 150ms zusätzlich → im Hintergrund

---

### Stufe 2 im Detail: Analytische Leaf-Evaluation

Bereits implementiert in `WinProbabilityCalc.computeBaselineWinProb`:

```
score(player p) = playerEvPerRound(p) × remainingTurns + Σ LANDMARK_WEIGHT(p)
P_win(i) = softmax(scores)[i]
```

**Qualität:** Gut für relative Rangordnung, aber Schwäche bei Zuständen kurz vor Spielende (letzter Landmark-Kauf). Für mittlere Spieltiefen (3–20 Züge) sehr gut.

**Verbesserungspotenzial:**
- Portfoliodiversität (Risikoreduktion) noch nicht modelliert
- Coin-Vorteil nicht direkt kodiert (nur indirekt über EV)
- Gegner-Interaktion (rote Karten, Bürohaus) nur statistisch

---

### Stufe 3 im Detail: MC-Rollout mit Boltzmann-Policy

**Aktueller Stand:** MC vorhanden, aber mit deterministisch-greedy Buy-Policy → systematischer Bias.

**Geplante Policy:** Boltzmann-Sampling

```
P(buy X) ∝ exp(score(X) / T)

wobei:
  score(X) = contextualCardEvPerRound(X) × ROI_GEOMETRIC_SUM − X.cost
  T = Temperatur (0 = greedy, 0.5–1.0 = leichte Exploration, ∞ = uniform)
```

**Warum Boltzmann statt greedy?**
- Greedy überschätzt die Qualität der eigenen Kaufentscheidungen (alle Spieler spielen „perfekt")
- Boltzmann mit T≈0.7 simuliert realistischere Spieler: können suboptimale Züge machen
- Gibt realistischere Win-Raten für suboptimale Spielpfade des echten Spielers

**Performance-Budget (gemessen):**
- ~28.000 parallele Sims/Sekunde
- 10.000 Sims: ~350ms → realistisches UI-Budget
- 100.000 Sims: ~3.5s → zu langsam für Real-Time, aber nutzbar als „Deep Analysis"

---

### Was ist aktuell implementiert vs. was fehlt

| Komponente | Status | Qualität |
|-----------|--------|----------|
| `get_I` — alle 19 Karten | ✓ vollständig | Exakt |
| `computeAllDeltasForRoll` — single turn resolution | ✓ vollständig | Exakt (Rot→Blau/Grün→Lila, counter-clockwise) |
| `evPerRound` — 1-Runden-EV mit step-aware Münzprojektion | ✓ vollständig | Gut (Näherung bei Rot) |
| `roiOverHorizon` — diskontierter ROI | ✓ vollständig | Gut |
| `computeBaselineWinProb` — analytische Siegwahrscheinlichkeit | ✓ vollständig | Mittel (softmax-Näherung) |
| `mcWinRate` — MC-Simulation | ✓ vorhanden | Mittel (greedy-Policy-Bias) |
| `computeSynergyNote` — Synergie-Hinweis (1 Partner) | ✓ vorhanden | Begrenzt (per-Karte, nicht Portfolio) |
| `computeTwoTurnNote` — 2-Turn-Lookahead | ✓ vorhanden | Begrenzt (analytisch, nur Bahnhof als Landmark) |
| **Stufe 1: Rollout-Tree Enumerator** | ❌ fehlt | — |
| **Stufe 3: Boltzmann-MC-Policy** | ❌ fehlt (M7) | — |
| **Portfolio-Synergie** (nicht per-Karte) | ❌ fehlt | — |
| **Gegner-Modellierung** (adaptive Strategie) | ❌ fehlt | — |

---

### Prioritäten für nächste Entwicklungsschritte

#### Schritt 1 — M7: Boltzmann-MC-Policy (Fundament für alle weiteren MC-basierten Berechnungen)

**Warum zuerst:** MC ist das stärkste Werkzeug. Solange die Policy deterministisch greedy ist, sind alle MC-Ergebnisse verzerrt. Das zu reparieren verbessert sofort die Win-Prob-Qualität und alle darauf aufbauenden Empfehlungen.

**Scope:** ~80 Zeilen — `RankingOptions.mcExplorationTemp`, `GameSimulator.boltzmannBuy()`, UI-Toggle.

---

#### Schritt 2 — Portfolio-Synergie: `portfolioDeltaEV`

**Problem heute:** `computeSynergyNote` bewertet die Synergie aus Sicht der Karte A ("macht B meine Karte A besser?"). Die relevantere Frage ist: "Erhöht das Paar (A, B) das **Gesamt-Portfolio-EV** mehr als A alleine?"

**Neue Methode:** `portfolioDeltaEV(gs, pi, cardA)`:
```
Δ = playerEvPerRound(portfolio + A) − playerEvPerRound(portfolio)
```
Anstatt `contextualCardEvPerRound(A)` als Proxy. Nutzt bereits vorhandene `playerEvPerRound` — nur der Aufruf fehlt.

**Warum besser:** `playerEvPerRound` berechnet das Portfolio korrekt als Ganzes (alle Karten zusammen, alle Würfelverteilungen, alle Spieler). Die Differenz ist exakt der Marginalwert von Karte A.

**Scope:** ~30 Zeilen in `ProbabilityCalc` + Umbau `rankPurchasableProjects`.

---

#### Schritt 3 — Rollout-Tree Enumerator (Kernarchitektur)

**Neue Klasse:** `RolloutTree` in `logic.probability`

```java
class RolloutTree {
    // Enumeriert alle (Würfel × Kauf)-Pfade bis Tiefe d
    // Wertet Blätter via analytischer Win-Prob aus
    // Gibt pro Kaufoption den erwarteten Sieg-Wert zurück

    static RolloutResult evaluate(GameState gs, int playerIndex,
                                   int depth, int topK);

    record RolloutResult(Project bestAction, double expectedWinProb,
                         Map<Project, Double> allActionValues) {}
}
```

**Integration in UI:** Ersetzt die aktuelle `rankAllProjects`-Logik als „tiefer" Analysemode.

**Scope:** ~200 Zeilen neue Klasse + Integration.

---

### Akzeptierte Näherungen (kein sofortiger Handlungsbedarf)

| # | Thema | Erklärung |
|---|-------|-----------|
| A1 | Bürohaus — step-aware Projektion | Blaues Einkommen wird schrittsweise akkumuliert; integer-Rundungsfehler vernachlässigbar. |
| A2 | Bahnhof-Würfelwahl im Simulator | `rollDice()` wählt 2d6 wenn Karte ≥ 7 vorhanden — Heuristik statt exakter EV. Akzeptabler Trade-off. |
| A3 | `contextualCardEvPerRound` — per-Karte-Max statt Portfolio-optimal | Bahnhof-Entscheidung wird per Karte als max(EV_1d6, EV_2d6) berechnet, nicht global. Wird durch Schritt 2 (portfolioDeltaEV) ersetzt. |
| A4 | Softmax-Win-Prob ist EV-basiert | Berücksichtigt keine Portfoliodiversität, keine Coin-Vorteile direkt. Akzeptabel für Ranking; Schritt 3 ersetzt als Leaf-Evaluator. |

---

## Offene Items (bestehend)

### Code-Qualität

#### C4 · File Split Priority 2 (Low, deferred)

`MainWindow` ist groß. Sinnvolle Aufteilung wenn ein UI-Test-Layer existiert:
- `UIDataModel` (~50 Zeilen): hält `session`, `rankOpts`, `lastRanking`
- `RankingUIRenderer` (~100 Zeilen): `rebuildTable`, `populateCenter`, `clearCenter`, `buildNote`
- `GameController` (dünn): Turn-Anwendung, Undo, Snapshot, Save/Load-Dispatch

---

#### C5 · Deep Code Optimization (Medium, unpriorisiert)

Vollständige Optimierungsrunde:
- Hot-Path-Profiling: `rankAllProjects` → `evPerRound` → `computeNetGainForRoll`
- `computeNetGainForRoll` wird pro `evPerRound`-Aufruf mehrfach für dieselbe `PlayerStats` aufgerufen — memoization prüfen
- Redundante Bahnhof/Funkturm/Freizeitpark-Prüflogik in `immediateEV`, `evPerRound`, `computeVarianceOwnTurn`, `computeProbNoIncomeOwnTurn` — DRY-Kandidat

**Voraussetzung:** Bestehende Tests müssen weiterhin bestehen; keine Verhaltensänderung.

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

#### M7 · MC-Rollout-Policy: Boltzmann-Exploration Toggle (Prio: Hoch)

Aktueller greedy Simulator wählt immer die Karte mit höchstem `evPerRound/cost`. Das führt zu systematisch verzerrten Win-Raten.

**Geplante Änderung:**
- `RankingOptions.mcExplorationTemp` (double, default 0.0 = greedy): Boltzmann-Temperatur T
- Bei T > 0: `P(buy X) ∝ exp(score(X) / T)`
- Empfohlener Standard: T = 0.7
- Toggle in MainWindow: "Exploration" Checkbox neben MC-Slider

**Scope:** ~80 Zeilen.

---

## Future Features (nicht priorisiert)

- **Erweiterungskarten** — Hafen/Millionärsreihe. Architektur ist bereit (JSON-getrieben).
- **Gegner-Archetypen** — Verschiedene Buy-Strategien für realistischere Win-Raten.
- **Rollout-Tree** — Schritt 3 der Vision oben. Kernarchitektur für exakte Empfehlungen.
- **Portfolio-Synergie** — Schritt 2: `portfolioDeltaEV` als Basis für alle Ranking-Berechnungen.

---

## Geschichte abgeschlossener Items

### Math-Audit ✓
M1 (Architektur-Audit) · M2 (Funkturm-EV) · M3 (dynamisches remainingTurns) · M4 (Landmark-Gewichte) · M5 (Warten-Option) · M6 (Bahnhof-Synergie im 2-Turn-Lookahead) · M8 (Bahnhof-Gate im Simulator)

### Features ✓
N0 (Bürohaus-Tausch) · N1 (Game Assistant) · N2 (Bahnhof-Würfelwahl) · N3 (Phasenerkennung) · N4a–N4c (Snapshot/Labeling-System)

### UI ✓
U1 (rechtes Panel) · U3 (Trigger-Modus-Anzeige)

### Future Strategy ✓
Synergy-Lookahead · 2-Turn Lookahead · MC-Policy (greedy → roiOverHorizon)
