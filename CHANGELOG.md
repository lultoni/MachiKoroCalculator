# CHANGELOG.md — MachiKoroCalculator

Implementierungsgeschichte: was gebaut wurde, warum, und welche Designentscheidungen getroffen wurden.

---

## M7 — Boltzmann-Exploration Toggle für MC-Simulator

Der MC-Simulator verwendete bisher eine rein deterministische Greedy-Policy: alle simulierten Spieler kaufen immer die Karte mit dem höchsten ROI-Score. Das führte zu systematisch verzerrten Win-Raten — Spieler die von der optimalen Strategie abweichen, wurden als schlechter dargestellt als sie tatsächlich sind.

**`GameSimulator.simulate(state, rng, temperature)`** — neue Überladung mit Boltzmann-Temperatur T. Bei T=0 wird die bestehende `greedyBuy()`-Methode aufgerufen (identisches Verhalten). Bei T>0 ruft `boltzmannBuy()` auf: Scores aller erschwingl. Karten werden via Softmax in eine Wahrscheinlichkeitsverteilung umgewandelt, aus der stochastisch gesampelt wird.

**Formel:** `P(buy X) ∝ exp((score(X) − max_score) / T)` mit max-Subtraktion für numerische Stabilität.

**Landmark-Priorität** (Bahnhof-Gate + Kosten-Reihenfolge) bleibt in beiden Modi deterministisch — nur die Establishments werden stochastisch gewählt.

**`RankingOptions.mcExplorationTemp`** (default 0.0) — neues Feld, wird durch `rankPurchasableProjects`, `rankAllProjects` und `mcWinRate` durchgereicht.

**UI:** T-Spinner (Bereich 0.0–5.0, Schritt 0.1) neben dem N-Spinner in der Button-Bar. Empfohlener Wert: T=0.7.

**Tests:** 228 PASS, 0 FAIL.

---

## Bahnhof-Synergie-Fixes: M6 (Lookahead), M8 (Simulator-Gate)

### Problem
Simulierte Spieler (MC/Labeling) kauften Bahnhof in `GameSimulator.greedyBuy` zu früh — ohne Karten mit Aktivierung ≥ 7 bringt 2d6 keinen EV-Vorteil, der Kauf ist wertlos. Außerdem zeigte `computeTwoTurnNote` keine Bahnhof-Synergien: „Bergwerk kaufen → dann lohnt sich Bahnhof" war nie als Note sichtbar.

### M8 — Bahnhof-Gate in `GameSimulator.greedyBuy`
Vor Bahnhof-Kauf wird jetzt `hasHighRangeCard(player)` geprüft — gibt `true` zurück wenn der Spieler mindestens eine Nicht-Landmark mit Aktivierung ≥ 7 besitzt. Ohne solche Karte wird der Bahnhof-Kauf übersprungen (nächste Landmark in der Prioritätsreihenfolge wird probiert, oder Establishment-Phase tritt ein). Die gleiche Logik war bereits in `rollDice` implementiert; jetzt konsistent für den Kauf.

### M6 — Bahnhof-Synergie in `computeTwoTurnNote`
Der generelle Landmark-Skip (`continue` für alle `is_grossprojekt`) wurde durch eine gezielte Bahnhof-Behandlung ersetzt:
- Wenn `cardB=Bahnhof` und Spieler hat ihn noch nicht: berechne `contextualCardEvPerRound(cardA, statsWithAB)` vs `contextualCardEvPerRound(cardA, statsAfterA)`. Die Differenz ist der Synergy-Gewinn den Bahnhof für Karte A bringt. ROI(Bahnhof für A) = synergyGain × geometricSum − cost(Bahnhof). Konservative Untergrenze — berücksichtigt nur A's Synergy, nicht andere Karten. Wenn selbst das ROI > 0.5 ergibt, erscheint die Note.
- Wenn `cardA=Bahnhof`: `statsAfterA.hasBahnhof=true` → `contextualCardEvPerRound(Bergwerk, statsAfterA)` berechnet automatisch 2d6-EV für Bergwerk. Die beste 7–12 Karte erscheint dann korrekt als Follow-up.

**Tests:** 228 PASS, 0 FAIL.

---



**`computeTwoTurnNote(gs, pi, cardA, candidates, horizon, discount)`** — new package-private static method in `ProbabilityCalc`. For each affordable candidate card A, evaluates all remaining candidates as a potential follow-up purchase B. Uses `CardIncome.contextualCardEvPerRound(B, statsAfterA, n, oppCoins)` — no `GameState.copy()` needed, only `PlayerStats` are constructed. Selects B with the highest estimated `roiOverHorizon` in the post-A portfolio state. Returns a note like "Danach: Bergwerk (ROI +4.2)" when the follow-up ROI exceeds 0.5 (threshold to avoid noise). Landmarks excluded from candidates (their interaction is too complex for this level).

Wired into both `rankPurchasableProjects` and `rankAllProjects` (affordable cards only) alongside the existing synergy note. Notes are concatenated with `"  |  "` separator. `Strings.twoTurnNote(name, roi)` added.

Performance: ranking benchmark still passes at < 5ms avg (O(n²) per ranking call, but n ≤ 19 cards so ≤ 361 `contextualCardEvPerRound` calls with no allocations beyond `PlayerStats`).

**Tests:** 228 PASS, 0 FAIL (MC sum test occasionally flaky by design).

---

## UI-Polishing: Bug-Fixes, Runner-Ups, Win-Prob always-on, Income Matrix

**Bug-Fixes:**
- Roll-change no longer resets buy selection unless the previously selected card is no longer affordable after the new roll (preserves selection correctly).
- Roll-change now always re-ranks analytically (was blocked when MC mode was active).
- Phase label in context profile now shows a continuous blend ("Früh 30% · Mitte 70%") instead of a single label.
- EKZ GP hint now computes actual EV gain via `portfolioEvPerRound` diff; no longer shows "+0.00¢/Runde".
- Wait sentinel ("≡ Sparen") now appears in the affordable tab (it's a valid this-turn choice).
- Removed all emoji from UI strings — replaced with `[GP]`, `[+]`, `[!]`, `[W]`/`[D]` prefixes for cross-platform safety.

**Runner-Ups per assistant profile:** Each profile row in the Game Assistant now shows the 2nd and 3rd place cards in a right-aligned column ("2. Bergwerk  3. Wald"), so the uniqueness of the top recommendation is immediately visible. `runnerUpNames(metric, lowerIsBetter, winnerId, max)` helper; `addAssistantRow` overload with `BorderLayout` right column.

**Win-prob always on / MC on by default:** The win-probability delta toggle button is removed — win prob is always shown in the card detail panel. Deep Analysis (MC) is enabled by default. `showWinProb` field removed; `rankOpts.includeWinProbDelta = true` and `rankOpts.mcSimulations = mcSimCount` set in constructor.

**Extended `GamePhaseContext`:** Eleven new fields added — `catchUpStrength`, `pullAheadStrength`, `evGapVsLeader`, `coinAdvantage`, `portfolioDiversity`, `turnsToOwnWin`, `minTurnsToOppWin`, `ekzEvGain`, plus synergy gap detection (`synergyGapExists`, `synergyGapCard`, `synergyGapGain`). `addContextProfile` uses position modifiers (catch-up boosts GPRush/Aggro/Cheap; pull-ahead boosts ROI/Safe/LowVar), coin advantage, and diversity gap on top of the phase interpolation.

**Income Matrix (collapsible):** New toggle button in the left panel between roll preview and buy dropdown. Shows a grid of coin deltas for all players (rows = roll values 1–12 or 1–6, columns = players), color-coded green/red. Hidden by default; lazily refreshed on show and on every roll change. `refreshIncomeMatrix()` method; `incomeMatrixPanel` + `incomeMatrixToggleBtn` fields.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## N4d–N4f: Continuous Phase Weights + LabelingWindow UX + PhaseFitter

**N4d — Kontinuierliche Phasen-Gewichte:** `computePhaseContext` berechnet jetzt drei kontinuierliche Stärken `earlyStrength`, `midStrength`, `lateStrength` ∈ [0,1] (Summe = 1). Spät-Stärke: linearer Ramp über GP-Anzahl → `LATE_GP_THRESHOLD`. Früh-Stärke: Mittelwert aus EV-Schwäche (`1 - avgEv/threshold`) und EKZ-Erreichbarkeit (0/1). Mid = Restant. `addContextProfile` interpoliert Gewichte als `w[i] = earlyStr × WEIGHTS_EARLY[i] + midStr × WEIGHTS_MID[i] + lateStr × WEIGHTS_LATE[i]` — kein hartes Snap mehr auf eine Phase. `phaseLabel` wird nur noch für die Anzeige und als Tiebreaker gesetzt (höchste Stärke).

**N4e — LabelingWindow UX:** Einzelner Phase-Slider (0=Früh, 50=Mitte, 100=Spät) ersetzt drei unabhängige Slider. Live-Label "Früh 80% · Mitte 20% · Spät 0%" unter Slider. Auto-Save nach jedem "Nächster Snapshot"-Klick in `phase_labels.json`. Labels werden beim Öffnen des Fensters wiederhergestellt.

**N4e — Detaillierte Label-Exports:** JSON enthält jetzt: `gp_count`, `gps` (Liste der GP-IDs in Kaufreihenfolge), `non_gp_cards`, `cards` (Liste von `{id, count}`), `features` Block (`avg_gps`, `max_gps`, `avg_cards`, `avg_coins`) — direkt für `PhaseFitter` verwendbar ohne Re-Berechnung.

**N4e — SnapshotCard UX:** GP-Leiste mit benannten Slots und Tooltips (GP-Name + Kosten + gebaut/nicht gebaut). Karten-Liste zeigt Projektnamen mit ×N-Multiplikator. Münzen-Zeile mit Text-Label statt Emoji (vermeidet Rendering-Probleme). Karten-Liste erhält `CENTER`-Layout-Slot — füllt restlichen Platz.

**N4f — PhaseFitter:** Neue Klasse `gui.newui.PhaseFitter`. OLS-Regression (Normalengleichungen, Gauß'sche Elimination mit Partial Pivoting) auf `phase_labels.json`. Features: `[1, avg_gps, max_gps, avg_cards, avg_coins]`. Deriviert `LATE_GP_THRESHOLD` aus max_gps-Wert wo Late-Score = 0.5. R²-Bericht im Kalibrier-Dialog. "Kalibrieren…"-Button in `LabelingWindow` triggert Fit + Update via Reflection (Fallback: zeigt Ergebnis ohne Anwendung auf Java 17+).

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## N4a–N4c: SnapshotCard + SnapshotGenerator + LabelingWindow

**N4a — `SnapshotCard.java`:** Neues kompaktes Player-Panel in `gui.newui`. Zeigt: Spielername, Münzen (Clickable / Spinner in edit mode), GP-Fortschrittsleiste (0–4, farbkodiert: grün=führend, gelb=mittel, rot=hinten), farbige Karten-Chips (Blau/Grün/Rot/Lila/Gelb als aggregierte Chips mit ×N-Zähler), EV/Runde via `portfolioEvPerRound`. In Edit-Mode (Doppelklick oder `setEditable(true)`): `BoundedSpinner` für Münzen, pro-Farbe-Spinner/Checkbox für alle Karten — gleiche Validierungslogik wie `SnapshotDialog`. API: `setPlayer(Player)`, `getEditedPlayer() → Player`, `setEditable(boolean)`, `addChangeListener(...)`. `.mkoro`-kompatibel: `getEditedPlayer()` liefert direkt einen `Player` für `GameStateBuilder`.

**N4b — `SnapshotGenerator.java`:** Neue public Klasse in `logic.probability`. `generate(numPlayers, minTurn, maxTurn)`: Simuliert ein frisches Spiel (greedy via `GameSimulator.applyRoll` + `greedyBuy`) bis zu einem Zufallszug im Bereich und gibt den `GameState`-Deep-Copy zurück. `generateFromFile(Path)`: Lädt `.mkoro` via `GameSession.load()` und gibt den Endzustand zurück. `applyRoll`, `greedyBuy`, `buildSupply` in `GameSimulator` auf package-private geändert.

**N4c — `LabelingWindow.java`:** Neues JFrame in `gui.newui`. Layout: Oben — Spieleranzahl, Züge-Bereich, "Generieren"-Button, "Aus Datei laden"-Button. Mitte — Side-by-Side `SnapshotCard`s + drei unabhängige Slider (keine Tick-Nummern, nur Endpoint-Labels: "Frühphase ←→ Nicht Frühphase" etc.) für Early/Mid/Late. Unten — "Nächster Snapshot" (speichert aktuelles Label, generiert nächsten), "Labels exportieren" (schreibt `phase_labels.json` via Gson). Label-Format: `[{players:[{name,coins,gps,cards}], labels:{early,mid,late}}]`. Erreichbar via neues "Werkzeuge"-Menü in `MainWindow`.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## N2+N3: Bahnhof-Würfelwahl im Assistenten + wirtschaftsbasierte Phasenerkennung

**N2 — `optimalDiceCount(gs, pi)`:** Neuer public wrapper in `ProbabilityCalc`. Vergleicht `weightedRollEV(1d6)` vs `weightedRollEV(2d6)` mit aktuellem Portfolio (kein Kandidat). Gibt 1 oder 2 zurück. Im Assistenten: wenn Bahnhof besessen → neuer Hint "🎲 1W6 optimal — Portfolio aktiviert hauptsächlich auf 1–6" (oder 2W6). Strings: `assistantDiceHint1d6()` / `assistantDiceHint2d6()`.

**N3 — `AssistantConfig.java`:** Neue package-private Klasse in `gui.newui`. Zentralisiert alle Schwellwerte und Gewichtsarrays — kein Magic-Number-Streuer mehr in `rebuildAssistantPanel`. Konstanten: `EARLY_AVG_EV_THRESHOLD`, `EARLY_SAVE_ROUNDS`, `EKZ_COST`, `LATE_GP_THRESHOLD`, Pressure-Modifier-Werte, drei Gewichtsarrays (EARLY/MID/LATE). Methode `weightsForPhase(String)` gibt mutable Clone zurück.

**N3 — Wirtschaftsbasierte Phasenerkennung** ersetzt einfachen GP-Zähler-Check:
- **Frühphase**: `avgPortfolioEV < 1.2` UND EKZ nicht innerhalb 2 Runden erreichbar (`coins + 2×ownEv < 10`)
- **Endspiel**: `max(eigene GPs, maxOppGPs) >= 3`
- **Mittelspiel**: alles andere

**N3 — Rückstand-Modifier**: `minTurnsToWin` des gefährlichsten Gegners berechnet als `(22 - oppCoins) / oppEv` (Worst-Case 4. GP = Funkturm 22 Münzen). Notfall (≤3 Züge): GP-Rush +0.5, Aggro +0.3. Druck (≤6 Züge): GP-Rush +0.2, Aggro +0.1. Modifier via `AssistantConfig`-Konstanten.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## Synergy-Lookahead im Ranking + P(0)-Metrik auf vollständige Runde umgestellt

**Synergy-Lookahead:** `ProbabilityCalc.computeSynergyNote(gs, pi, card, candidates, n)` — neue package-private Methode. Berechnet für jede Karte im Ranking die beste Folgekarte (Partner), die ihren Wert am meisten steigern würde. Methode:

1. Erstellt `PlayerStats` als ob der Spieler `card` bereits besitzt (via `buildStatsWithCard`)
2. Für jede Nicht-Landmark-Karte S im Pool: erstellt `PlayerStats` mit card + S (`buildStatsWithCards`) und berechnet `contextualCardEvPerRound(card, statsWithS)` − Baseline
3. Für grün/store-Karten (Bäckerei, Mini-Markt): testet zusätzlich Einkaufszentrum via `buildStatsWithEkz`
4. Gibt `Strings.synergyNote(partnerName, gain)` zurück wenn Gewinn ≥ 0.05¢/Runde

Ergebnis: `entry.notes` im Ranking-Eintrag enthält z.B. "Gut mit: Bauernhof (+0.30¢/Runde)". Hilfsmethoden `applyToStats`, `buildStatsWithCard`, `buildStatsWithCards`, `buildStatsWithEkz` im selben `ProbabilityCalc`. Keine `GameState.copy()`-Aufrufe nötig → allokationsfrei.

Wird in `rankPurchasableProjects` und `rankAllProjects` aufgerufen. Bürohaus-Hinweis und Synergy-Note werden mit `"  |  "` kombiniert wenn beide vorhanden.

**P(0)-Metrik auf Rundenbasis:** `probNoIncomeRound` ersetzt `probNoIncomeOwnTurn` in Rankingtabelle, Kartendetail-Panel und `computeMetricRankPct`. Berechnet `P(0 Münzen über komplette Runde) = P(0 eigener Zug) × Π P(0 je Gegner-Zug)`. Dies ist konsistent mit dem "Sicherheitsstrategie"-Profil im Game Assistant, das bereits `probNoIncomeRound` verwendete. Beschreibungstexte in `Strings.legendP0Desc()` und `Strings.colTipP0()` aktualisiert.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## MC-Policy: ROI-basiertes Scoring in GameSimulator

**Problem:** `greedyBuy` benutzte `contextualEvPerRound / cost` als Kaufentscheidung. Das ignoriert die zeitliche Diskontierung — ein teurer 5-Münzen-Return-Karte sah gleich aus wie 5 billige 1-Münzen-Karten.

**Lösung:** Neues Scoring: `roi = contextualEvPerRound × ROI_GEOMETRIC_SUM − cost`, wobei `ROI_GEOMETRIC_SUM = γ × (1 − γ^T) / (1 − γ)` mit γ = 0.95, T = 10 (= 7.72, vorberechnet als statische Konstante). Das entspricht der ROI-Formel des analytischen Rankings. Die Simulation spielt nun dieselbe Strategie, die der Spieler im Ranking sieht → realistischere Win-Raten.

Performance: 40ms für 1000 Sims (unverändert), da `contextualCardEvPerRound` allokationsfrei bleibt.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## U3: Trigger-Modus-Anzeige in Kartendetails

`TriggerModePanel` — neues inneres `JPanel` in `MainWindow`, in die `nameRow` nach dem Farb-Tag eingefügt. Zeichnet programmatisch mit `Graphics2D`:
- **Blau** — 3 blaue Kreise: Karte triggert bei jedem Spieler-Zug
- **Grün** — 1 grüner Kreis: nur eigener Zug
- **Rot** — 1 roter Kreis mit Diagonalstrich: nur Gegner-Züge
- **Lila** — 1 lila Kreis + Diamant: eigener Zug, einmalig pro Runde
- **Gelb** — kein Indikator (Großprojekte werden gebaut, nicht getriggert)

`populateCenter` setzt `topCardTrigger.setCardColor(p.getColor())`; `clearCenter` setzt `null`.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## M5: "Warten/Sparen" als synthetischer RankEntry im "Alle"-Tab

**Problem:** Das Ranking zeigte nie die Option, Münzen für eine bessere Karte zu sparen.

**Lösung:**
- `RankEntry.WAIT_SENTINEL` — statisches Sentinel-`Project`-Objekt mit `id="_wait_"`. `RankEntry.isWaitEntry()` erkennt es.
- `addWaitEntryIfUseful(results, gs, playerIndex, opts)` — neue private Methode in `ProbabilityCalc.rankAllProjects`. Findet die beste nicht-erschwingliche Karte, berechnet `turnsToSave = coinsNeeded / currentEvPerRound`, und ROI: `ROI(warten) = ROI(beste_nächste) − turnsToSave × currentEvPerRound`. Nur eingefügt wenn unerschwingliche Karten vorhanden.
- `Strings.waitLabel()` — "≡ Sparen" / "≡ Save". `Strings.waitEntryNotes(card, turns)` — "Spare auf: [Karte] (~X.X Züge)".
- **UI**: `fillRankTableModel` überspringt den Sentinel in Erschwinglich/Nicht-erschwinglich-Tab; im "Alle"-Tab erscheint er als "≡ Sparen"-Zeile (unaffordable, kursiv/grau per DimRenderer). Kost-Spalte zeigt `NaN` (leer). Row-click zeigt Name + Notes im Center-Panel.
- **Assistent**: GP-Rush-Filter ergänzt mit `!e.isWaitEntry()` Guard.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## M4: Per-Landmark-Gewichte in WinProbabilityCalc

**Problem:** `LANDMARK_WEIGHT = 2.0` war für alle 4 Großprojekte identisch, obwohl ihre EV-Beiträge stark variieren.

**Kalibrierung** (mid-game Portfolio, 15 verbleibende Züge):
| Landmark | evPerRound-Delta | Neues Gewicht |
|---|---|---|
| Bahnhof (4¢) | +0.5/Runde | 1.5 |
| Einkaufszentrum (10¢) | +1.0/Runde | 3.0 |
| Freizeitpark (16¢) | +0.3/Runde | 1.5 |
| Funkturm (22¢) | +1.1/Runde | 4.0 (M2-Fix) |

**Lösung:** `LANDMARK_WEIGHTS` Map in `WinProbabilityCalc`; `LANDMARK_WEIGHT_DEFAULT = 2.0` als Fallback für zukünftige Expansion-Landmarks. `computeScores` nutzt `LANDMARK_WEIGHTS.getOrDefault(id, DEFAULT)`.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

---

## M3: Dynamischer REMAINING_TURNS_ESTIMATE in WinProbabilityCalc

**Problem:** `REMAINING_TURNS_ESTIMATE = 12.0` war eine statische Konstante. Im Frühspiel (viele Züge übrig) wurde der EV-Term unterschätzt, im Endspiel (Gegner hat 3 GPs) dramatisch überschätzt.

**Lösung:**
- `RankingOptions.turnsElapsed` — neues optionales Feld (Default 0 = Fallback auf statischen Wert).
- `WinProbabilityCalc.computeScores(GameState, int turnsElapsed)` — dynamische Schätzung:
  `remainingTurns = max(3, 25 − turnsElapsed / n)`, wobei `TOTAL_EXPECTED_TURNS = 25`.
- `WinProbabilityCalc.estimateWinProbDelta` — nimmt jetzt `turnsElapsed`-Overload.
- `ProbabilityCalc.rankAllProjects` / `rankPurchasableProjects` — leiten `opts.turnsElapsed` an `estimateWinProbDelta` weiter.
- `MainWindow.refreshAll()` und `refreshAfterRollChange()` setzen `rankOpts.turnsElapsed = session.getEffectiveTurnCount()` vor jedem Ranking-Aufruf.
- Rückwärtskompatibilität: `turnsElapsed = 0` → REMAINING_TURNS_FALLBACK = 12.0 wie zuvor.

**Tests:** 224 bestanden, 0 fehlgeschlagen.

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
