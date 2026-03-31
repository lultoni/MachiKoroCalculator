package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import core.GameState;
import core.GameStateBuilder;
import core.Player;
import core.Project;
import core.ProjectLoader;

/**
 * JSON serialization helpers for {@link GameState}.
 *
 * <p>Wire format (both request and response):
 * <pre>
 * {
 *   "players": [
 *     { "name": "Alice", "coins": 7, "ownedIds": ["weizenfeld", "bäckerei", "bahnhof"] },
 *     { "name": "Bob",   "coins": 3, "ownedIds": ["weizenfeld", "bäckerei"] }
 *   ]
 * }
 * </pre>
 *
 * <p>The unbuilt supply is reconstructed from owned cards, matching {@link GameStateBuilder} logic.
 */
final class GameStateSerializer {

    private GameStateSerializer() {}

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    /** Serializes a {@link GameState} to a {@link JsonObject}. */
    static JsonObject toJson(GameState state) {
        JsonObject obj = new JsonObject();
        JsonArray players = new JsonArray();
        for (Player p : state.getPlayers()) {
            JsonObject pObj = new JsonObject();
            pObj.addProperty("name", p.getName());
            pObj.addProperty("coins", p.getCoins());
            JsonArray ownedIds = new JsonArray();
            for (Project proj : p.getOwned_projects()) ownedIds.add(proj.getId());
            pObj.add("ownedIds", ownedIds);
            players.add(pObj);
        }
        obj.add("players", players);
        return obj;
    }

    // -------------------------------------------------------------------------
    // Deserialization
    // -------------------------------------------------------------------------

    /**
     * Deserializes a {@link GameState} from a {@link JsonObject}.
     *
     * @throws IllegalArgumentException if any project id is unknown or the player array is invalid
     */
    static GameState fromJson(JsonObject obj) {
        JsonArray playersArr = obj.getAsJsonArray("players");
        if (playersArr == null || playersArr.size() < 2 || playersArr.size() > 4) {
            throw new IllegalArgumentException(
                    "\"players\" must be an array of 2–4 player objects");
        }

        int n = playersArr.size();
        String[] names = new String[n];
        for (int i = 0; i < n; i++) {
            names[i] = playersArr.get(i).getAsJsonObject().get("name").getAsString();
        }

        GameStateBuilder builder = new GameStateBuilder(n);
        for (int i = 0; i < n; i++) {
            JsonObject pObj = playersArr.get(i).getAsJsonObject();
            builder.setPlayerName(i, names[i]);
            builder.setCoins(i, pObj.get("coins").getAsInt());
            for (JsonElement idEl : pObj.getAsJsonArray("ownedIds")) {
                String id = idEl.getAsString();
                if (ProjectLoader.getProject(id).isEmpty()) {
                    throw new IllegalArgumentException("Unknown project id: " + id);
                }
                builder.addProject(i, id);
            }
        }
        return builder.build();
    }
}
