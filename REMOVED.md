# REMOVED.md — Functionality Removed from MachiKoroCalculator

This file documents any user-visible functionality that was removed during development.

---

## Current Status

No user-visible functionality has been removed from this project.

All changes to date have been additive (new classes, new metrics, new UI panels).

The following internal details were superseded but not "removed" in a user-visible sense:

### `WinProbabilityCalc.computeScores` — old `LANDMARK_WEIGHTS` values

**Previously:** Bahnhof=1.5, Einkaufszentrum=3.0, Freizeitpark=1.5, Funkturm=4.0, Default=2.0

**Replaced with:** Coin-equivalent calibrated values — Bahnhof=24, Einkaufszentrum=36, Freizeitpark=24, Funkturm=48, Default=20

**Impact:** The analytical softmax win-probability values changed (now more accurate). No API change.

---

*This file will be updated whenever a previously available feature or calculation mode is removed.*
