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
    public static String portfolioDeltaLabel()   { return s("Portfolio ΔEV:", "Portfolio ΔEV:"); }
    public static String portfolioDeltaTooltip() {
        return s(
            "Marginaler EV-Gewinn pro Runde durch Kauf dieser Karte: playerEvPerRound(Portfolio + Karte) − playerEvPerRound(Portfolio). " +
            "Erfasst Kreuz-Synergien: z.B. Bauernhof erhöht Molkerei's Wert, Bahnhof erhöht alle 7–12 Karten.",
            "Marginal EV gain per round from buying this card: playerEvPerRound(portfolio + card) − playerEvPerRound(portfolio). " +
            "Captures cross-card synergies: e.g. Bauernhof increases Molkerei's value, Bahnhof unlocks 7–12 cards."
        );
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
        return s("Wahrscheinlichkeit, 0 Münzen in der gesamten Runde zu erhalten. Niedriger = zuverlässig.",
                 "Probability of earning 0 coins across the full round. Lower = reliable.");
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
    public static String colPortfolioDelta()  { return s("Port.ΔEV", "Port.ΔEV"); }

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
        return s("P(0): Wahrscheinlichkeit, in der gesamten Runde (eigener + Gegner-Züge) null Münzen zu erhalten. Niedriger = zuverlässiger.",
                 "P(0): probability of earning zero coins across the full round (own turn + opponent turns). Lower = more reliable income.");
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
    public static String tabAssistant()       { return s("Assistent", "Assistant"); }
    public static String tabRollout()         { return s("Rollout", "Rollout"); }
    public static String rolloutRunBtn()      { return s("Rollout starten", "Run Rollout"); }
    public static String rolloutRunningMsg()  { return s("Rollout läuft...", "Running rollout..."); }
    public static String rolloutDepthLabel()  { return s("Tiefe:", "Depth:"); }
    public static String rolloutTopKLabel()   { return s("Top-K:", "Top-K:"); }
    public static String rolloutBestLabel()   { return s("Empfehlung: ", "Recommendation: "); }
    public static String rolloutWinProbLabel(){ return s("Win-Prob: ", "Win-Prob: "); }
    public static String rolloutNoResult()    { return s("Noch kein Rollout. Tiefe und Top-K wählen, dann starten.", "No rollout yet. Choose depth and top-K, then run."); }
    public static String noAffordableCardsTab() { return s("Keine erschwinglichen Karten — sparen!", "No affordable cards — save up!"); }
    public static String noUnaffordableCards()  { return s("Alle Karten sind erschwinglich!", "All cards are affordable!"); }

    // ---- Card Details: rank context ----
    /**
     * Shows the card's rank among affordable cards and all cards.
     * If rAffordable == 0, the card is not affordable.
     */
    public static String rankLabel(int rAffordable, int nAffordable, int rAll, int nAll) {
        String allPart = "#" + rAll + " / " + nAll + s(" gesamt", " total");
        if (rAffordable > 0) {
            return "#" + rAffordable + " / " + nAffordable + s(" erschwinglich  ·  ", " affordable  ·  ") + allPart;
        } else {
            return s("nicht erschwinglich  ·  ", "not affordable  ·  ") + allPart;
        }
    }


    public static String assistantProfileROI()      { return s("Bestes Investment", "Best Investment"); }
    public static String assistantProfileEV()       { return s("Maximaler Ertrag", "Max Income"); }
    public static String assistantProfileSafe()     { return s("Sicherheitsstrategie", "Safety Strategy"); }
    public static String assistantProfileLowVar()   { return s("Niedrige Varianz", "Low Variance"); }
    public static String assistantProfileCheap()    { return s("Sparsam", "Frugal"); }
    public static String assistantProfileWinProb()  { return s("Gewinnwahrscheinlichkeit", "Win Probability"); }
    public static String assistantProfileAggro()    { return s("Aggressiv", "Aggressive"); }
    public static String assistantProfileGPRush()   { return s("GP Rush", "GP Rush"); }

    // ---- Game Assistant explanations (profile, card name) ----
    public static String assistantExplainROI(String card, double roi) {
        return s("Kaufe <b>" + card + "</b> — höchster ROI (" + String.format("%.2f", roi) + "¢). "
                + "Maximiert den Münzgewinn pro eingesetzter Münze über 10 Runden.",
                 "Buy <b>" + card + "</b> — highest ROI (" + String.format("%.2f", roi) + "¢). "
                + "Maximises coin gain per coin spent over 10 turns.");
    }
    public static String assistantExplainEV(String card, double ev) {
        return s("Kaufe <b>" + card + "</b> — höchstes EV/Runde (+" + String.format("%.2f", ev) + "¢). "
                + "Liefert den größten erwarteten Münzzuwachs pro Runde unabhängig von Kosten.",
                 "Buy <b>" + card + "</b> — highest EV/round (+" + String.format("%.2f", ev) + "¢). "
                + "Provides the greatest expected coin gain per round regardless of cost.");
    }
    public static String assistantExplainSafe(String card, double p0) {
        return s("Kaufe <b>" + card + "</b> — niedrigstes P(0) (" + String.format("%.0f", p0 * 100) + "%). "
                + "Am seltensten leer ausgehen — gut bei knapper Kasse oder starken Gegnern.",
                 "Buy <b>" + card + "</b> — lowest P(0) (" + String.format("%.0f", p0 * 100) + "%). "
                + "Least likely to yield no income — good when cash is tight or opponents are strong.");
    }
    public static String assistantExplainLowVar(String card, double var) {
        return s("Kaufe <b>" + card + "</b> — niedrigste Varianz (" + String.format("%.2f", var) + "). "
                + "Stabile Auszahlung ohne große Ausreißer nach oben oder unten.",
                 "Buy <b>" + card + "</b> — lowest variance (" + String.format("%.2f", var) + "). "
                + "Consistent payout with no large swings in either direction.");
    }
    public static String assistantExplainCheap(String card, int cost) {
        return s("Kaufe <b>" + card + "</b> — günstigste Karte (" + cost + "¢). "
                + "Schnellster Weg zurück ins Spiel; Münzen sofort wieder investierbar.",
                 "Buy <b>" + card + "</b> — cheapest card (" + cost + "¢). "
                + "Fastest way back into action; coins immediately available for reinvestment.");
    }
    public static String assistantExplainWinProb(String card, double delta) {
        String sign = delta >= 0 ? "+" : "";
        return s("Kaufe <b>" + card + "</b> — bestes Win Prob Δ (" + sign + String.format("%.1f", delta * 100) + "%). "
                + "Verbessert deine Siegchance am stärksten.",
                 "Buy <b>" + card + "</b> — best Win Prob Δ (" + sign + String.format("%.1f", delta * 100) + "%). "
                + "Improves your win probability the most.");
    }
    public static String assistantExplainAggro(String card) {
        return s("Kaufe <b>" + card + "</b> — aggressivste Option. "
                + "Rot/Lila-Karten entziehen Gegnern Münzen und stören ihre Pläne.",
                 "Buy <b>" + card + "</b> — most aggressive option. "
                + "Red/purple cards drain opponent coins and disrupt their plans.");
    }
    public static String assistantExplainGPRush(String card, int cost, int coins) {
        int missing = Math.max(0, cost - coins);
        return s("Kaufe <b>" + card + "</b> — nächstes Großprojekt (" + cost + "¢). "
                + (missing == 0 ? "Jetzt erschwinglich!" : "Noch " + missing + "¢ fehlen."),
                 "Buy <b>" + card + "</b> — next landmark (" + cost + "¢). "
                + (missing == 0 ? "Affordable now!" : missing + "¢ still needed."));
    }
    public static String assistantNoAffordable() {
        return s("Keine erschwinglichen Karten für dieses Profil.", "No affordable cards match this profile.");
    }
    public static String assistantNoWinProb() {
        return s("Win Prob Δ nicht verfügbar — 'Gewinnw.-Δ anzeigen' aktivieren.", "Win Prob Δ not available — enable 'Show Win Prob Δ'.");
    }

    // ---- Game Assistant: tie-breaking ----
    /** Note shown when a tiebreaker was needed. {@code criterion} is the winning criterion label. */
    public static String assistantTiebreakerNote(String criterion) {
        return s("Gleichstand — entschieden per: " + criterion + ".",
                 "Tied — chosen by: " + criterion + ".");
    }
    /** Compact "Also: X, Y, +N more" suffix appended after tiebreaker note. */
    public static String assistantAlso(java.util.List<String> names, int extra) {
        String joined = String.join(", ", names);
        if (extra > 0) joined += s(", +" + extra + " weitere", ", +" + extra + " more");
        return s("Auch: ", "Also: ") + joined + ".";
    }

    // ---- Game Assistant: Spiellage-Analyse (9th profile) ----
    public static String assistantContextTitle() {
        return s("Spiellage-Analyse", "Situation Analysis");
    }
    public static String assistantContextPhase(String phase, int maxOppLandmarks,
                                                double earlyStr, double midStr, double lateStr) {
        String blend;
        if (earlyStr >= 0.01 && midStr >= 0.01 && lateStr >= 0.01) {
            blend = s(
                String.format("Früh %.0f%% · Mitte %.0f%% · Spät %.0f%%",
                        earlyStr*100, midStr*100, lateStr*100),
                String.format("Early %.0f%% · Mid %.0f%% · Late %.0f%%",
                        earlyStr*100, midStr*100, lateStr*100));
        } else {
            blend = s("Phase: " + phase, "Phase: " + phase);
        }
        return blend + s("  ·  Gegner max. " + maxOppLandmarks + " GPs",
                         "  ·  Opponents max. " + maxOppLandmarks + " GPs");
    }
    public static String assistantContextRecommend(String card) {
        return s("Empfehlung: <b>" + card + "</b>", "Recommendation: <b>" + card + "</b>");
    }
    /**
     * Returns a one-line position description (Aufholjagd / Vorbeiziehen / neutral), or null
     * if the situation is neutral (no strong signal).
     */
    public static String assistantContextPosition(double catchUp, double pullAhead,
                                                   double evGap, double turnsOwn, double turnsOpp) {
        String urgency = "";
        if (turnsOpp < Double.MAX_VALUE && turnsOpp <= 4) {
            urgency = isDE()
                ? String.format("  [!] Gegner in ~%.0f Zügen fertig!", turnsOpp)
                : String.format("  [!] Opponent ~%.0f turns from win!", turnsOpp);
        } else if (turnsOwn < Double.MAX_VALUE && turnsOwn <= 4) {
            urgency = isDE()
                ? String.format("  → Du in ~%.0f Zügen fertig", turnsOwn)
                : String.format("  → You ~%.0f turns from win", turnsOwn);
        }

        if (catchUp >= 0.35) {
            String evStr = evGap < -0.1
                    ? String.format(isDE() ? " (EV -%4.2f¢/Rd.)" : " (EV -%4.2f¢/rd.)", -evGap) : "";
            return isDE()
                ? "<b>Aufholjagd</b> — liegst zurück" + evStr + urgency
                : "<b>Catch-up</b> — you're behind" + evStr + urgency;
        } else if (pullAhead >= 0.35) {
            String evStr = evGap > 0.1
                    ? String.format(isDE() ? " (EV +%4.2f¢/Rd.)" : " (EV +%4.2f¢/rd.)", evGap) : "";
            return isDE()
                ? "<b>Vorbeiziehen</b> — liegst vorne" + evStr + urgency
                : "<b>Pull-ahead</b> — you're leading" + evStr + urgency;
        } else if (!urgency.isEmpty()) {
            return urgency.trim();
        }
        return null;
    }
    public static String assistantContextSynergyGap(String card, double evGain) {
        return isDE()
            ? String.format("[+] %s fehlt — würde Portfolio um +%.2f¢/Runde steigern", card, evGain)
            : String.format("[+] %s missing — would boost portfolio by +%.2f¢/round", card, evGain);
    }
    public static String assistantContextWeightsBlend() {
        return s("Kriterien-Gewichte = interpoliert aus Frühphase/Mittelspiel/Endspiel-Profilen:",
                 "Criteria weights = interpolated from Early/Mid/Late profiles:");
    }
    public static String assistantContextFactor(String profile, double weight, int rank) {
        return String.format("×%.1f  %s", weight, profile) + s("  — Rang #" + rank, "  — rank #" + rank);
    }
    public static String assistantContextGPHint(String gp, double evGain) {
        if (evGain > 0.01) {
            return s("[GP] " + gp + " lohnt sich (+" + String.format("%.2f", evGain) + "¢/Runde)",
                     "[GP] " + gp + " worth buying (+" + String.format("%.2f", evGain) + "¢/round)");
        } else {
            return s("[GP] " + gp + " lohnt sich (Synergie mit Portfolio)",
                     "[GP] " + gp + " worth buying (synergy with portfolio)");
        }
    }
    public static String assistantContextNoAffordable() {
        return s("Keine erschwinglichen Karten — sparen.", "No affordable cards — save up.");
    }
    public static String assistantPhaseEarly()  { return s("Frühphase", "Early Game"); }
    public static String assistantPhaseMid()    { return s("Mittelspiel", "Mid Game"); }
    public static String assistantPhaseLate()   { return s("Endspiel", "Late Game"); }

    /** Shown in the context profile when the player owns Bahnhof. */
    public static String assistantDiceHint1d6() {
        return s("[W] 1W6 optimal — Portfolio aktiviert hauptsächlich auf 1–6",
                 "[D] 1d6 optimal — portfolio activates mainly on 1–6");
    }
    public static String assistantDiceHint2d6() {
        return s("[W] 2W6 optimal — Portfolio aktiviert hauptsächlich auf 7–12",
                 "[D] 2d6 optimal — portfolio activates mainly on 7–12");
    }

    public static String deepAnalysisBtn()    { return s("Deep Analysis AUS", "Deep Analysis OFF"); }
    public static String deepAnalysisBtnOn()  { return s("⚡ Deep Analysis AN", "⚡ Deep Analysis ON"); }
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

    public static String mcTempLabel()        { return "T:"; }
    public static String mcTempTooltip()      {
        return s(
            "Boltzmann-Temperatur für MC-Kaufpolitik: " +
            "0 = deterministisch greedy (alle Spieler kaufen immer optimal), " +
            "0.7 = empfohlen (realistische Streuung), " +
            "hoch = zufällig.",
            "Boltzmann temperature for MC buy policy: " +
            "0 = greedy (all players always buy optimally), " +
            "0.7 = recommended (realistic spread), high = random."
        );
    }

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

    /** Label shown in the ranking table for the synthetic "Wait/Save" entry. */
    public static String waitLabel()          { return s("≡ Sparen", "≡ Save"); }

    /**
     * Notes line for the "Wait/Save" entry: explains which card to save for and how many
     * turns until it's affordable.
     *
     * @param cardName    localized name of the card to save for
     * @param turnsToSave estimated turns until the player can afford the card (may be fractional)
     */
    public static String waitEntryNotes(String cardName, double turnsToSave) {
        String turns = String.format("%.1f", turnsToSave);
        return s("Spare auf: " + cardName + " (~" + turns + " Züge)",
                 "Save for: " + cardName + " (~" + turns + " turns)");
    }

    /**
     * Short synergy hint appended to card notes, e.g. "Kombiniert gut mit Bauernhof (+0.30¢)".
     *
     * @param partnerName  localized name of the synergy partner card
     * @param gainPerRound expected EV gain per round from owning the partner
     */
    public static String synergyNote(String partnerName, double gainPerRound) {
        return s("Gut mit: " + partnerName + " (+" + String.format("%.2f", gainPerRound) + "¢/Runde)",
                 "Pairs with: " + partnerName + " (+" + String.format("%.2f", gainPerRound) + "¢/round)");
    }

    /**
     * Two-turn lookahead note: shown in card notes when buying card A then card B
     * gives a significantly better combined ROI than any single purchase.
     *
     * @param followUpName  localized name of the recommended follow-up card
     * @param followUpRoi   the follow-up card's ROI over horizon in post-A state
     */
    public static String twoTurnNote(String followUpName, double followUpRoi) {
        return s("Danach: " + followUpName + " (ROI +" + String.format("%.1f", followUpRoi) + ")",
                 "Then buy: " + followUpName + " (ROI +" + String.format("%.1f", followUpRoi) + ")");
    }

    public static String bürohausSwapTitle()  { return s("Bürohaus: Karte tauschen?", "Office Building: Swap Card?"); }
    public static String bürohausSwapPrompt(String note, double evGain) {
        return s(note + "\nGeschätzter EV-Gewinn: +" + String.format("%.2f", evGain) + " Münzen/Runde.\nTauschen?",
                 note + "\nEstimated EV gain: +" + String.format("%.2f", evGain) + " coins/round.\nSwap?");
    }

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

    // Short color names for SnapshotCard chips
    public static String snapshotColorBlauShort()  { return s("Blau",  "Blue"); }
    public static String snapshotColorGrünShort()  { return s("Grün",  "Green"); }
    public static String snapshotColorRotShort()   { return s("Rot",   "Red"); }
    public static String snapshotColorLilaShort()  { return s("Lila",  "Purple"); }
    public static String snapshotColorGelbShort()  { return s("Gelb",  "Yellow"); }
    public static String snapshotCardNone()        { return s("(keine Karten)", "(no cards)"); }

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
    public static String menuTools()          { return s("Werkzeuge", "Tools"); }
    public static String menuLabelingWindow() { return s("Spielphase Labeling…", "Game Phase Labeling…"); }

    // =========================================================================
    // Build-note strings
    // =========================================================================

    public static String costNotRecouped(int turns) {
        return s("(Kosten werden in " + turns + " Zügen nicht zurückgespielt)",
                 "(cost may not be recouped in " + turns + " turns)");
    }

    // =========================================================================
    // LabelingWindow (N4c)
    // =========================================================================

    public static String labelingWindowTitle()   { return s("Spielphase Labeling", "Game Phase Labeling"); }
    public static String labelingNextBtn()       { return s("Nächster Snapshot →", "Next Snapshot →"); }
    public static String labelingFromFileBtn()   { return s("Aus Datei laden…", "Load from File…"); }
    public static String labelingExportBtn()     { return s("Labels exportieren…", "Export Labels…"); }
    public static String labelingLabelCount(int n) { return s(n + " Label(s) gespeichert", n + " label(s) saved"); }
    public static String labelingSliderEarlyLeft()  { return s("Nicht Frühphase", "Not Early Game"); }
    public static String labelingSliderEarlyRight() { return s("Frühphase", "Early Game"); }
    public static String labelingSliderMidLeft()    { return s("Nicht Mittelspiel", "Not Mid Game"); }
    public static String labelingSliderMidRight()   { return s("Mittelspiel", "Mid Game"); }
    public static String labelingSliderLateLeft()   { return s("Nicht Endspiel", "Not Late Game"); }
    public static String labelingSliderLateRight()  { return s("Endspiel", "Late Game"); }
    public static String labelingNoSnapshot()       { return s("Kein Snapshot geladen.", "No snapshot loaded."); }
    public static String labelingNumPlayers()       { return s("Spieleranzahl:", "Number of players:"); }
    public static String labelingTurnRange()        { return s("Züge (min–max):", "Turns (min–max):"); }
    public static String labelingGenerate()         { return s("Generieren", "Generate"); }
    public static String labelingExportSuccess(String path) {
        return s("Labels gespeichert: " + path, "Labels saved: " + path);
    }
    public static String labelingExportError(String msg) {
        return s("Export fehlgeschlagen: " + msg, "Export failed: " + msg);
    }
    public static String labelingLoadError(String msg) {
        return s("Fehler beim Laden: " + msg, "Error loading file: " + msg);
    }

    // =========================================================================
    // Income Matrix (left panel, collapsible)
    // =========================================================================

    public static String incomeMatrixToggleShow() { return s("Einkommensmatrix anzeigen", "Show Income Matrix"); }
    public static String incomeMatrixToggleHide() { return s("Einkommensmatrix verbergen", "Hide Income Matrix"); }
    /** Column header: "Roll" */
    public static String incomeMatrixRollHeader() { return s("Wurf", "Roll"); }

    // =========================================================================
    // Header bar
    // =========================================================================

    /** Compact header showing active player name, turn number, coins, and win probability. */
    public static String headerBar(String playerName, int turn, int coins, double winPct) {
        return s(
            "<html><b>" + playerName + "</b>  ·  Zug " + turn
                + "  ·  " + coins + " " + coinsUnit()
                + "  ·  <span style='color:#1A5C28'>Win: "
                + String.format("%.1f", winPct) + "%</span></html>",
            "<html><b>" + playerName + "</b>  ·  Turn " + turn
                + "  ·  " + coins + " " + coinsUnit()
                + "  ·  <span style='color:#1A5C28'>Win: "
                + String.format("%.1f", winPct) + "%</span></html>"
        );
    }

    // =========================================================================
    // Contextual metric tooltips (for card details panel)
    // =========================================================================

    /**
     * Rich contextual tooltip for EV/round metric.
     * @param value   the card's EV/round value
     * @param rankPos 1-based rank among all cards (1 = best)
     * @param total   total number of cards ranked
     */
    public static String evTooltipContextual(double value, int rankPos, int total) {
        String quality = value >= 0.35 ? s("Sehr gut", "Very good")
                       : value >= 0.10 ? s("Gut", "Good")
                       : value >= 0.0  ? s("Neutral", "Neutral")
                                       : s("Schlecht", "Poor");
        return s(
            "<html><b>EV / Runde</b> — Erwartete Münzen pro vollständiger Runde<br>"
            + "(eigener Zug + alle Gegner-Züge kombiniert)<br><br>"
            + "• ≥ 0.35¢/Rd = Sehr gut &nbsp; • ≥ 0.10 = Gut &nbsp; • < 0 = Schlecht<br><br>"
            + "<b>" + String.format("%.2f", value) + "¢/Runde</b> — " + quality
            + " &nbsp;·&nbsp; Rang #" + rankPos + " von " + total + "</html>",
            "<html><b>EV / round</b> — Expected coins per full game round<br>"
            + "(your turn + all opponent turns combined)<br><br>"
            + "• ≥ 0.35¢/rd = Very good &nbsp; • ≥ 0.10 = Good &nbsp; • < 0 = Poor<br><br>"
            + "<b>" + String.format("%.2f", value) + "¢/rd</b> — " + quality
            + " &nbsp;·&nbsp; Rank #" + rankPos + " of " + total + "</html>"
        );
    }

    /**
     * Rich contextual tooltip for ROI metric.
     */
    public static String roiTooltipContextual(double value, int rankPos, int total) {
        String quality = value >= 2.0  ? s("Ausgezeichnet — sofort kaufen", "Excellent — buy now")
                       : value >= 0.5  ? s("Gut — lohnend", "Good — profitable")
                       : value >= 0.0  ? s("Schwach — knapp rentabel", "Weak — barely profitable")
                                       : s("Negativ — nicht rentabel in 10 Zügen", "Negative — not profitable in 10 turns");
        return s(
            "<html><b>ROI (10 Runden)</b> — Diskontierter Münzgewinn über 10 Runden minus Kaufpreis<br><br>"
            + "• ≥ 2.0 = Ausgezeichnet &nbsp; • ≥ 0.5 = Gut &nbsp; • ≥ 0 = Schwach &nbsp; • &lt; 0 = Nicht rentabel<br><br>"
            + "<b>" + String.format("%.2f", value) + "</b> — " + quality
            + " &nbsp;·&nbsp; Rang #" + rankPos + " von " + total + "</html>",
            "<html><b>ROI (10 turns)</b> — Discounted coin gain over 10 turns minus purchase cost<br><br>"
            + "• ≥ 2.0 = Excellent &nbsp; • ≥ 0.5 = Good &nbsp; • ≥ 0 = Weak &nbsp; • &lt; 0 = Not profitable<br><br>"
            + "<b>" + String.format("%.2f", value) + "</b> — " + quality
            + " &nbsp;·&nbsp; Rank #" + rankPos + " of " + total + "</html>"
        );
    }

    /**
     * Rich contextual tooltip for P(0 income) metric.
     */
    public static String p0TooltipContextual(double value, int rankPos, int total) {
        String quality = value <= 0.35 ? s("Zuverlässig — kommt selten leer", "Reliable — rarely earns nothing")
                       : value <= 0.55 ? s("Gut", "Good")
                       : value <= 0.80 ? s("Riskant — oft Runden ohne Ertrag", "Risky — often earns nothing")
                                       : s("Sehr riskant — meistens leer", "Very risky — usually earns nothing");
        return s(
            "<html><b>P(0 Einkommen)</b> — Wahrscheinlichkeit, in der ganzen Runde 0 Münzen zu erhalten<br><br>"
            + "• ≤ 35% = Zuverlässig &nbsp; • ≤ 55% = Gut &nbsp; • ≤ 80% = Riskant &nbsp; • &gt; 80% = Sehr riskant<br>"
            + "<i>Niedriger ist besser.</i><br><br>"
            + "<b>" + String.format("%.0f", value * 100) + "%</b> — " + quality
            + " &nbsp;·&nbsp; Rang #" + rankPos + " von " + total + "</html>",
            "<html><b>P(0 income)</b> — Probability of earning 0 coins across the whole round<br><br>"
            + "• ≤ 35% = Reliable &nbsp; • ≤ 55% = Good &nbsp; • ≤ 80% = Risky &nbsp; • &gt; 80% = Very risky<br>"
            + "<i>Lower is better.</i><br><br>"
            + "<b>" + String.format("%.0f", value * 100) + "%</b> — " + quality
            + " &nbsp;·&nbsp; Rank #" + rankPos + " of " + total + "</html>"
        );
    }

    /**
     * Rich contextual tooltip for Variance metric.
     */
    public static String varianceTooltipContextual(double value, int rankPos, int total) {
        String quality = value <= 0.8  ? s("Stabil — vorhersehbares Einkommen", "Stable — predictable income")
                       : value <= 1.5  ? s("Mäßig", "Moderate")
                       : value <= 3.0  ? s("Boom-oder-Pleite", "Boom-or-bust")
                                       : s("Sehr volatil", "Very volatile");
        return s(
            "<html><b>Varianz</b> — Statistische Streuung des Einkommens pro Zug<br><br>"
            + "• ≤ 0.8 = Stabil &nbsp; • ≤ 1.5 = Mäßig &nbsp; • ≤ 3.0 = Boom-oder-Pleite &nbsp; • &gt; 3.0 = Sehr volatil<br>"
            + "<i>Niedriger = berechenbarer. Höher = gelegentlich viel, oft nichts.</i><br><br>"
            + "<b>" + String.format("%.2f", value) + "</b> — " + quality
            + " &nbsp;·&nbsp; Rang #" + rankPos + " von " + total + "</html>",
            "<html><b>Variance</b> — Statistical spread of per-turn income<br><br>"
            + "• ≤ 0.8 = Stable &nbsp; • ≤ 1.5 = Moderate &nbsp; • ≤ 3.0 = Boom-or-bust &nbsp; • &gt; 3.0 = Very volatile<br>"
            + "<i>Lower = more predictable. Higher = occasionally large, often nothing.</i><br><br>"
            + "<b>" + String.format("%.2f", value) + "</b> — " + quality
            + " &nbsp;·&nbsp; Rank #" + rankPos + " of " + total + "</html>"
        );
    }

    /**
     * Rich contextual tooltip for Win Probability Delta metric.
     */
    public static String winProbTooltipContextual(double value, int rankPos, int total, boolean isMC) {
        String method = isMC ? s("MC-Simulation", "MC simulation") : s("analytisch", "analytical");
        String quality = value >= 0.02 ? s("Signifikante Verbesserung — kaufen", "Significant boost — buy it")
                       : value >= 0.005? s("Kleine Verbesserung", "Small boost")
                       : value >= 0.0  ? s("Neutral oder minimal", "Neutral or minimal")
                                       : s("Reduziert Siegchance", "Reduces win probability");
        return s(
            "<html><b>Gewinnwahrscheinlichkeit Δ</b> — Änderung der Siegchance durch diesen Kauf<br>"
            + "Methode: " + method + "<br><br>"
            + "• ≥ +2% = Sehr gut &nbsp; • ≥ +0.5% = Gut &nbsp; • ≈ 0% = Neutral &nbsp; • &lt; 0% = Nachteilig<br>"
            + "<i>Softmax-Schätzung basierend auf EV-Verhältnis aller Spieler.</i><br><br>"
            + "<b>" + String.format("%+.1f", value * 100) + "%</b> — " + quality
            + " &nbsp;·&nbsp; Rang #" + rankPos + " von " + total + "</html>",
            "<html><b>Win Probability Δ</b> — Change in win probability from buying this card<br>"
            + "Method: " + method + "<br><br>"
            + "• ≥ +2% = Very good &nbsp; • ≥ +0.5% = Good &nbsp; • ≈ 0% = Neutral &nbsp; • &lt; 0% = Harmful<br>"
            + "<i>Softmax estimate based on relative EV scores of all players.</i><br><br>"
            + "<b>" + String.format("%+.1f", value * 100) + "%</b> — " + quality
            + " &nbsp;·&nbsp; Rank #" + rankPos + " of " + total + "</html>"
        );
    }

    /**
     * Rich contextual tooltip for Portfolio ΔEV metric.
     */
    public static String portfolioDeltaTooltipContextual(double value, int rankPos, int total) {
        String quality = value >= 0.3  ? s("Starke Synergie mit deinem Portfolio", "Strong synergy with your portfolio")
                       : value >= 0.08 ? s("Gute Synergie", "Good synergy")
                       : value >= 0.0  ? s("Schwache Synergie", "Weak synergy")
                                       : s("Keine Synergie", "No synergy");
        return s(
            "<html><b>Portfolio ΔEV</b> — Marginaler EV-Gewinn pro Runde durch diesen Kauf:<br>"
            + "playerEvPerRound(Portfolio + Karte) − playerEvPerRound(Portfolio)<br><br>"
            + "• ≥ 0.30¢/Rd = Starke Synergie &nbsp; • ≥ 0.08 = Gut &nbsp; • ≥ 0 = Schwach<br>"
            + "<i>Erfasst Kreuz-Synergien, z.B. Bahnhof schaltet 7–12 Karten frei.</i><br><br>"
            + "<b>+" + String.format("%.2f", value) + "¢/Runde</b> — " + quality
            + " &nbsp;·&nbsp; Rang #" + rankPos + " von " + total + "</html>",
            "<html><b>Portfolio ΔEV</b> — Marginal EV gain per round from this purchase:<br>"
            + "playerEvPerRound(portfolio + card) − playerEvPerRound(portfolio)<br><br>"
            + "• ≥ 0.30¢/rd = Strong synergy &nbsp; • ≥ 0.08 = Good &nbsp; • ≥ 0 = Weak<br>"
            + "<i>Captures cross-card synergies, e.g. Train Station unlocks 7–12 cards.</i><br><br>"
            + "<b>+" + String.format("%.2f", value) + "¢/round</b> — " + quality
            + " &nbsp;·&nbsp; Rank #" + rankPos + " of " + total + "</html>"
        );
    }

    // =========================================================================
    // Inline insight summary (below metrics grid in Card Details)
    // =========================================================================

    /**
     * Builds a 1–2 sentence HTML insight summary from the card's metrics and ranks.
     * @param cardName    localized card name
     * @param roi         ROI value
     * @param roiRank     ROI rank (1 = best)
     * @param winDelta    win prob delta
     * @param evPerRound  EV per round
     * @param affordable  true if the card is currently affordable
     * @param totalCards  total cards in ranking
     */
    public static String metricInsightSummary(
            String cardName, double roi, int roiRank, double winDelta,
            double evPerRound, boolean affordable, int totalCards) {
        StringBuilder sb = new StringBuilder("<html><body style='width:270px;color:#222;font-size:11px'>");

        // Lead: strongest signal
        if (roiRank == 1 && roi > 0) {
            sb.append(s("<b>★ Beste Option diese Runde</b> — ROI ", "<b>★ Best option this turn</b> — ROI "));
            sb.append(String.format("%.2f", roi)).append(s(". ", ". "));
        } else if (roi >= 2.0) {
            sb.append(s("<b>Sehr guter ROI</b> (", "<b>Excellent ROI</b> ("));
            sb.append(String.format("+%.2f", roi)).append(") · #").append(roiRank)
              .append(s(" von ", " of ")).append(totalCards).append(". ");
        } else if (roi >= 0.5) {
            sb.append(s("Solider ROI +", "Solid ROI +"));
            sb.append(String.format("%.2f", roi)).append(s(" · kaufenswert. ", " · worth buying. "));
        } else if (roi < 0) {
            sb.append(s("<b style='color:#AA0000'>Kosten nicht amortisiert</b> in 10 Zügen (ROI ",
                        "<b style='color:#AA0000'>Cost not recouped</b> in 10 turns (ROI "));
            sb.append(String.format("%.2f", roi)).append("). ");
        } else {
            sb.append(s("Schwacher ROI (", "Weak ROI ("));
            sb.append(String.format("+%.2f", roi)).append("). ");
        }

        // Win delta qualifier
        if (winDelta >= 0.02) {
            sb.append(s("Win-Δ <b style='color:#1A5C28'>+", "Win Δ <b style='color:#1A5C28'>+"));
            sb.append(String.format("%.1f", winDelta * 100)).append("%</b> — ");
            sb.append(s("kaufen.", "buy it."));
        } else if (winDelta >= 0.005) {
            sb.append(s("Win-Δ +", "Win Δ +")).append(String.format("%.1f", winDelta * 100)).append("% — ");
            sb.append(s("leicht positiv.", "slightly positive."));
        } else if (winDelta < -0.005) {
            sb.append(s("Win-Δ <b style='color:#AA0000'>", "Win Δ <b style='color:#AA0000'>"));
            sb.append(String.format("%.1f", winDelta * 100)).append("%</b> — ");
            sb.append(s("senkt Siegchance.", "lowers win probability."));
        }

        if (!affordable) {
            sb.append(s(" <i>(nicht erschwinglich)</i>", " <i>(not affordable)</i>"));
        }

        sb.append("</body></html>");
        return sb.toString();
    }

    // =========================================================================
    // Deep Analysis / MC explanations (rich tooltips for right panel controls)
    // =========================================================================

    public static String deepAnalysisTooltipRich() {
        return s(
            "<html><b>Deep Analysis (Monte Carlo)</b><br><br>"
            + "Aktiviert MC-Simulationen für genauere Win-Prob-Deltas.<br>"
            + "Jede Karte wird durch N vollständige Spiele simuliert.<br><br>"
            + "<b>OFF:</b> Nur analytische Softmax-Schätzung (schnell, ~0ms)<br>"
            + "<b>ON:</b> MC ersetzt Stufe-2-Schätzung für Top-5 Karten (langsamer)<br><br>"
            + "<i>Empfehlung: ON für finale Kaufentscheidung, OFF für schnelles Scrollen.</i></html>",
            "<html><b>Deep Analysis (Monte Carlo)</b><br><br>"
            + "Enables MC simulations for more accurate win-prob deltas.<br>"
            + "Each card is evaluated through N complete game simulations.<br><br>"
            + "<b>OFF:</b> Analytical softmax estimate only (fast, ~0ms)<br>"
            + "<b>ON:</b> MC replaces Tier-2 estimate for top-5 cards (slower)<br><br>"
            + "<i>Recommendation: ON for final decisions, OFF for quick browsing.</i></html>"
        );
    }

    public static String mcSimTooltipRich() {
        return s(
            "<html><b>N — Anzahl MC-Simulationen</b><br><br>"
            + "Wie viele vollständige Spiele pro Karte simuliert werden.<br><br>"
            + "<b>100:</b> Schnell (~10ms), ±3–5% Fehler<br>"
            + "<b>1000:</b> Empfohlen (~90ms), ±1–2% Fehler<br>"
            + "<b>5000:</b> Präzise (~450ms), ±0.5% Fehler<br>"
            + "<b>10000:</b> Sehr präzise (~900ms), ±0.3% Fehler<br><br>"
            + "<i>Für Endspiel-Entscheidungen: ≥ 2500 empfohlen.</i></html>",
            "<html><b>N — Number of MC simulations</b><br><br>"
            + "How many complete games are simulated per card.<br><br>"
            + "<b>100:</b> Fast (~10ms), ±3–5% error<br>"
            + "<b>1000:</b> Recommended (~90ms), ±1–2% error<br>"
            + "<b>5000:</b> Precise (~450ms), ±0.5% error<br>"
            + "<b>10000:</b> Very precise (~900ms), ±0.3% error<br><br>"
            + "<i>For endgame decisions: ≥ 2500 recommended.</i></html>"
        );
    }

    public static String mcTempTooltipRich() {
        return s(
            "<html><b>T — Boltzmann-Temperatur (Gegner-Verhalten)</b><br><br>"
            + "Steuert wie zufällig Gegner im MC kaufen:<br><br>"
            + "<b>T = 0:</b> Alle Gegner kaufen immer optimal (greedy)<br>"
            + "→ überschätzt starke Gegner, unterschätzt schwache<br>"
            + "<b>T = 0.3–0.5:</b> Leicht explorativ — realistischer<br>"
            + "<b>T = 0.7:</b> Empfohlen — gelegentlich suboptimale Käufe<br>"
            + "<b>T = 1.5+:</b> Fast zufällig — unterschätzt alle Gegner<br><br>"
            + "<i>T = 0.7 ist nicht empirisch kalibriert — konservative Schätzung.</i></html>",
            "<html><b>T — Boltzmann temperature (opponent behaviour)</b><br><br>"
            + "Controls how randomly opponents buy in MC:<br><br>"
            + "<b>T = 0:</b> All opponents always buy optimally (greedy)<br>"
            + "→ overestimates strong opponents, underestimates weak<br>"
            + "<b>T = 0.3–0.5:</b> Slightly explorative — more realistic<br>"
            + "<b>T = 0.7:</b> Recommended — occasional suboptimal buys<br>"
            + "<b>T = 1.5+:</b> Near-random — underestimates all opponents<br><br>"
            + "<i>T = 0.7 is not empirically calibrated — a conservative estimate.</i></html>"
        );
    }

    public static String rolloutDepthTooltipRich() {
        return s(
            "<html><b>Tiefe — Expectimax-Rollout-Tiefe</b><br><br>"
            + "Wie viele vollständige Runden der Baum expandiert.<br>"
            + "1 Runde = eigener Zug + N−1 simulierte Gegner-Züge.<br><br>"
            + "<b>Tiefe 1:</b> Schnell (~20ms), 1 Runde vorausschauend<br>"
            + "<b>Tiefe 2:</b> Empfohlen (~200ms), 2 Runden — erfasst Folge-Käufe<br>"
            + "<b>Tiefe 3:</b> Langsam (~2s), 3 Runden — maximale Genauigkeit<br><br>"
            + "<i>Tiefe 2 balanciert Laufzeit und Qualität für die meisten Situationen.</i></html>",
            "<html><b>Depth — Expectimax rollout depth</b><br><br>"
            + "How many full rounds the tree expands.<br>"
            + "1 round = your turn + N−1 simulated opponent turns.<br><br>"
            + "<b>Depth 1:</b> Fast (~20ms), 1 round lookahead<br>"
            + "<b>Depth 2:</b> Recommended (~200ms), 2 rounds — captures follow-up buys<br>"
            + "<b>Depth 3:</b> Slow (~2s), 3 rounds — maximum accuracy<br><br>"
            + "<i>Depth 2 balances runtime and quality for most situations.</i></html>"
        );
    }

    public static String rolloutTopKTooltipRich() {
        return s(
            "<html><b>Top-K — Kandidaten pro Entscheidungsknoten</b><br><br>"
            + "Wie viele der besten Kaufoptionen an jedem Knoten expandiert werden.<br><br>"
            + "<b>K = 2:</b> Nur die 2 besten Optionen — sehr schnell, kann Alternativen verpassen<br>"
            + "<b>K = 5:</b> Empfohlen — gute Balance aus Breite und Geschwindigkeit<br>"
            + "<b>K = 8:</b> Breite Suche — langsamer, erfasst mehr Alternativen<br><br>"
            + "<i>Pruning-Kriterium: Karten mit &lt;50% des besten portfolioDeltaEV werden ausgeschlossen.</i></html>",
            "<html><b>Top-K — Candidates per decision node</b><br><br>"
            + "How many of the best buy options are expanded at each node.<br><br>"
            + "<b>K = 2:</b> Only the top 2 options — very fast, may miss alternatives<br>"
            + "<b>K = 5:</b> Recommended — good balance of breadth and speed<br>"
            + "<b>K = 8:</b> Broad search — slower, explores more alternatives<br><br>"
            + "<i>Pruning criterion: cards with &lt;50% of the best portfolioDeltaEV are excluded.</i></html>"
        );
    }
}
