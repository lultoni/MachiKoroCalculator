package server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serves static files from {@code web/dist/} at the root path {@code /}.
 *
 * <p>SPA fallback: if the requested path does not match a file, serves {@code index.html}
 * instead (allowing client-side routing to handle the URL).
 *
 * <p>Only activates if {@code web/dist/index.html} exists at server start time.
 */
final class StaticFileHandler implements HttpHandler {

    private final Path basePath;

    /**
     * @param basePath the directory to serve files from (e.g. {@code web/dist/})
     */
    StaticFileHandler(Path basePath) {
        this.basePath = basePath;
    }

    /** Returns true if the base path contains an {@code index.html} file. */
    static boolean isAvailable(Path basePath) {
        return Files.exists(basePath.resolve("index.html"));
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (ApiUtils.handleCors(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            ApiUtils.sendMethodNotAllowed(exchange, "GET");
            return;
        }

        String requestPath = exchange.getRequestURI().getPath();

        // Strip leading slash and normalize
        if (requestPath.startsWith("/")) {
            requestPath = requestPath.substring(1);
        }
        if (requestPath.isEmpty()) {
            requestPath = "index.html";
        }

        // Security: prevent path traversal
        Path resolved = basePath.resolve(requestPath).normalize();
        if (!resolved.startsWith(basePath)) {
            ApiUtils.sendError(exchange, 403, "Forbidden");
            return;
        }

        // If the file doesn't exist, fall back to index.html (SPA routing)
        if (!Files.exists(resolved) || Files.isDirectory(resolved)) {
            resolved = basePath.resolve("index.html");
        }

        if (!Files.exists(resolved)) {
            ApiUtils.sendError(exchange, 404, "Not found");
            return;
        }

        byte[] content = Files.readAllBytes(resolved);
        String contentType = guessContentType(resolved.getFileName().toString());

        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(content);
        }
    }

    private static String guessContentType(String filename) {
        if (filename.endsWith(".html"))       return "text/html; charset=utf-8";
        if (filename.endsWith(".js"))         return "application/javascript; charset=utf-8";
        if (filename.endsWith(".css"))        return "text/css; charset=utf-8";
        if (filename.endsWith(".json"))       return "application/json; charset=utf-8";
        if (filename.endsWith(".svg"))        return "image/svg+xml";
        if (filename.endsWith(".png"))        return "image/png";
        if (filename.endsWith(".ico"))        return "image/x-icon";
        if (filename.endsWith(".jpg")
                || filename.endsWith(".jpeg")) return "image/jpeg";
        if (filename.endsWith(".woff"))       return "font/woff";
        if (filename.endsWith(".woff2"))      return "font/woff2";
        if (filename.endsWith(".ttf"))        return "font/ttf";
        if (filename.endsWith(".map"))        return "application/json";
        return "application/octet-stream";
    }
}
