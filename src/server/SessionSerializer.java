package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import core.GameSession;
import core.TurnRecord;

import java.util.List;

/**
 * Converts a {@link GameSession} to the canonical JSON response used by all session endpoints.
 *
 * <h2>Wire format</h2>
 * <pre>
 * {
 *   "state":              { ...GameState JSON... },
 *   "nextPlayerIndex":    0,
 *   "effectiveTurnCount": 4,
 *   "bonusTurnPending":   false,
 *   "finished":           false,
 *   "winnerIndex":        -1,
 *   "history":            [ ...TurnRecord JSON... ]
 * }
 * </pre>
 */
public final class SessionSerializer {

    private SessionSerializer() {}

    /** Serializes a {@link GameSession} to a {@link JsonObject}. */
    public static JsonObject toJson(GameSession session) {
        JsonObject root = new JsonObject();
        root.add("state", GameStateSerializer.toJson(session.getState()));
        root.addProperty("nextPlayerIndex", session.nextPlayerIndex());
        root.addProperty("effectiveTurnCount", session.getEffectiveTurnCount());
        root.addProperty("bonusTurnPending", session.isBonusTurnPending());
        root.addProperty("finished", session.isFinished());
        root.addProperty("winnerIndex", session.getWinnerIndex());
        root.add("history", serializeHistory(session.getHistory()));
        return root;
    }

    private static JsonArray serializeHistory(List<TurnRecord> history) {
        JsonArray arr = new JsonArray();
        for (TurnRecord t : history) {
            JsonObject turn = new JsonObject();
            turn.addProperty("playerIndex", t.playerIndex);
            turn.addProperty("roll", t.roll);
            if (t.bought != null) {
                turn.addProperty("boughtId", t.bought.getId());
            } else {
                turn.add("boughtId", JsonNull.INSTANCE);
            }
            turn.addProperty("isDoubles", t.isDoubles);
            turn.addProperty("diceCount", t.diceCount);
            if (t.coinDeltas != null) {
                JsonArray da = new JsonArray();
                for (int d : t.coinDeltas) da.add(d);
                turn.add("coinDeltas", da);
            } else {
                turn.add("coinDeltas", JsonNull.INSTANCE);
            }
            if (t.swappedAway != null) {
                turn.addProperty("swappedAway", t.swappedAway.getId());
            } else {
                turn.add("swappedAway", JsonNull.INSTANCE);
            }
            if (t.swappedIn != null) {
                turn.addProperty("swappedIn", t.swappedIn.getId());
            } else {
                turn.add("swappedIn", JsonNull.INSTANCE);
            }
            turn.addProperty("swapOppPlayerIndex", t.swapOppPlayerIndex);
            arr.add(turn);
        }
        return arr;
    }
}
