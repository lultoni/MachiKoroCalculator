package logic.probability;

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

    public Project(String id, String category, boolean is_grossprojekt, int cost, int[] dice_activation,
                   String color, String description) {
        this.id = id;
        this.category = category;
        this.is_grossprojekt = is_grossprojekt;
        this.cost = cost;
        this.dice_activation = dice_activation;
        this.color = color;
        this.description = description;
    }

    public String getId() {
        return id;
    }

    public String getCategory() {
        return category;
    }

    public boolean isIs_grossprojekt() {
        return is_grossprojekt;
    }

    public int getCost() {
        return cost;
    }

    public int[] getDice_activation() {
        return dice_activation;
    }

    public String getColor() {
        return color;
    }

    public String getDescription() {
        return description;
    }

    /** Two projects are equal iff their ids are equal. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Project)) return false;
        return Objects.equals(id, ((Project) o).id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Project{id='" + id + "', color='" + color + "', cost=" + cost
                + ", dice=" + Arrays.toString(dice_activation) + "}";
    }
}