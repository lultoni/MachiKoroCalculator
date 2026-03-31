package core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * JSON serialization and deserialization for {@link GameSession} save files.
 *
 * <h2>File format</h2>
 * <pre>
 * {
 *   "playerNames": ["Alice", "Bob"],
 *   "initialSnapshot": [
 *     {"name": "Alice", "coins": 3, "ownedIds": ["weizenfeld", "bäckerei"]},
 *     {"name": "Bob",   "coins": 3, "ownedIds": ["weizenfeld", "bäckerei"]}
 *   ],
 *   "turns": [
 *     {"playerIndex": 0, "roll": 7, "boughtId": "bauernhof"},
 *     {"playerIndex": 1, "roll": 3, "boughtId": null}
 *   ]
 * }
 * </pre>
 *
 * <p>The initial game state is stored as a snapshot so that sessions which started
 * from a mid-game snapshot (via {@link GameSession#fromSnapshot}) round-trip correctly.
 */
final class GameSessionPersistence {

    private GameSessionPersistence() {}

    // -------------------------------------------------------------------------
    // Save
    // -------------------------------------------------------------------------

    static void save(GameState initialState, List<TurnRecord> history,
                     String[] playerNames, Path path) throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        JsonObject root = new JsonObject();

        JsonArray names = new JsonArray();
        for (String n : playerNames) names.add(n);
        root.add("playerNames", names);

        root.add("initialSnapshot", serializeSnapshot(initialState));
        root.add("turns", serializeTurns(history));

        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            gson.toJson(root, w);
        }
    }

    // -------------------------------------------------------------------------
    // Load
    // -------------------------------------------------------------------------

    static GameSession load(Path path) throws IOException {
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();

            String[] names = parseNames(root.getAsJsonArray("playerNames"));
            GameState initialState = parseSnapshot(root.getAsJsonArray("initialSnapshot"), names);

            GameSession session = new GameSession(initialState, names);
            replayTurns(session, root.getAsJsonArray("turns"));
            return session;
        }
    }

    // -------------------------------------------------------------------------
    // Serialization helpers
    // -------------------------------------------------------------------------

    private static JsonArray serializeSnapshot(GameState initialState) {
        JsonArray snapshot = new JsonArray();
        for (Player p : initialState.getPlayers()) {
            JsonObject ps = new JsonObject();
            ps.addProperty("name", p.getName());
            ps.addProperty("coins", p.getCoins());
            JsonArray ownedIds = new JsonArray();
            for (Project proj : p.getOwned_projects()) ownedIds.add(proj.getId());
            ps.add("ownedIds", ownedIds);
            snapshot.add(ps);
        }
        return snapshot;
    }

    private static JsonArray serializeTurns(List<TurnRecord> history) {
        JsonArray turns = new JsonArray();
        for (TurnRecord t : history) {
            JsonObject turn = new JsonObject();
            turn.addProperty("playerIndex", t.playerIndex);
            turn.addProperty("roll", t.roll);
            if (t.bought != null) {
                turn.addProperty("boughtId", t.bought.getId());
            } else {
                turn.add("boughtId", com.google.gson.JsonNull.INSTANCE);
            }
            if (t.isDoubles) turn.addProperty("isDoubles", true);
            if (t.coinDeltas != null) {
                JsonArray da = new JsonArray();
                for (int d : t.coinDeltas) da.add(d);
                turn.add("coinDeltas", da);
            }
            if (t.swappedAway != null) turn.addProperty("swappedAway", t.swappedAway.getId());
            if (t.swappedIn   != null) turn.addProperty("swappedIn",   t.swappedIn.getId());
            if (t.swapOppPlayerIndex >= 0) turn.addProperty("swapOppPlayerIndex", t.swapOppPlayerIndex);
            if (t.diceCount != 1) turn.addProperty("diceCount", t.diceCount);
            turns.add(turn);
        }
        return turns;
    }

    // -------------------------------------------------------------------------
    // Deserialization helpers
    // -------------------------------------------------------------------------

    private static String[] parseNames(JsonArray namesArr) {
        String[] names = new String[namesArr.size()];
        for (int i = 0; i < names.length; i++) names[i] = namesArr.get(i).getAsString();
        return names;
    }

    private static GameState parseSnapshot(JsonArray snapshotArr, String[] names) {
        GameStateBuilder builder = new GameStateBuilder(names.length);
        for (int i = 0; i < names.length; i++) {
            JsonObject ps = snapshotArr.get(i).getAsJsonObject();
            builder.setPlayerName(i, ps.get("name").getAsString());
            builder.setCoins(i, ps.get("coins").getAsInt());
            for (JsonElement idEl : ps.getAsJsonArray("ownedIds")) {
                builder.addProject(i, idEl.getAsString());
            }
        }
        return builder.build();
    }

    private static void replayTurns(GameSession session, JsonArray turnsArr) {
        for (JsonElement el : turnsArr) {
            JsonObject t = el.getAsJsonObject();
            int pi   = t.get("playerIndex").getAsInt();
            int roll = t.get("roll").getAsInt();
            JsonElement boughtEl = t.get("boughtId");
            Project bought = null;
            if (boughtEl != null && !boughtEl.isJsonNull()) {
                String id = boughtEl.getAsString();
                bought = ProjectLoader.getProject(id)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Unknown project id in save file: " + id));
            }
            boolean isDoubles = t.has("isDoubles") && t.get("isDoubles").getAsBoolean();
            int[] coinDeltas = null;
            if (t.has("coinDeltas") && t.get("coinDeltas").isJsonArray()) {
                JsonArray da = t.getAsJsonArray("coinDeltas");
                coinDeltas = new int[da.size()];
                for (int i = 0; i < da.size(); i++) coinDeltas[i] = da.get(i).getAsInt();
            }
            Project swappedAway = null, swappedIn = null;
            if (t.has("swappedAway") && !t.get("swappedAway").isJsonNull()) {
                swappedAway = ProjectLoader.getProject(t.get("swappedAway").getAsString()).orElse(null);
            }
            if (t.has("swappedIn") && !t.get("swappedIn").isJsonNull()) {
                swappedIn = ProjectLoader.getProject(t.get("swappedIn").getAsString()).orElse(null);
            }
            int swapOppPlayerIndex = t.has("swapOppPlayerIndex") ? t.get("swapOppPlayerIndex").getAsInt() : -1;
            int diceCount = t.has("diceCount") ? t.get("diceCount").getAsInt() : 1;
            // Apply turn without swap data first (swap is a separate action on the state)
            session.applyTurn(new TurnRecord(pi, roll, bought, isDoubles, coinDeltas,
                    null, null, -1, diceCount));
            // Then replay the exact saved swap (not re-computing via greedy heuristic)
            if (swappedAway != null && swappedIn != null) {
                if (swapOppPlayerIndex >= 0) {
                    session.applyBürohausSwap(pi, swappedAway, swapOppPlayerIndex, swappedIn);
                } else {
                    // Legacy: no opponent index recorded, fall back to greedy
                    session.applyBürohausSwap(pi);
                }
            }
        }
    }
}
