/** German locale strings. */
const de: Record<string, string> = {
  // General
  'app.title': 'Machi Koro Berater',
  'btn.start': 'Spiel starten',
  'btn.skip': 'Überspringen',
  'btn.undo': 'Rückgängig',
  'btn.save': 'Speichern',
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
};

export default de;
