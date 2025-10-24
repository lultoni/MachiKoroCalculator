package logic.probability;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Paths;

/**
 * Diese Klasse ist die Schnittstelle zwischen der src/resources/jsons/projects.json und dem Rest des Programms.
 */
public class ProjectLoader {

    /**
     * Läd aus der json-Datei das gewünschte Objekt.
     * @param p_id ID vom Projekt, welches ausgegeben werden soll.
     * @return Objekt des angegebenen Projektes.
     */
    public static Project getProject(String p_id) {
        Gson gson = new Gson();

        // Pfad zur JSON-Datei
        String path = Paths.get("src", "resources", "jsons", "projects.json").toString();

        try (FileReader reader = new FileReader(path)) {
            JsonObject jsonRoot = gson.fromJson(reader, JsonObject.class);

            // Prüfen, ob das gewünschte Projekt existiert
            JsonElement projectElement = jsonRoot.get(p_id);
            if (projectElement == null || !projectElement.isJsonObject()) {
                System.err.println("Projekt-ID '" + p_id + "' nicht gefunden.");
                return null;
            }

            // In Project-Objekt umwandeln
            return gson.fromJson(projectElement, Project.class);

        } catch (IOException e) {
            System.err.println("Fehler beim Laden der Datei: " + e.getMessage());
            return null;
        }
    }

}
