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
| A1 | Bürohaus — step-aware Projektion | Blaues Einkommen wird jetzt schrittsweise pro Gegner-Position akkumuliert; Rundungsfehler durch integer-Projektion sind vernachlässigbar. |
| A2 | Bahnhof-Würfelwahl im Simulator | `GameSimulator.rollDice()` wählt 2d6 wenn der Spieler eine Karte mit Aktivierung ≥ 7 hat — Heuristik statt exakter EV-Berechnung. Akzeptabler Trade-off für Simulationsgeschwindigkeit. |

---

## Math-Audit (High — Priorität nach N1)

### M1 · ~~Tiefenanalyse der Berechnungsarchitektur — Näherungen entfernen~~ ✓ (behoben)

---

## Future Features (nicht priorisiert)

- **Erweiterungskarten** — Hafen/Millionärsreihe. Architektur ist bereit (JSON-getrieben, `get_I` dispatcht per ID).
- **Gegner-Archetypen** — Simulierte Spieler folgen aktuell einer greedy Policy. Verschiedene Archetypen würden realistischere Gewinnraten liefern. Basis: Strategieprofile aus N1.
