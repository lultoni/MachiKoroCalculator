package Tests;

import logic.probability.Project;
import logic.probability.ProjectLoader;

public class RuntimeTester {

    public static void main(String[] args) {
        System.out.println("\n--- Runtime Tests Start ---");


        System.out.println("Current Test: ProjectLoader.getProject");
        for (int i = 1; i <= 100000; i *= 10) {
            System.out.println(" - Runs of Function: " + i);
            long start_time = System.currentTimeMillis();
            for (int j = 0; j <= i; j++) test_project_loader_get_project();
            long end_time = System.currentTimeMillis();
            System.out.println("  | runtime = " + millis_time_to_string(start_time, end_time));
        }

        System.out.println("\n--- Runtime Tests End ---");
    }

    private static void test_project_loader_get_project() {
        Project project = ProjectLoader.getProject("stadion");
    }

    private static String millis_time_to_string(long start, long end) {
        return Math.abs(end - start) + " ms";
    }

}
