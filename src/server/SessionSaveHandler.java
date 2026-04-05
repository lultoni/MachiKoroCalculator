package server;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import core.GameSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * POST /api/session/save — saves the active session to a .mkoro file.
 *
 * <h2>Request body</h2>
 * <pre>
 * { "filename": "my-game" }   // optional; auto-generates if absent
 * </pre>
 *
 * <h2>Response (200)</h2>
 * <pre>
 * { "path": "my-game.mkoro" }
 * </pre>
 */
final class SessionSaveHandler implements HttpHandler {

    private final SessionManager sessionManager;

    SessionSaveHandler(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (ApiUtils.handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            ApiUtils.sendMethodNotAllowed(exchange, "POST");
            return;
        }

        GameSession session = sessionManager.getSession();
        if (session == null) {
            ApiUtils.sendError(exchange, 404, "No active session");
            return;
        }

        JsonObject body;
        try {
            body = ApiUtils.parseBody(exchange);
        } catch (Exception e) {
            // Empty body is acceptable (auto-generate filename)
            body = new JsonObject();
        }

        try {
            String filename;
            if (body != null && body.has("filename")
                    && !body.get("filename").isJsonNull()
                    && !body.get("filename").getAsString().isBlank()) {
                filename = body.get("filename").getAsString().strip();
            } else {
                filename = generateFilename(session);
            }

            // Ensure the filename does not already include the extension
            if (!filename.endsWith(".mkoro")) {
                filename = filename + ".mkoro";
            }

            Path savesDir = sessionManager.getSavesDir();
            Files.createDirectories(savesDir);
            Path savePath = savesDir.resolve(filename);
            session.save(savePath);

            JsonObject response = new JsonObject();
            response.addProperty("path", filename);
            ApiUtils.sendJson(exchange, 200, response);

        } catch (Exception e) {
            ApiUtils.sendError(exchange, 500, "Save failed: " + e.getMessage());
        }
    }

    private static String generateFilename(GameSession session) {
        String[] names = session.getPlayerNames();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if (i > 0) sb.append("-vs-");
            sb.append(sanitize(names[i]));
        }
        sb.append("_");
        sb.append(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
        return sb.toString();
    }

    /** Replaces non-alphanumeric characters with underscores for safe filenames. */
    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9]", "_");
    }
}
