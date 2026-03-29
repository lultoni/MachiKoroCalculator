package gui.newui;

/**
 * Central registry of all user-visible strings, supporting German (DE) and English (EN).
 *
 * <p>Usage: access strings as {@code Strings.CONFIRM_TURN} etc. Call {@link #setLocale(Locale)}
 * to switch language. The active locale is stored statically; the UI must be rebuilt after
 * switching (see {@code MainWindow.applyLocale()}).
 */
public final class Strings {

    public enum Locale { DE, EN }

    private static Locale locale = Locale.DE;

    private Strings() {}

    public static Locale getLocale() { return locale; }

    public static void setLocale(Locale l) { locale = l; }

    public static boolean isDE() { return locale == Locale.DE; }

    // ── Generic helpers ───────────────────────────────────────────────────────

    private static String s(String de, String en) {
        return locale == Locale.DE ? de : en;
    }

    // =========================================================================
    // SetupWindow
    // =========================================================================

    public static String setupWindowTitle()   { return s("Machi Koro Rechner — Neues Spiel", "Machi Koro Calculator — New Game"); }
    public static String setupHeading()       { return s("Machi Koro Rechner", "Machi Koro Calculator"); }
    public static String setupNumPlayers()    { return s("Spieleranzahl:", "Number of players:"); }
    public static String setupPlayerName(int n) { return s("Spieler " + n + " Name:", "Player " + n + " name:"); }
    public static String setupDefaultName(int n){ return s("Spieler " + n, "Player " + n); }
    public static String setupStartBtn()      { return s("Spiel starten", "Start Game"); }

    // =========================================================================
    // MainWindow — window / panel titles
    // =========================================================================

    public static String mainWindowTitle()    { return s("Machi Koro Rechner", "Machi Koro Calculator"); }
    public static String leftPanelTitle()     { return s("Aktueller Zug-Tracker", "Current Turn Tracker"); }
    public static String centerPanelTitle()   { return s("Kartendetails", "Card Details"); }
    public static String rightPanelTitle()    { return s("Verfügbare Karten", "Available Cards"); }

    // =========================================================================
    // MainWindow — left panel labels & buttons
    // =========================================================================

    public static String diceRollLabel()      { return s("Würfelwurf:", "Dice roll:"); }
    public static String doublesCheckbox()    { return s("Pasch!", "Doubles!"); }
    public static String doublesTooltip()     {
        return s("<html>Hake an, wenn du einen Pasch gewürfelt hast (beide Würfel gleich).<br>" +
                 "Freizeitpark gibt dir einen zweiten Zug!</html>",
                 "<html>Check this if you rolled doubles (both dice show the same face).<br>" +
                 "Freizeitpark grants you a bonus second turn!</html>");
    }
    public static String rollOutcomeLabel()   { return s("Würfelergebnis:", "Roll outcome:"); }
    public static String purchaseLabel()      { return s("Kauf (optional):", "Purchase (optional):"); }
    public static String nothingOption()      { return s("— nichts —", "— nothing —"); }
    public static String confirmTurnBtn()     { return s("Zug bestätigen", "Confirm Turn"); }
    public static String undoBtn()            { return s("Letzten Zug rückgängig", "Undo Last Turn"); }
    public static String snapshotBtn()        { return s("Schnappschuss…", "Enter Snapshot…"); }
    public static String saveBtn()            { return s("Spiel speichern…", "Save Game…"); }
    public static String loadBtn()            { return s("Spiel laden…", "Load Game…"); }
    public static String historyLabel()       { return s("Zugverlauf:", "Turn history:"); }
    public static String coinsUnit()          { return s("Münzen", "coins"); }
    public static String coinSingular()       { return s("Münze", "coin"); }

    // =========================================================================
    // MainWindow — center panel (Card Details)
    // =========================================================================

    public static String evRoundLabel()       { return s("EV / Runde:", "EV / round:"); }
    public static String evRoundTooltip()     {
        return s("Erwartete Münzen pro vollständiger Spielrunde (eigener Zug + Gegner-Züge). Höher = bessere Einkommensquelle.",
                 "Expected coins earned per full game round (own turn + opponent turns). Higher = better income engine.");
    }
    public static String roiLabel()           { return s("ROI (10 Runden):", "ROI (10 turns):"); }
    public static String roiTooltip()         {
        return s("Abgezinster Return on Investment über 10 Runden minus Kaufpreis. Positiv = lohnender Kauf.",
                 "Discounted return on investment over 10 rounds minus purchase cost. Positive = profitable buy.");
    }
    public static String p0Label()            { return s("P(0 Einkommen):", "P(0 income):"); }
    public static String p0Tooltip()          {
        return s("Wahrscheinlichkeit, im eigenen Zug null Münzen zu erhalten. Niedriger = zuverlässigeres Einkommen.",
                 "Probability of earning zero coins on your own turn. Lower = more reliable income.");
    }
    public static String varianceLabel()      { return s("Varianz:", "Variance:"); }
    public static String varianceTooltip()    {
        return s("Statistische Streuung des Einkommens pro Zug. Niedriger = berechenbarer; höher = Boom-oder-Pleite.",
                 "Statistical spread of per-turn income. Lower = more predictable; higher = boom-or-bust.");
    }
    public static String winProbLabel()       { return s("Gewinnw.-Δ:", "Win Prob Δ:"); }
    public static String winProbTooltip()     {
        return s("Änderung der geschätzten Gewinnwahrscheinlichkeit durch den Kauf dieser Karte. Erfordert Gewinnwahrscheinlichkeitsanalyse.",
                 "Change in estimated win probability from buying this card. Requires win-prob analysis.");
    }
    public static String metricLegendToggleOpen()  { return s("▼ Metriken-Legende", "▼ Metric legend"); }
    public static String metricLegendToggleClosed(){ return s("▶ Metriken-Legende", "▶ Metric legend"); }

    public static String legendEVAbbr()       { return s("EV / Runde", "EV / round"); }
    public static String legendEVDesc()       {
        return s("Erwartete Münzen pro vollständiger Runde (eigener + Gegner-Züge)",
                 "Expected coins per full game round (own + opponents' turns)");
    }
    public static String legendROIAbbr()      { return "ROI"; }
    public static String legendROIDesc()      {
        return s("Return on Investment über 10 Runden minus Kosten. Positiv = lohnend.",
                 "Return on investment over 10 rounds minus cost. Positive = profitable.");
    }
    public static String legendP0Abbr()       { return "P(0)"; }
    public static String legendP0Desc()       {
        return s("Wahrscheinlichkeit, 0 Münzen im eigenen Zug zu erhalten. Niedriger = zuverlässig.",
                 "Probability of earning 0 coins on your own turn. Lower = reliable.");
    }
    public static String legendVarAbbr()      { return s("Varianz", "Var"); }
    public static String legendVarDesc()      {
        return s("Varianz des Einkommens pro Zug. Höher = Boom-oder-Pleite-Risiko.",
                 "Variance of per-turn income. Higher = boom-or-bust risk.");
    }
    public static String legendWinAbbr()      { return s("Gew.-Δ", "Win Δ"); }
    public static String legendWinDesc()      {
        return s("Änderung der geschätzten Gewinnwahrscheinlichkeit durch diesen Kauf.",
                 "Change in estimated win probability from buying this card.");
    }

    public static String baselineWinProbLabel(){ return s("Gewinnwahrscheinlichkeit: —", "Win prob: —"); }
    public static String baselineWinProbFmt(double pct) {
        return s(String.format("Gewinnwahrsch.: %.1f%%", pct), String.format("Win prob: %.1f%%", pct));
    }
    public static String winProbSoftmaxTooltip() {
        return s("<html>Analytische Softmax-Schätzung: Die Gewinnwahrscheinlichkeit jedes Spielers ist proportional<br>" +
                 "zu e^(EV) / Summe(e^(EV)). Höherer eigener EV und niedrigerer Gegner-EV → höhere Wahrscheinlichkeit.</html>",
                 "<html>Analytical softmax estimate: each player's win probability is proportional<br>" +
                 "to e^(EV score) / sum(e^(EV scores)). Higher own EV and lower opponent EV → higher probability.</html>");
    }
    public static String winProbSoftmaxExplain() {
        return s("<html><small style='color:#666'>Softmax der relativen EV-Werte — für Details hovern</small></html>",
                 "<html><small style='color:#666'>Softmax of relative EV scores — hover for details</small></html>");
    }

    // =========================================================================
    // MainWindow — right panel (table / buttons)
    // =========================================================================

    public static String colCard()            { return s("Karte", "Card"); }
    public static String colCost()            { return s("Kosten", "Cost"); }
    public static String colEV()              { return s("EV/Rd.", "EV/rnd"); }
    public static String colROI()             { return "ROI"; }
    public static String colP0()              { return "P(0)"; }
    public static String colVar()             { return s("Var.", "Var"); }
    public static String colWinDelta()        { return s("Gew.-Δ", "Win Δ"); }

    public static String colTipCard()         { return s("Kartenname", "Card name"); }
    public static String colTipCost()         { return s("Kaufpreis (Münzen)", "Purchase cost (coins)"); }
    public static String colTipEV()           {
        return s("EV/Runde: erwartete Münzen pro vollständiger Runde (eigener Zug + Gegner-Züge)",
                 "EV/round: expected coins earned per full game round (own turn + opponent turns)");
    }
    public static String colTipROI()          {
        return s("ROI: abgezinster Return on Investment über 10 Runden minus Kaufpreis. Positiv = lohnend.",
                 "ROI: discounted return on investment over 10 rounds minus purchase cost. Positive = profitable.");
    }
    public static String colTipP0()           {
        return s("P(0): Wahrscheinlichkeit, im eigenen Zug null Münzen zu erhalten. Niedriger = zuverlässiger.",
                 "P(0): probability of earning zero coins on your own turn. Lower = more reliable income.");
    }
    public static String colTipVar()          {
        return s("Var: statistische Varianz des Einkommens. Höher = Boom-oder-Pleite.",
                 "Var: statistical variance of per-turn income. Higher = boom-or-bust.");
    }
    public static String colTipWinDelta()     {
        return s("Gew.-Δ: geschätzte Änderung der Gewinnwahrscheinlichkeit durch diesen Kauf (analytisch oder MC).",
                 "Win Δ: estimated change in win probability from buying this card (analytical or MC).");
    }

    public static String showWinProbBtn()     { return s("Gewinnw.-Δ anzeigen", "Show Win Prob Δ"); }
    public static String hideWinProbBtn()     { return s("Gewinnw.-Δ verbergen", "Hide Win Prob Δ"); }
    public static String tabAffordable()      { return s("Erschwinglich", "Affordable"); }
    public static String tabNotAffordable()   { return s("Nicht erschwinglich", "Not Affordable"); }
    public static String tabAll()             { return s("Alle", "All"); }
    public static String noAffordableCardsTab() { return s("Keine erschwinglichen Karten — sparen!", "No affordable cards — save up!"); }
    public static String noUnaffordableCards()  { return s("Alle Karten sind erschwinglich!", "All cards are affordable!"); }
    public static String deepAnalysisBtn()    { return s("Tiefenanalyse (MC)", "Deep Analysis (MC)"); }
    public static String deepAnalysisBtnOn()  { return s("Tiefenanalyse AN (MC)", "Deep Analysis ON (MC)"); }
    public static String deepAnalysisTooltip(){ return s("Monte-Carlo-Simulationen pro Karte für genaues Gewinnwahrscheinlichkeits-Delta.",
                                                          "Run Monte Carlo simulations per card for accurate win-probability delta."); }
    public static String mcSimNLabel()        { return "N:"; }
    public static String mcSimTooltip()       {
        return s("Anzahl Monte-Carlo-Simulationen (100–10000). Mehr = genauer, aber langsamer.",
                 "Number of Monte Carlo simulations (100–10000). More = accurate but slower.");
    }
    public static String mcReloadTooltip()    {
        return s("Monte-Carlo-Analyse mit aktuellen Einstellungen neu ausführen.",
                 "Re-run Monte Carlo analysis with the current settings.");
    }
    public static String mcRunning()          { return s("MC läuft…", "Running MC…"); }
    public static String mcDone(int n)        { return s("MC fertig (" + n + " Sim.)", "MC done (" + n + " sims)"); }

    // =========================================================================
    // MainWindow — dynamic status strings
    // =========================================================================

    public static String playerTurn(String name)  { return name + s(" ist dran", "'s turn"); }
    public static String bonusTurn(String name) {
        return "<html><b style='color:#7030A0'>" + name + s(" — BONUSZUG (Freizeitpark)!", " — BONUS TURN (Freizeitpark)!") + "</b></html>";
    }
    public static String coinsDisplay(int n)  { return n + " " + (n == 1 ? coinSingular() : coinsUnit()); }
    public static String coinsAfterNeutral(int n){ return "→ " + coinsDisplay(n) + " (±0)"; }
    public static String coinsAfterDelta(int post, int delta) {
        String sign = delta > 0 ? "+" : "";
        return "→ " + coinsDisplay(post) + " (" + sign + delta + ")";
    }
    public static String noAffordableCards()  { return s("Keine erschwinglichen Karten — sparen!", "No affordable cards — save up!"); }
    public static String gameOver(String winner){ return winner + s(" gewinnt!", " wins!"); }
    public static String gameOverDesc()       { return s("<html><i>Alle 4 Großprojekte gebaut!</i></html>",
                                                          "<html><i>All 4 " + grossProjekt() + "s built!</i></html>"); }
    public static String gameOverNote()       { return s("<html><i>Spiel vorbei. Rückgängig drücken oder Fenster schließen.</i></html>",
                                                          "<html><i>Game over. Use Undo to continue or close the window.</i></html>"); }
    public static String gameOverStatus()     { return s("Spiel vorbei!", "Game over!"); }
    public static String gpTag()              { return "[GP]"; }
    public static String grossProjekt()       { return s("Großprojekt", "Landmark"); }

    // =========================================================================
    // MainWindow — file dialogs & errors
    // =========================================================================

    public static String saveDialogTitle()    { return s("Spiel speichern", "Save Game"); }
    public static String saveFileFilter()     { return s("Machi Koro Speicherdateien (*.mkoro)", "Machi Koro save files (*.mkoro)"); }
    public static String saveErrorMsg(String e){ return s("Konnte nicht speichern: " + e, "Could not save: " + e); }
    public static String saveErrorTitle()     { return s("Speichern fehlgeschlagen", "Save Failed"); }
    public static String loadDialogTitle()    { return s("Spiel laden", "Load Game"); }
    public static String loadFileFilter()     { return s("Machi Koro Speicherdateien (*.mkoro)", "Machi Koro save files (*.mkoro)"); }
    public static String loadErrorMsg(String e){ return s("Konnte nicht laden: " + e, "Could not load: " + e); }
    public static String loadErrorTitle()     { return s("Laden fehlgeschlagen", "Load Failed"); }
    public static String invalidTurnTitle()   { return s("Ungültiger Zug", "Invalid Turn"); }
    public static String undoNothingMsg()     { return s("Nichts rückgängig zu machen.", "Nothing to undo."); }
    public static String undoTitle()          { return s("Rückgängig", "Undo"); }
    public static String undoFailedTitle()    { return s("Rückgängig fehlgeschlagen", "Undo failed"); }

    // =========================================================================
    // MainWindow — card cost / activation area
    // =========================================================================

    public static String costPrefix(int cost) {
        return s("Kosten: " + cost + " " + (cost == 1 ? coinSingular() : coinsUnit()),
                 "Cost: " + cost + " " + (cost == 1 ? coinSingular() : coinsUnit()));
    }

    // =========================================================================
    // TurnEntryPanel
    // =========================================================================

    public static String historyRolled()      { return s("würfelte", "rolled"); }
    public static String historyDoubles()     { return s(" PASCH!", " DOUBLES!"); }
    public static String historyBought(String cardName, boolean isGP, int cost) {
        String gp = isGP ? " " + gpTag() : "";
        return "→ " + s("gekauft: ", "bought ") + cardName + gp + " (−" + cost + "¢)";
    }

    // =========================================================================
    // SnapshotDialog
    // =========================================================================

    public static String snapshotDialogTitle(){ return s("Schnappschuss bearbeiten", "Edit Snapshot"); }
    public static String snapshotHint()       {
        return s("<html><small>Änderungen gelten ab diesem Punkt. Der Zugverlauf wird zurückgesetzt.</small></html>",
                 "<html><small>Changes take effect from this point. Turn history resets to empty.</small></html>");
    }
    public static String snapshotCoins()      { return s("Münzen:", "Coins:"); }
    public static String snapshotOwnedCards() { return s("Eigene Karten:", "Owned cards:"); }
    public static String snapshotApplyBtn()   { return s("Schnappschuss anwenden", "Apply Snapshot"); }
    public static String snapshotCancelBtn()  { return s("Abbrechen", "Cancel"); }
    public static String snapshotColorBlau()  { return s("Blau — alle Züge (Spinner = Anzahl)", "Blue — all turns (spinner = copies owned)"); }
    public static String snapshotColorRot()   { return s("Rot — fremde Züge (Spinner = Anzahl)", "Red — opponent turns (spinner = copies owned)"); }
    public static String snapshotColorGrün()  { return s("Grün — eigener Zug (Spinner = Anzahl)", "Green — own turn (spinner = copies owned)"); }
    public static String snapshotColorLila()  { return s("Lila — eigener Zug, einzigartig (Haken = besessen)", "Purple — own turn, unique (tick = owned)"); }
    public static String snapshotColorGelb()  { return s("Gelb — Großprojekte (Haken = gebaut)", "Yellow — Landmarks (tick = built)"); }
    public static String snapshotError(String e){ return s("Ungültiger Spielzustand: " + e, "Invalid game state: " + e); }
    public static String snapshotErrorTitle() { return s("Schnappschuss-Fehler", "Snapshot Error"); }

    // =========================================================================
    // Card color labels (used in center panel color tag)
    // =========================================================================

    public static String colorLabel(String colorId) {
        return switch (colorId) {
            case "blau"  -> s("Blau",  "Blue");
            case "rot"   -> s("Rot",   "Red");
            case "grün"  -> s("Grün",  "Green");
            case "lila"  -> s("Lila",  "Purple");
            case "gelb"  -> s("Gelb",  "Yellow");
            default      -> colorId;
        };
    }

    // =========================================================================
    // Menu bar
    // =========================================================================

    public static String menuLanguage()       { return s("Sprache", "Language"); }
    public static String menuLangDE()         { return "Deutsch"; }
    public static String menuLangEN()         { return "English"; }

    // =========================================================================
    // Build-note strings
    // =========================================================================

    public static String costNotRecouped(int turns) {
        return s("(Kosten werden in " + turns + " Zügen nicht zurückgespielt)",
                 "(cost may not be recouped in " + turns + " turns)");
    }
}
