package server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Shared utilities for HTTP handlers: JSON serialization, response writing, error helpers.
 */
final class ApiUtils {

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ApiUtils() {}

    // -------------------------------------------------------------------------
    // Response helpers
    // -------------------------------------------------------------------------

    /**
     * Writes a JSON response with the given status code.
     *
     * @param exchange the HTTP exchange
     * @param status   HTTP status code (200, 400, 404, 405, 500, etc.)
     * @param body     object to serialize as JSON (may be a JsonObject, POJO, etc.)
     */
    static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Sends a JSON error response: {@code {"error": message}}. */
    static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", message);
        sendJson(exchange, status, obj);
    }

    /** Sends 405 Method Not Allowed with an Allow header. */
    static void sendMethodNotAllowed(HttpExchange exchange, String... allowed) throws IOException {
        exchange.getResponseHeaders().set("Allow", String.join(", ", allowed));
        sendError(exchange, 405, "Method not allowed: " + exchange.getRequestMethod());
    }

    /** Handles CORS preflight OPTIONS requests. */
    static boolean handleCors(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Request helpers
    // -------------------------------------------------------------------------

    /** Reads the request body as a UTF-8 string. Returns empty string if no body. */
    static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody();
             InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) sb.append(buf, 0, n);
            return sb.toString();
        }
    }

    /** Parses the request body as a JsonObject. Throws if the body is not valid JSON. */
    static JsonObject parseBody(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        return GSON.fromJson(body, JsonObject.class);
    }
}
