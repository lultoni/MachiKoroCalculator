package Tests;

import logic.probability.GameState;
import logic.probability.Player;
import logic.probability.Project;
import logic.probability.ProjectLoader;

import java.util.ArrayList;
import java.util.Optional;

public class RuntimeTester {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("\n=== Phase 1 Model Tests ===\n");

        test_project_loader_count();
        test_project_loader_known_project();
        test_project_loader_unknown_project();
        test_project_loader_cache_is_fast();
        test_player_copy();
        test_game_state_initial();
        test_game_state_copy_is_independent();

        System.out.println("\n--- Results: " + passed + " passed, " + failed + " failed ---");

        System.out.println("\n=== Runtime Benchmarks ===\n");

        System.out.println("Benchmark: ProjectLoader.getProject (cached)");
        for (int i = 1; i <= 100000; i *= 10) {
            System.out.println(" - Runs: " + i);
            long start = System.currentTimeMillis();
            for (int j = 0; j < i; j++) ProjectLoader.getProject("stadion");
            System.out.println("  | runtime = " + (System.currentTimeMillis() - start) + " ms");
        }

        System.out.println("\nBenchmark: ProjectLoader.getAllProjects()");
        for (int i = 1; i <= 10000; i *= 10) {
            System.out.println(" - Runs: " + i);
            long start = System.currentTimeMillis();
            for (int j = 0; j < i; j++) ProjectLoader.getAllProjects();
            System.out.println("  | runtime = " + (System.currentTimeMillis() - start) + " ms");
        }
    }

    // -------------------------------------------------------------------------
    // Phase 1 tests
    // -------------------------------------------------------------------------

    private static void test_project_loader_count() {
        ArrayList<Project> all = ProjectLoader.getAllProjects();
        assertEq("ProjectLoader.getAllProjects() returns 19 projects", 19, all.size());
    }

    private static void test_project_loader_known_project() {
        Optional<Project> opt = ProjectLoader.getProject("stadion");
        assertTrue("getProject(stadion) is present", opt.isPresent());
        assertEq("stadion cost", 6, opt.get().getCost());
        assertEq("stadion color", "lila", opt.get().getColor());
    }

    private static void test_project_loader_unknown_project() {
        Optional<Project> opt = ProjectLoader.getProject("does_not_exist");
        assertTrue("getProject(unknown) returns empty", opt.isEmpty());
    }

    private static void test_project_loader_cache_is_fast() {
        // After first load, 10 000 lookups must complete in under 50 ms.
        long start = System.currentTimeMillis();
        for (int i = 0; i < 10000; i++) ProjectLoader.getProject("weizenfeld");
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("10 000 cached getProject calls < 50 ms (was " + elapsed + " ms)", elapsed < 50);
    }

    private static void test_player_copy() {
        ArrayList<Project> owned = new ArrayList<>();
        ProjectLoader.getProject("weizenfeld").ifPresent(owned::add);
        Player original = new Player("Alice", 5, owned);
        Player copy = original.copy();

        assertTrue("copy has same name", original.getName().equals(copy.getName()));
        assertEq("copy has same coins", original.getCoins(), copy.getCoins());
        assertTrue("copy list is a different object", original.getOwned_projects() != copy.getOwned_projects());
        assertEq("copy list has same size", original.getOwned_projects().size(), copy.getOwned_projects().size());

        // Mutating copy coins must not affect original
        copy.setCoins(99);
        assertEq("original coins unchanged after copy mutation", 5, original.getCoins());
    }

    private static void test_game_state_initial() {
        GameState gs = GameState.initial(4);
        assertEq("initial state has 4 players", 4, gs.getPlayers().length);
        for (Player p : gs.getPlayers()) {
            assertEq(p.getName() + " starts with 3 coins", 3, p.getCoins());
            assertEq(p.getName() + " starts with 2 projects", 2, p.getOwned_projects().size());
            assertTrue(p.getName() + " owns weizenfeld", p.hasProject("weizenfeld"));
            assertTrue(p.getName() + " owns bäckerei", p.hasProject("bäckerei"));
        }
        assertEq("unbuilt pool has 17 projects", 17, gs.getUnbuilt_projects().size());
    }

    private static void test_game_state_copy_is_independent() {
        GameState gs = GameState.initial(2);
        GameState copy = gs.copy();

        // Modifying copy's player coins must not affect original
        copy.getPlayers()[0].setCoins(999);
        assertEq("original player 0 coins unaffected by copy mutation", 3, gs.getPlayers()[0].getCoins());

        // Adding to copy's unbuilt list must not affect original
        int origSize = gs.getUnbuilt_projects().size();
        copy.getUnbuilt_projects().add(ProjectLoader.getProject("bergwerk").orElseThrow());
        assertEq("original unbuilt list size unaffected by copy mutation", origSize, gs.getUnbuilt_projects().size());
    }

    // -------------------------------------------------------------------------
    // Assertion helpers
    // -------------------------------------------------------------------------

    private static void assertTrue(String label, boolean condition) {
        if (condition) {
            System.out.println("  PASS: " + label);
            passed++;
        } else {
            System.out.println("  FAIL: " + label);
            failed++;
        }
    }

    private static void assertEq(String label, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            System.out.println("  PASS: " + label);
            passed++;
        } else {
            System.out.println("  FAIL: " + label + " — expected [" + expected + "] got [" + actual + "]");
            failed++;
        }
    }
}
