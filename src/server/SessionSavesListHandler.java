package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * GET /api/session/saves — lists all .mkoro save files in the saves directory.
 *
 * <h2>Response (200)</h2>
 * <pre>
 * {
 *   "saves": [
 *     {
 *       "filename":     "game1.mkoro",
 *       "lastModified": "2026-03-31T14:00:00",
 *       "playerNames":  ["Alice", "Bob"]
 *     }
 *   ]
 * }
 * </pre>
 *
 * <p>Returns an empty list if the saves directory does not exist.
 */
final class SessionSavesListHandler implements HttpHandler {

    private final SessionManager sessionManager;

    SessionSavesListHandler(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (ApiUtils.handleCors(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            ApiUtils.sendMethodNotAllowed(exchange, "GET");
            return;
        }

        try {
            Path savesDir = sessionManager.getSavesDir();
            JsonArray saves = new JsonArray();

            if (Files.isDirectory(savesDir)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(savesDir, "*.mkoro")) {
                    for (Path file : stream) {
                        JsonObject entry = new JsonObject();
                        entry.addProperty("filename", file.getFileName().toString());

                        Instant lastModified = Files.getLastModifiedTime(file).toInstant();
                        String isoTime = DateTimeFormatter.ISO_LOCAL_DATE_TIME
                                .format(lastModified.atZone(ZoneId.systemDefault()));
                        entry.addProperty("lastModified", isoTime);

                        // Attempt to read playerNames from the save file header
                        JsonArray playerNames = readPlayerNames(file);
                        if (playerNames != null) {
                            entry.add("playerNames", playerNames);
                        }

                        saves.add(entry);
                    }
                }
            }

            JsonObject response = new JsonObject();
            response.add("saves", saves);
            ApiUtils.sendJson(exchange, 200, response);

        } catch (Exception e) {
            ApiUtils.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    /**
     * Reads the "playerNames" field from a .mkoro JSON file without fully parsing
     * the entire save. Returns null if the field cannot be read.
     */
    private static JsonArray readPlayerNames(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();
                if (obj.has("playerNames") && obj.get("playerNames").isJsonArray()) {
                    return obj.getAsJsonArray("playerNames");
                }
            }
        } catch (Exception ignored) {
            // If the file is corrupt or unreadable, just skip playerNames
        }
        return null;
    }
}
