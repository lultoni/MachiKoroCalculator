# PLAN.md — MachiKoroCalculator Active Backlog

Open items only. For history see `CHANGELOG.md`, for math see `ARCHITECTURE.md`.

Progress key: `[ ]` open · `[~]` in progress · `[x]` done

---

## Bugs

### B1 · ~~Würfelaktivierung > 6 zeigt leeren Würfel~~ ✓ (behoben)

### B2 · ~~Rechtes Panel zu schmal — Reload-Button und Status-Text abgeschnitten~~ ✓ (behoben)

### B3 · ~~"coins" im Würfelergebnis-Preview nicht lokalisiert~~ ✓ (behoben)

---

## Code-Qualität

### C1 · ~~Metrik-Färbung: gemeinsame Basisklasse~~ ✓ (behoben)

### C2 · ~~Language Deep Clean — UX-Lesbarkeit des gesamten UI~~ ✓ (behoben)

### C3 · ~~Codeduplizierungen und Dead Code entfernen~~ ✓ (behoben)

---

### C4 · File Split Priority 2 (Low, deferred)

`MainWindow` ist groß. Sinnvolle Aufteilung wenn ein UI-Test-Layer existiert:
- `UIDataModel` (~50 Zeilen): hält `session`, `rankOpts`, `lastRanking`, `showWinProb`
- `RankingUIRenderer` (~100 Zeilen): `rebuildTable`, `populateCenter`, `clearCenter`, `buildNote`
- `GameController` (dünn): Turn-Anwendung, Undo, Snapshot, Save/Load-Dispatch

---

## UI-Verbesserungen

### U1 · Rechtes Panel: umbenennen + Tabs (Medium)

**Aktuell:** "Alle erschwinglichen Karten" → veraltet.
**Neu:** "Verfügbare Karten" (DE) / "Available Cards" (EN)

**Tab-Struktur:**
| Tab | Inhalt |
|-----|--------|
| Erschwinglich / Affordable | Wie bisher — nur kaufbare Karten, sortiert nach ROI |
| Nicht erschwinglich / Not Affordable | Karten die der aktive Spieler noch nicht kaufen kann (Planung / Sparvorschau) |
| Alle / All | Beide Gruppen kombiniert; nicht erschwingliche Karten ausgegraut/kursiv |

Buttons (Win Prob, Deep Analysis, MC Spinner etc.) bleiben unten und gelten für alle Tabs.

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

### N1 · Game Assistant (Medium)

Deterministischer, regelbasierter Spielassistent. **Keine generative KI.** Wertet alle Metriken aus und gibt pro Strategie eine Empfehlung mit 2–3 Sätzen Begründung aus.

**Strategieprofile:**

| Profil | Kriterium | Kurzbeschreibung |
|--------|-----------|-----------------|
| Bestes Investment | höchster ROI | Standard-Empfehlung — maximiert Gewinn pro eingesetzter Münze über 10 Runden |
| Maximaler Ertrag | höchstes EV/round | Höchster erwarteter Münzgewinn pro Runde, unabhängig von Kosten |
| Sicherheitsstrategie | niedrigstes P(0) | Karte die am seltensten leer ausgeht — gut bei knapper Kasse oder starken Gegnern |
| Niedrige Varianz | niedrigste Varianz | Stabile Auszahlung ohne Ausreißer |
| Sparsam | günstigste Karte | Schnellster Kauf um sofort ins Spiel zurückzukehren |
| Gewinnwahrscheinlichkeit | höchstes Win Prob Δ | Verbessert die eigene Siegchance am stärksten (nur wenn Win Prob Δ berechnet) |
| Aggressiv | max. Gegnerschaden | Bevorzugt Rot/Lila (Stadion, Bürohaus) die Gegner Münzen kosten |
| GP Rush | nächste GP-Schwelle | Strategie die am schnellsten das nächste Großprojekt finanzierbar macht |

**Ausgabe-Beispiel:**
> **Bestes Investment:** Kaufe Café (2¢). ROI 1.84 — du bekommst die Investition in ~5.4 Runden zurück. Besonders effektiv, weil du noch kein Café besitzt.

**UI:** Vierter Tab im rechten Panel oder eigener Bereich unter Kartendetails. Dropdown / Buttons zur Strategiewahl; Empfehlung aktualisiert sich automatisch bei jedem `refreshAll()`.

**Langfristig:** Die Strategieprofile können als Entscheidungslogik für simulierte Gegner in `GameSimulator` genutzt werden (→ Future: Opponent Modeling).

---

## Akzeptierte Näherungen (kein Handlungsbedarf)

| # | Thema | Erklärung |
|---|-------|-----------|
| A1 | Bürohaus — Optimal-Swap-Annahme | `bürohausSwapEV` nimmt an, der Spieler tauscht immer optimal. Swap ist eigentlich optional. Akzeptable Heuristik. Siehe `ARCHITECTURE.md §2.8`. |
| A2 | GameSimulator — Statische EV/Cost-Tabelle | `STATIC_EV_PER_COST` ignoriert Synergien (z.B. viele Food-Karten + Markthalle). Akzeptabel für Gewinnraten-Schätzung. Siehe `ARCHITECTURE.md §4.2`. |
| A3 | `evPerRound` — Einschritt-Münzprojektion | Projektion via `estimateUncappedOwnTurnEV` ist kein vollständiges Mehrrundenmodell. Akzeptierte Näherung. Siehe `ARCHITECTURE.md §2.4b`. |

---

## Future Features (nicht priorisiert)

- **Erweiterungskarten** — Hafen/Millionärsreihe. Architektur ist bereit (JSON-getrieben, `get_I` dispatcht per ID).
- **Gegner-Archetypen** — Simulierte Spieler folgen aktuell einer greedy Policy. Verschiedene Archetypen würden realistischere Gewinnraten liefern. Basis: Strategieprofile aus N1.
