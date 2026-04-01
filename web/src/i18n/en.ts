/** English locale strings. */
const en: Record<string, string> = {
  // General
  'app.title': 'Machi Koro Advisor',
  'btn.start': 'Start Game',
  'btn.skip': 'Skip',
  'btn.undo': 'Undo',
  'btn.save': 'Save',
  'btn.load': 'Load',
  'btn.settings': 'Settings',
  'btn.newGame': 'New Game',
  'btn.review': 'Review',
  'btn.swap': 'Swap',
  'btn.decline': 'Decline',
  'btn.confirmTurn': 'Confirm Turn',

  // Setup
  'setup.title': 'New Game',
  'setup.playerCount': 'Player Count',
  'setup.playerName': 'Player {n}',
  'setup.savedGames': 'Saved Games',
  'setup.advanced': 'Advanced',
  'setup.jumpBackIn': 'Jump Back In',

  // Turn indicator
  'turn.your': 'Your Turn',
  'turn.opponent': "{name}'s Turn",
  'turn.bonus': 'BONUS TURN!',
  'turn.count': 'Turn {n}',

  // Dice
  'dice.1d6': '1 Die',
  'dice.2d6': '2 Dice',
  'dice.doubles': 'Doubles!',

  // Coin flow
  'coins.now': 'Now',
  'coins.roll': 'Roll',
  'coins.buy': 'Buy',

  // Purchase
  'purchase.assistant': 'Assistant',
  'purchase.manual': 'Manual',
  'purchase.recommendation': 'Recommendation',
  'purchase.seeAll': 'See all options',
  'purchase.winRate': 'Win Rate',

  // Bürohaus
  'bürohaus.title': 'Bürohaus — Card Swap',
  'bürohaus.yourCards': 'Your Cards',
  'bürohaus.opponentCards': "{name}'s Cards",
  'bürohaus.notEligible': 'Not eligible',
  'bürohaus.recommended': 'Recommended',
  'bürohaus.swapLabel': "Swap your {own} ↔ {opp}'s {card}",

  // Insights
  'insights.etw': 'Estimated Turns to Win',
  'insights.tempo': 'Tempo Advantage',
  'insights.portfolio': 'Portfolio EV',
  'insights.supply': 'Supply',
  'insights.supplyWarning': '{card} — only {n} left',
  'insights.analyzing': 'Analyzing your next turn…',

  // Settings
  'settings.title': 'Settings',
  'settings.engine': 'Engine',
  'settings.mode': 'Mode',
  'settings.language': 'Language',
  'settings.autosave': 'Autosave',
  'settings.userPlayer': 'My Player',

  // Game over
  'gameOver.title': '{name} wins!',
  'gameOver.standings': 'Final Standings',
  'gameOver.landmarks': 'Landmarks',

  // Card colors
  'color.blau': 'Blue',
  'color.rot': 'Red',
  'color.grün': 'Green',
  'color.lila': 'Purple',
  'color.gelb': 'Yellow',

  // Explanation factor categories
  'factor.winRate': 'Win',
  'factor.income': 'Income',
  'factor.synergy': 'Synergy',
  'factor.risk': 'Risk',
  'factor.tempo': 'Tempo',
  'factor.landmark': 'Landmark',
  'factor.cost': 'Cost',
  'factor.coverage': 'Coverage',
  'factor.scarcity': 'Scarcity',

  // H2H
  'btn.back': 'Back',
  'h2h.title': 'Head-to-Head Testing',
  'h2h.newMatch': 'New Match',
  'h2h.engineA': 'Engine A (Player 1)',
  'h2h.engineB': 'Engine B (Player 2)',
  'h2h.games': 'Games',
  'h2h.iterations': 'Iterations / Eval',
  'h2h.start': 'Start Match',
  'h2h.running': 'Running...',
  'h2h.results': 'Results',
  'h2h.noResults': 'No matches played yet.',
  'h2h.matchup': 'Matchup',
  'h2h.gamesCol': 'Games',
  'h2h.winRate': 'Win %',
  'h2h.avgTurns': 'Avg Turns',
  'h2h.avgEval': 'Avg Eval',
  'h2h.time': 'Time',
  'h2h.winsOf': 'wins of',
  'h2h.gameList': 'Game Log',
  'h2h.winner': 'Winner',
  'h2h.turns': 'turns',
  'h2h.landmarks': 'Landmarks',
  'h2h.coins': 'Coins',
  'h2h.config': 'Config',
  'h2h.game': 'Game',
  'h2h.timeout': 'timeout',
  'h2h.won': 'won',
  'h2h.turnN': 'Turn {n}',
  'h2h.dice': 'Dice',
  'h2h.income': 'Income',
  'h2h.purchase': 'Purchase',
  'h2h.save': 'Save',
  'h2h.finalState': 'Final State',
  'h2h.nav': 'H2H Testing',
};

export default en;
