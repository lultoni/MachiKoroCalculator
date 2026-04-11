package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import core.CardIncome;
import core.Project;
import core.ProjectLoader;

import java.io.IOException;
import java.util.ArrayList;

/**
 * GET /api/projects — returns all 19 base-game cards.
 *
 * <p>Response: JSON array of project objects, each with:
 * {@code id, name_de, name_en, color, category, cost, activationRolls, isGrossprojekt, income_base}
 *
 * <p>{@code income_base} is the base coin income per activation with no synergies:
 * owner's perspective, no Einkaufszentrum, f_c=1, a_c=1, p_c=1, c=100, co=[2,2].
 * For Markthalle it uses f_c=1 (one food card), Molkerei uses a_c=1, Möbelfabrik uses p_c=1.
 * Red cards return the positive amount taken from the roller (negated for display).
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
            obj.addProperty("is_grossprojekt", p.isIs_grossprojekt());
            obj.addProperty("description_de", p.getDescription());
            obj.addProperty("description_en", p.getDescriptionEn());
            JsonArray rolls = new JsonArray();
            for (int r : p.getDice_activation()) rolls.add(r);
            obj.add("dice_activation", rolls);

            // income_base: base income per activation (owner perspective, no synergy bonuses)
            // Red cards: return the absolute amount taken (positive number)
            int incomeBase = 0;
            int[] activationRolls = p.getDice_activation();
            if (activationRolls.length > 0) {
                int r = activationRolls[0];
                int raw = CardIncome.get_I(r, p.getId(), true, false, 1, 1, 1, 100, new int[]{2, 2});
                if (raw == 0 && p.getColor().equals("rot")) {
                    // Red: query from roller's side (oop=false) and negate
                    int rollerCost = CardIncome.get_I(r, p.getId(), false, false, 1, 1, 1, 100, new int[]{2, 2});
                    incomeBase = -rollerCost;  // positive = coins taken from roller
                } else {
                    incomeBase = raw;
                }
            }
            obj.addProperty("income_base", incomeBase);

            arr.add(obj);
        }

        ApiUtils.sendJson(exchange, 200, arr);
    }
}
