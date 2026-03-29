# MachiKoroCalculator

Entscheidungshilfe für das Brettspiel Machi Koro (Grundspiel). Berechnet auf Basis des aktuellen Spielstands (Münzen, Karten, Spielerzahl) den mathematisch optimalen Kauf mit Erwartungswert- und Wahrscheinlichkeitsanalyse.

## Features

- Turn-by-turn Spielverfolgung mit vollständigem Undo-Verlauf
- Snapshot-Modus: Spielstand jederzeit eintragen oder bearbeiten, dann turn-by-turn weiterführen
- Erwartungswertberechnung (EV/round), ROI über 10 Runden, Risikomaß P(0), Varianz
- Alle Großprojekte (Landmarks) als Kaufoptionen neben regulären Karten
- Bahnhof: 1W6 vs. 2W6-Auswahl; Einkaufszentrum-Boni; Freizeitpark-Doppelwurf; Funkturm-Neuwerfen; Bürohaus-Tausch-EV
- "Doubles?"-Checkbox wenn Spieler Bahnhof + Freizeitpark besitzt; Bonuszug-Logik in `GameSession`
- Kaufliste und Ranking basieren auf Post-Roll-Münzen (korrekte Turnreihenfolge: Würfeln → Einkommen/Zahlen → Kaufen)
- Optionale Win-Prob-Δ-Spalte: analytisch (Softmax) oder Monte Carlo
- **Deep Analysis**: konfigurierbares MC (100–10 000 Simulationen), unabhängig vom Win-Prob-Toggle, läuft per `SwingWorker` off-EDT
- DE/EN lokalisiert: Sprachwechsel in Setup (Radiobuttons) und Hauptfenster (Menüleiste)

## Dokumentation

| Datei | Inhalt |
|-------|--------|
| `CLAUDE.md` | Architektur, Coding-Konventionen, Workflow |
| `ARCHITECTURE.md` | Formeln, Kartenregeln, Designentscheidungen |
| `CHANGELOG.md` | Implementierungsgeschichte |
| `PLAN.md` | Offene Bugs, geplante Verbesserungen |

## Voraussetzungen & Build

Java 17+, `gson-2.11.0.jar` (im Repo enthalten).

```bash
# Kompilieren
javac -cp "src:gson-2.11.0.jar" -d out $(find src -name "*.java")

# Starten
java -cp "out:src:gson-2.11.0.jar" logic.Main

# Tests
java -cp "out:src:gson-2.11.0.jar" Tests.RuntimeTester
```

## Projektstruktur

```
src/
  logic/                         Einstiegspunkt (Main.java)
  logic/probability/             Wahrscheinlichkeits-Engine + Datenmodell
    GameState.java               Veränderbarer Spielstand (Player[] + Kartenpool)
    GameStateBuilder.java        Fluent Builder für GameState
    GameSession.java             Turn-Tracker: Undo, Freizeitpark-Bonuszüge, Snapshot
    GameSessionPersistence.java  JSON-Speichern/Laden (von GameSession getrennt)
    TurnRecord.java              Unveränderlicher Zug-Record (Wurf, Kauf, isDoubles)
    ProbabilityCalc.java         Statische Math-Engine (EV, ROI, Varianz, Ranking, MC)
    BürohausLogic.java           Bürohaus-Tausch-Helpers (aus ProbabilityCalc extrahiert)
    GameSimulator.java           Stateless Monte-Carlo-Simulator (greedy Rollout)
    ProjectLoader.java           JSON-Loader mit statischem Cache
    RankEntry.java               Ergebnis-POJO für Ranking
    RankingOptions.java          Optionen (Horizont, Diskontfaktor, MC-Simulations)
  gui/newui/                     Swing-UI
    SetupWindow.java             Neues Spiel (Spielerzahl + Namen + Sprache)
    MainWindow.java              Hauptfenster (3-Spalten-Layout)
    SnapshotDialog.java          Snapshot-Editor
    Strings.java                 Zentrale i18n-Registry (DE/EN)
    BoundedSpinner.java          JSpinner-Subklasse: +/− deaktiviert an Modellgrenzen
    DiceFacePanel.java           Programmatisch gezeichnetes Würfelgesicht
    DiceSelectorPanel.java       Klickbarer Würfel-Strip für die Wurfeingabe
    TurnEntryPanel.java          Panel für einen einzelnen Verlaufseintrag
  resources/jsons/               projects.json — alle 19 Grundspiel-Karten
  Tests/                         RuntimeTester — 228 Tests + Benchmarks
```

## UI-Überblick

**Links — Current Turn Tracker**
Würfelauswahl (1W6 ohne Bahnhof, 2W6 mit). "Doubles?"-Checkbox bei Bahnhof+Freizeitpark. Kaufdropdown zeigt nur Karten, die nach dem Wurf erschwinglich sind. Verlauf mit Würfelgesichtern, Münzdeltas pro Spieler und Kaufinfos.

**Mitte — Card Details**
Top-empfohlene Karte mit EV/round, ROI, Risikomaß, optionalem Win-Prob-Δ und Würfelergebnis-Preview pro Spieler. Aktuelle Basisgewinnwahrscheinlichkeit immer sichtbar.

**Rechts — Verfügbare Karten**
Vollständige sortierbare Rankingtabelle. Farbkodierung: grün = stark positiv, rot = stark negativ. Großprojekte enthalten. Deep-Analysis-Toggle für MC.

## Karten (Grundspiel)

Alle 19 Karten sind in `src/resources/jsons/projects.json` definiert.

| Farbe | Löst aus | Beispiele |
|-------|---------|-----------|
| Blau | Immer — alle Spieler | Weizenfeld, Bauernhof, Bergwerk |
| Grün | Eigener Zug | Bäckerei, Molkerei, Möbelfabrik |
| Rot | Fremde Züge | Café, Familienrestaurant |
| Lila | Eigener Zug, einmalig | Stadion, Fernsehsender, Bürohaus |
| Gelb | Großprojekte (GP) | Bahnhof, Einkaufszentrum, … |
