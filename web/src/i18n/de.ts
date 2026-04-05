/** German locale strings. */
const de: Record<string, string> = {
  // General
  'app.title': 'Machi Koro Berater',
  'btn.start': 'Spiel starten',
  'btn.skip': 'Überspringen',
  'btn.undo': 'Rückgängig',
  'btn.save': 'Speichern / Laden',
  'btn.load': 'Laden',
  'btn.settings': 'Einstellungen',
  'btn.newGame': 'Neues Spiel',
  'btn.review': 'Rückblick',
  'btn.swap': 'Tauschen',
  'btn.decline': 'Ablehnen',
  'btn.confirmTurn': 'Zug bestätigen',

  // Setup
  'setup.title': 'Neues Spiel',
  'setup.playerCount': 'Spieleranzahl',
  'setup.playerName': 'Spieler {n}',
  'setup.savedGames': 'Gespeicherte Spiele',
  'setup.advanced': 'Erweitert',
  'setup.jumpBackIn': 'Zurückspringen',

  // Turn indicator
  'turn.your': 'Dein Zug',
  'turn.opponent': '{name}s Zug',
  'turn.bonus': 'BONUSZUG!',
  'turn.count': 'Zug {n}',
  'turn.round': 'Runde {n}',

  // Dice
  'dice.1d6': '1 Würfel',
  'dice.2d6': '2 Würfel',
  'dice.doubles': 'Pasch!',

  // Coin flow
  'coins.now': 'Jetzt',
  'coins.roll': 'Würfeln',
  'coins.buy': 'Kaufen',

  // Purchase
  'purchase.assistant': 'Assistent',
  'purchase.manual': 'Manuell',
  'purchase.recommendation': 'Empfehlung',
  'purchase.seeAll': 'Alle Optionen anzeigen',
  'purchase.winRate': 'Gewinnrate',

  // Bürohaus
  'bürohaus.title': 'Bürohaus — Kartentausch',
  'bürohaus.yourCards': 'Deine Karten',
  'bürohaus.opponentCards': '{name}s Karten',
  'bürohaus.notEligible': 'Nicht tauschbar',
  'bürohaus.recommended': 'Empfohlen',
  'bürohaus.swapLabel': 'Tausche dein {own} ↔ {opp}s {card}',

  // Insights
  'insights.etw': 'Geschätzte Züge zum Sieg',
  'insights.tempo': 'Tempovorsprung',
  'insights.portfolio': 'Portfolio EV',
  'insights.supply': 'Vorrat',
  'insights.supplyWarning': '{card} — nur noch {n} übrig',
  'insights.analyzing': 'Analysiere deinen nächsten Zug…',

  // Settings
  'settings.title': 'Einstellungen',
  'settings.engine': 'Engine',
  'settings.mode': 'Modus',
  'settings.language': 'Sprache',
  'settings.autosave': 'Automatisch speichern',
  'settings.userPlayer': 'Mein Spieler',

  // Game over
  'gameOver.title': '{name} hat gewonnen!',
  'gameOver.standings': 'Endstand',
  'gameOver.landmarks': 'Wahrzeichen',

  // Decision review
  'review.title': 'Entscheidungsrückblick',
  'review.agreed': 'Engine-Empfehlung gefolgt',
  'review.avgRank': 'Ø Rangplatz',
  'review.youBought': 'Du kauftest',
  'review.enginePick': 'Engine #1',
  'review.rank': 'Rang #{n}',
  'review.noEval': 'Keine Auswertung',
  'review.saved': 'Gespart',
  'review.match': 'Übereinstimmung!',
  'review.ofTurns': 'von {n} Zügen',
  'review.noData': 'Keine Engine-Daten für dieses Spiel verfügbar.',
  'review.backToResults': 'Zurück zum Ergebnis',

  // Card colors
  'color.blau': 'Blau',
  'color.rot': 'Rot',
  'color.grün': 'Grün',
  'color.lila': 'Lila',
  'color.gelb': 'Gelb',

  // Explanation factor categories
  'factor.winRate': 'Gewinn',
  'factor.income': 'Einkommen',
  'factor.synergy': 'Synergie',
  'factor.risk': 'Risiko',
  'factor.tempo': 'Tempo',
  'factor.landmark': 'Wahrzeichen',
  'factor.cost': 'Kosten',
  'factor.coverage': 'Abdeckung',
  'factor.scarcity': 'Knappheit',

  // H2H
  'btn.back': 'Zurück',
  'h2h.title': 'Engine-Vergleichstest',
  'h2h.newMatch': 'Neues Match',
  'h2h.engineA': 'Engine A (Spieler 1)',
  'h2h.engineB': 'Engine B (Spieler 2)',
  'h2h.games': 'Spiele',
  'h2h.iterations': 'Iterationen / Eval',
  'h2h.start': 'Match starten',
  'h2h.running': 'Läuft...',
  'h2h.results': 'Ergebnisse',
  'h2h.noResults': 'Noch keine Matches gespielt.',
  'h2h.matchup': 'Paarung',
  'h2h.gamesCol': 'Spiele',
  'h2h.winRate': 'Sieg %',
  'h2h.avgTurns': 'Ø Züge',
  'h2h.avgEval': 'Ø Eval',
  'h2h.time': 'Zeit',
  'h2h.winsOf': 'Siege von',
  'h2h.gameList': 'Spielprotokoll',
  'h2h.winner': 'Sieger',
  'h2h.turns': 'Züge',
  'h2h.landmarks': 'Wahrzeichen',
  'h2h.coins': 'Münzen',
  'h2h.config': 'Konfiguration',
  'h2h.game': 'Spiel',
  'h2h.timeout': 'Zeitlimit',
  'h2h.won': 'gewonnen',
  'h2h.turnN': 'Zug {n}',
  'h2h.dice': 'Würfel',
  'h2h.income': 'Einkommen',
  'h2h.purchase': 'Kauf',
  'h2h.save': 'Sparen',
  'h2h.finalState': 'Endstand',
  'h2h.nav': 'H2H-Test',
};

export default de;
