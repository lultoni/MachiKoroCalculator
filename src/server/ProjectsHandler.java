package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import core.Project;
import core.ProjectLoader;

import java.io.IOException;
import java.util.ArrayList;

/**
 * GET /api/projects — returns all 19 base-game cards.
 *
 * <p>Response: JSON array of project objects, each with:
 * {@code id, name_de, name_en, color, category, cost, activationRolls, isGrossprojekt}
 */
final class ProjectsHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (ApiUtils.handleCors(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            ApiUtils.sendMethodNotAllowed(exchange, "GET");
            return;
        }

        ArrayList<Project> all = ProjectLoader.getAllProjects();
        JsonArray arr = new JsonArray();
        for (Project p : all) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", p.getId());
            obj.addProperty("name_de", p.getId().isEmpty() ? p.getId()
                    : Character.toUpperCase(p.getId().charAt(0)) + p.getId().substring(1));
            obj.addProperty("name_en", p.getNameEn());
            obj.addProperty("color", p.getColor());
            obj.addProperty("category", p.getCategory());
            obj.addProperty("cost", p.getCost());
            obj.addProperty("isGrossprojekt", p.isIs_grossprojekt());
            JsonArray rolls = new JsonArray();
            for (int r : p.getDice_activation()) rolls.add(r);
            obj.add("activationRolls", rolls);
            arr.add(obj);
        }

        ApiUtils.sendJson(exchange, 200, arr);
    }
}
