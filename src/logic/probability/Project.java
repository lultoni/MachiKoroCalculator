package logic.probability;

import gui.newui.Strings;
import java.util.Arrays;
import java.util.Objects;

public class Project {

    private final String id;
    private final String category;
    private final boolean is_grossprojekt;
    private final int cost;
    private final int[] dice_activation;
    private final String color;
    private final String description;
    private final String name_en;
    private final String description_en;

    public Project(String id, String category, boolean is_grossprojekt, int cost, int[] dice_activation,
                   String color, String description, String name_en, String description_en) {
        this.id = id;
        this.category = category;
        this.is_grossprojekt = is_grossprojekt;
        this.cost = cost;
        this.dice_activation = dice_activation;
        this.color = color;
        this.description = description;
        this.name_en = name_en != null ? name_en : id;
        this.description_en = description_en != null ? description_en : description;
    }

    public String getId() { return id; }
    public String getCategory() { return category; }
    public boolean isIs_grossprojekt() { return is_grossprojekt; }
    public int getCost() { return cost; }
    public int[] getDice_activation() { return dice_activation; }
    public String getColor() { return color; }
    public String getDescription() { return description; }
    public String getNameEn() { return name_en; }
    public String getDescriptionEn() { return description_en; }

    /**
     * Returns the card's display name in the currently active locale.
     * German: the capitalized id (original game label). English: the official English name.
     */
    public String getLocalizedName() {
        if (Strings.isDE()) {
            // German: capitalize the first letter of the ID
            return id.isEmpty() ? id : Character.toUpperCase(id.charAt(0)) + id.substring(1);
        }
        return name_en;
    }

    /**
     * Returns the card's description in the currently active locale.
     */
    public String getLocalizedDescription() {
        return Strings.isDE() ? description : description_en;
    }

    /** Two projects are equal iff their ids are equal. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Project)) return false;
        return Objects.equals(id, ((Project) o).id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }

    @Override
    public String toString() {
        return "Project{id='" + id + "', color='" + color + "', cost=" + cost
                + ", dice=" + Arrays.toString(dice_activation) + "}";
    }
}
