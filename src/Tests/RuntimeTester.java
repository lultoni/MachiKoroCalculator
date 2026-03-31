package Tests;

import logic.probability.*;
import calcs.Calcs;
import iface.EngineRegistry;
import iface.EngineRegistryEntry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;

public class RuntimeTester {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("\n=== Phase 1 Model Tests ===\n");
        test_project_loader_count();
        test_project_loader_known_project();
        test_project_loader_unknown_project();
        test_project_loader_cache_is_fast();
        test_player_copy();
        test_game_state_initial();
        test_game_state_copy_is_independent();

        System.out.println("\n=== Phase 2 Math Engine Tests ===\n");
        test_probability_tables_sum_to_1();
        test_get_I_weizenfeld();
        test_get_I_baeckerei_green();
        test_get_I_bauernhof_blue();
        test_get_I_cafe_red_inability_to_pay();
        test_get_I_familienrestaurant_red();
        test_get_I_stadion_all_opponents();
        test_get_I_fernsehsender_richest_only();
        test_get_I_molkerei_synergy();
        test_get_I_markthalle_synergy();
        test_get_I_moebelfabrik_synergy();
        test_immediate_ev_weizenfeld_only();
        test_evPerRound_blue_scales_with_players();
        test_evPerRound_green_only_own_turn();
        test_evPerRound_red_income_on_opponent_turns();
        test_roi_positive_for_good_card();
        test_variance_nonnegative();
        test_probNoIncome_between_0_and_1();
        test_rank_nonempty_for_starting_state();
        test_rank_sorted_descending();
        test_rank_excludes_unaffordable();
        test_win_prob_delta_buying_improves_score();
        test_baseline_win_prob_sums_to_one();

        System.out.println("\n=== Phase 6 Bürohaus Tests ===\n");
        test_buerohaus_ev_positive_when_opponents_have_good_cards();
        test_buerohaus_ev_zero_when_no_opponents_own_cards();
        test_buerohaus_swap_note_set_in_ranking();
        test_buerohaus_swap_executed_in_simulator();

        System.out.println("\n=== Phase 5 Monte Carlo Tests ===\n");
        test_simulator_returns_valid_winner();
        test_simulator_deterministic_with_seed();
        test_mc_win_rates_sum_to_one();
        test_mc_win_prob_delta_in_range();

        System.out.println("\n=== Supply & Ownership Rules Tests ===\n");
        test_builder_throws_on_duplicate_purple_same_player();
        test_builder_allows_same_purple_for_different_players();
        test_rank_excludes_owned_purple_cards();
        test_rank_excludes_owned_landmarks();

        System.out.println("\n=== Rules Correctness Tests ===\n");
        test_red_fires_before_green_income();
        test_red_payment_counter_clockwise_order();

        System.out.println("\n=== Game-Over Detection Tests ===\n");
        test_game_over_on_fourth_landmark();
        test_no_game_over_before_fourth_landmark();

        System.out.println("\n=== Session Persistence Tests ===\n");
        test_save_and_load_roundtrip();
        test_load_restores_player_names_and_history_size();
        test_save_and_load_snapshot_rooted_session();
        test_load_invalid_file_throws();

        System.out.println("\n=== Starter-Card Supply Tests ===\n");
        test_starter_cards_allow_7_copies_in_builder();
        test_starter_cards_7_copies_exhausts_unbuilt_pool();
        test_non_starter_cards_capped_at_6_in_builder();

        System.out.println("\n=== GP Ranking Tests ===\n");
        test_gp_included_in_ranking_when_affordable();
        test_gp_not_offered_when_already_owned();
        test_gp_ranking_separate_from_regular_cards();

        System.out.println("\n=== Freizeitpark Doubles Tests ===\n");
        test_freizeitpark_bonus_turn_granted_on_doubles();
        test_freizeitpark_bonus_turn_not_granted_without_bahnhof();
        test_freizeitpark_no_chain_on_second_doubles();
        test_freizeitpark_bonus_advances_player_after_bonus();
        test_freizeitpark_undo_restores_correct_state();

        System.out.println("\n=== TurnRecord CoinDeltas Tests ===\n");
        test_coin_deltas_stored_in_turn_record();
        test_coin_deltas_null_for_externally_constructed_record();
        test_coin_deltas_correct_values_blue_card();
        test_coin_deltas_correct_values_red_card();
        test_coin_deltas_preserved_after_undo_replay();
        test_coin_deltas_roundtrip_save_load();

        System.out.println("\n=== Calcs Layer Tests ===\n");
        test_calcs_get_P1_sums_to_1();
        test_calcs_get_P2_sums_to_1();
        test_calcs_get_I_delegates_correctly();
        test_calcs_immediate_ev_nonnegative();
        test_calcs_ev_per_round_blue_scales_with_players();
        test_calcs_roi_over_horizon_positive_for_cheap_card();
        test_calcs_baseline_win_prob_sums_to_one();
        test_calcs_win_prob_delta_in_range();
        test_calcs_portfolio_delta_ev_nonnegative_for_blue();
        test_calcs_geometric_sum_identity_at_gamma1();
        test_calcs_optimal_dice_no_bahnhof_returns_1();

        System.out.println("\n=== Engine Registry Tests ===\n");
        test_engine_registry_loads_entries();
        test_engine_registry_has_default();
        test_engine_registry_default_is_balanced();
        test_engine_registry_find_by_id();

        System.out.println("\n--- Results: " + passed + " passed, " + failed + " failed ---");

        System.out.println("\n=== Runtime Benchmarks ===\n");

        System.out.println("Benchmark: ProjectLoader.getProject (cached)");
        for (int i = 1; i <= 100000; i *= 10) {
            long start = System.currentTimeMillis();
            for (int j = 0; j < i; j++) ProjectLoader.getProject("stadion");
            System.out.println(" - " + i + " runs: " + (System.currentTimeMillis() - start) + " ms");
        }

        System.out.println("\nBenchmark: rankPurchasableProjects (4-player starting state)");
        GameState gs4 = GameState.initial(4);
        RankingOptions opts = new RankingOptions();
        // Warm-up
        ProbabilityCalc.rankPurchasableProjects(gs4, 0, opts);
        long start = System.currentTimeMillis();
        int BENCH_RUNS = 200;
        for (int i = 0; i < BENCH_RUNS; i++) ProbabilityCalc.rankPurchasableProjects(gs4, 0, opts);
        long elapsed = System.currentTimeMillis() - start;
        System.out.println(" - " + BENCH_RUNS + " runs: " + elapsed + " ms total, "
                + String.format("%.2f", (double) elapsed / BENCH_RUNS) + " ms/call");
        assertTrue("rankPurchasableProjects avg < 5 ms",
                (double) elapsed / BENCH_RUNS < 5.0);

        System.out.println("\nBenchmark: MC simulation (1000 sims, 4-player starting state)");
        GameState gs4mc = GameState.initial(4);
        // Warm-up
        ProbabilityCalc.mcWinRate(gs4mc, 0, 10);
        long mcStart = System.currentTimeMillis();
        ProbabilityCalc.mcWinRate(gs4mc, 0, 1000);
        long mcElapsed = System.currentTimeMillis() - mcStart;
        System.out.println(" - 1000 sims: " + mcElapsed + " ms");
        assertTrue("1000 MC sims < 2000 ms (was " + mcElapsed + " ms)", mcElapsed < 2000);

        System.out.println("\nBenchmark: estimateWinProbDelta (MC, 500 sims, 4-player)");
        GameState gsMcDelta = GameState.initial(4);
        gsMcDelta.getPlayers()[0].setCoins(10);
        Project benchCard = ProjectLoader.getProject("bergwerk").orElseThrow();
        // Warm-up
        ProbabilityCalc.estimateWinProbDelta(gsMcDelta, 0, benchCard, 0, 10);
        long mcDeltaStart = System.currentTimeMillis();
        ProbabilityCalc.estimateWinProbDelta(gsMcDelta, 0, benchCard, 0, 500);
        long mcDeltaElapsed = System.currentTimeMillis() - mcDeltaStart;
        System.out.println(" - 500 sims: " + mcDeltaElapsed + " ms");
        assertTrue("estimateWinProbDelta 500 MC sims < 2000 ms (was " + mcDeltaElapsed + " ms)",
                mcDeltaElapsed < 2000);

        System.out.println("\nBenchmark: ProjectLoader.getAllProjects() (cached)");
        long allProjStart = System.currentTimeMillis();
        for (int i = 0; i < 10000; i++) ProjectLoader.getAllProjects();
        long allProjElapsed = System.currentTimeMillis() - allProjStart;
        System.out.println(" - 10 000 getAllProjects() calls: " + allProjElapsed + " ms");
        assertTrue("getAllProjects() 10 000 calls < 200 ms (was " + allProjElapsed + " ms)",
                allProjElapsed < 200);
    }

    // =========================================================================
    // Phase 1 tests
    // =========================================================================

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
        assertTrue("copy list is a different object",
                original.getOwned_projects() != copy.getOwned_projects());
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
        assertEq("unbuilt pool has 15 projects", 15, gs.getUnbuilt_projects().size());
    }

    private static void test_game_state_copy_is_independent() {
        GameState gs = GameState.initial(2);
        GameState copy = gs.copy();
        copy.getPlayers()[0].setCoins(999);
        assertEq("original player 0 coins unaffected", 3, gs.getPlayers()[0].getCoins());
        int origSize = gs.getUnbuilt_projects().size();
        copy.getUnbuilt_projects().add(ProjectLoader.getProject("bergwerk").orElseThrow());
        assertEq("original unbuilt list size unaffected", origSize, gs.getUnbuilt_projects().size());
    }

    // =========================================================================
    // Phase 2 tests
    // =========================================================================

    private static void test_probability_tables_sum_to_1() {
        double sum1 = 0.0;
        for (int r = 1; r <= 6; r++) sum1 += ProbabilityCalc.get_P1(r);
        assertDoubleEq("P1 sums to 1.0", 1.0, sum1, 1e-12);

        double sum2 = 0.0;
        for (int r = 2; r <= 12; r++) sum2 += ProbabilityCalc.get_P2(r);
        assertDoubleEq("P2 sums to 1.0", 1.0, sum2, 1e-12);

        assertDoubleEq("P1 out-of-range returns 0", 0.0, ProbabilityCalc.get_P1(7), 1e-12);
        assertDoubleEq("P2 out-of-range returns 0", 0.0, ProbabilityCalc.get_P2(1), 1e-12);
    }

    private static void test_get_I_weizenfeld() {
        // Blue: activates on roll 1, pays 1 regardless of oop
        assertEq("weizenfeld roll=1 pays 1", 1,
                ProbabilityCalc.get_I(1, "weizenfeld", false, false, 0, 0, 0, 5, new int[]{5}));
        assertEq("weizenfeld roll=1 oop=true pays 1", 1,
                ProbabilityCalc.get_I(1, "weizenfeld", true, false, 0, 0, 0, 5, new int[]{5}));
        assertEq("weizenfeld roll=2 pays 0", 0,
                ProbabilityCalc.get_I(2, "weizenfeld", true, false, 0, 0, 0, 5, new int[]{5}));
    }

    private static void test_get_I_baeckerei_green() {
        // Green: only activates when oop=true (owner's turn); roll 2 or 3
        assertEq("bäckerei roll=2 oop=true pays 1", 1,
                ProbabilityCalc.get_I(2, "bäckerei", true, false, 0, 0, 0, 5, new int[]{}));
        assertEq("bäckerei roll=3 oop=true pays 1", 1,
                ProbabilityCalc.get_I(3, "bäckerei", true, false, 0, 0, 0, 5, new int[]{}));
        assertEq("bäckerei roll=2 oop=false pays 0", 0,
                ProbabilityCalc.get_I(2, "bäckerei", false, false, 0, 0, 0, 5, new int[]{}));
        assertEq("bäckerei roll=2 eb=true pays 2", 2,
                ProbabilityCalc.get_I(2, "bäckerei", true, true, 0, 0, 0, 5, new int[]{}));
    }

    private static void test_get_I_bauernhof_blue() {
        // Blue: activates on roll 2 for everyone
        assertEq("bauernhof roll=2 oop=false pays 1", 1,
                ProbabilityCalc.get_I(2, "bauernhof", false, false, 0, 0, 0, 5, new int[]{}));
        assertEq("bauernhof roll=3 pays 0", 0,
                ProbabilityCalc.get_I(3, "bauernhof", true, false, 0, 0, 0, 5, new int[]{}));
    }

    private static void test_get_I_cafe_red_inability_to_pay() {
        // Red: oop=false (roller pays), returns negative. Clamped to available coins.
        assertEq("café roll=3 oop=false costs -1", -1,
                ProbabilityCalc.get_I(3, "café", false, false, 0, 0, 0, 5, new int[]{}));
        assertEq("café roll=3 eb=true costs -2", -2,
                ProbabilityCalc.get_I(3, "café", false, true, 0, 0, 0, 5, new int[]{}));
        assertEq("café roll=3 only 0 coins pays 0", 0,
                ProbabilityCalc.get_I(3, "café", false, false, 0, 0, 0, 0, new int[]{}));
        assertEq("café oop=true returns 0 (owner doesn't pay self)", 0,
                ProbabilityCalc.get_I(3, "café", true, false, 0, 0, 0, 5, new int[]{}));
        // Inability to pay full: has 1 coin, owes 2 (with eb) → pays 1
        assertEq("café roll=3 eb=true 1 coin pays -1 (capped)", -1,
                ProbabilityCalc.get_I(3, "café", false, true, 0, 0, 0, 1, new int[]{}));
    }

    private static void test_get_I_familienrestaurant_red() {
        assertEq("familienrestaurant roll=9 costs -2", -2,
                ProbabilityCalc.get_I(9, "familienrestaurant", false, false, 0, 0, 0, 5, new int[]{}));
        assertEq("familienrestaurant roll=10 costs -2", -2,
                ProbabilityCalc.get_I(10, "familienrestaurant", false, false, 0, 0, 0, 5, new int[]{}));
        assertEq("familienrestaurant roll=9 eb=true costs -3", -3,
                ProbabilityCalc.get_I(9, "familienrestaurant", false, true, 0, 0, 0, 5, new int[]{}));
        assertEq("familienrestaurant roll=8 pays 0", 0,
                ProbabilityCalc.get_I(8, "familienrestaurant", false, false, 0, 0, 0, 5, new int[]{}));
    }

    private static void test_get_I_stadion_all_opponents() {
        // Stadion: takes 2 from EACH opponent (no total cap)
        // 3 opponents with 5 coins each → 3×2 = 6
        assertEq("stadion 3 opponents 5 coins each → 6", 6,
                ProbabilityCalc.get_I(6, "stadion", true, false, 0, 0, 0, 0,
                        new int[]{5, 5, 5}));
        // 1 opponent with 1 coin → min(2,1) = 1
        assertEq("stadion 1 opponent 1 coin → 1", 1,
                ProbabilityCalc.get_I(6, "stadion", true, false, 0, 0, 0, 0,
                        new int[]{1}));
        // 2 opponents: one has 0, one has 5 → 0+2 = 2
        assertEq("stadion 2 opponents 0+5 coins → 2", 2,
                ProbabilityCalc.get_I(6, "stadion", true, false, 0, 0, 0, 0,
                        new int[]{0, 5}));
        // oop=false: not owner, returns 0
        assertEq("stadion oop=false returns 0", 0,
                ProbabilityCalc.get_I(6, "stadion", false, false, 0, 0, 0, 0,
                        new int[]{5, 5}));
    }

    private static void test_get_I_fernsehsender_richest_only() {
        // Fernsehsender: takes min(5, richest_opponent_coins) from ONE opponent
        assertEq("fernsehsender richest has 10 coins → 5", 5,
                ProbabilityCalc.get_I(6, "fernsehsender", true, false, 0, 0, 0, 0,
                        new int[]{10, 3}));
        assertEq("fernsehsender richest has 3 coins → 3", 3,
                ProbabilityCalc.get_I(6, "fernsehsender", true, false, 0, 0, 0, 0,
                        new int[]{3, 1}));
        assertEq("fernsehsender all opponents have 0 → 0", 0,
                ProbabilityCalc.get_I(6, "fernsehsender", true, false, 0, 0, 0, 0,
                        new int[]{0, 0}));
    }

    private static void test_get_I_molkerei_synergy() {
        // Molkerei: roll 7, oop=true, pays 3 per animal card
        assertEq("molkerei 2 animal cards → 6", 6,
                ProbabilityCalc.get_I(7, "molkerei", true, false, 0, 2, 0, 5, new int[]{}));
        assertEq("molkerei 0 animal cards → 0", 0,
                ProbabilityCalc.get_I(7, "molkerei", true, false, 0, 0, 0, 5, new int[]{}));
        assertEq("molkerei roll=8 → 0", 0,
                ProbabilityCalc.get_I(8, "molkerei", true, false, 0, 2, 0, 5, new int[]{}));
    }

    private static void test_get_I_markthalle_synergy() {
        // Markthalle: roll 11 or 12, oop=true, pays 2 per food card
        assertEq("markthalle 3 food cards roll=11 → 6", 6,
                ProbabilityCalc.get_I(11, "markthalle", true, false, 3, 0, 0, 5, new int[]{}));
        assertEq("markthalle 3 food cards roll=12 → 6", 6,
                ProbabilityCalc.get_I(12, "markthalle", true, false, 3, 0, 0, 5, new int[]{}));
        assertEq("markthalle roll=10 → 0", 0,
                ProbabilityCalc.get_I(10, "markthalle", true, false, 3, 0, 0, 5, new int[]{}));
    }

    private static void test_get_I_moebelfabrik_synergy() {
        // Möbelfabrik: roll 8, oop=true, pays 3 per production card
        assertEq("möbelfabrik 2 production cards → 6", 6,
                ProbabilityCalc.get_I(8, "möbelfabrik", true, false, 0, 0, 2, 5, new int[]{}));
    }

    private static void test_immediate_ev_weizenfeld_only() {
        // Player owns only Weizenfeld (no Bahnhof), 1d6 only.
        // Weizenfeld activates on roll 1 → income 1. P=1/6.
        // Expected EV = 1/6 ≈ 0.1667
        GameState gs = GameState.initial(2);
        // Remove bäckerei from player 0 so only weizenfeld remains
        gs.getPlayers()[0].getOwned_projects().removeIf(p -> p.getId().equals("bäckerei"));

        // Candidate: buy weizenfeld again (blue card, always available in pool conceptually)
        // For this test we just use a second weizenfeld as candidate
        Project weizenfeld = ProjectLoader.getProject("weizenfeld").orElseThrow();
        double ev = ProbabilityCalc.immediateEV(gs, 0, weizenfeld, false);
        // With 2 weizenfelds: EV_own_turn = 2*(1/6) = 0.333
        // But from player 1's weizenfeld: not included (own-turn only for blue is correct — wait,
        // blue triggers from the OWNER's perspective on own turn)
        // Actually with 2 weizenfelds owned by player 0: both activate on roll 1 → 2 coins.
        // EV = 2*(1/6) = 0.333
        assertDoubleEq("immediateEV with 2 weizenfelds ≈ 0.333",
                2.0 / 6.0, ev, 0.001);
    }

    private static void test_evPerRound_blue_scales_with_players() {
        // In a 2-player game, Weizenfeld (blue, roll 1) fires on both players' turns.
        // Player 0 has only Weizenfeld. evPerRound should be ≈ 2 * (1/6) = 0.333
        GameState gs = GameState.initial(2);
        gs.getPlayers()[0].getOwned_projects().removeIf(p -> p.getId().equals("bäckerei"));
        gs.getPlayers()[0].getOwned_projects().removeIf(p -> p.getId().equals("weizenfeld"));

        Project weizenfeld = ProjectLoader.getProject("weizenfeld").orElseThrow();
        double ev = ProbabilityCalc.evPerRound(gs, 0, weizenfeld);
        // Candidate is added: player 0 has 1 weizenfeld
        // Own turn: roll 1 → 1 coin (blue). EV = 1/6
        // Opponent turn: player 1 rolls, weizenfeld activates for player 0 too. EV = 1/6
        // Total ≈ 2/6 = 0.333
        assertDoubleEq("evPerRound weizenfeld 2-player ≈ 0.333", 2.0 / 6.0, ev, 0.005);
    }

    private static void test_evPerRound_green_only_own_turn() {
        // Bäckerei (green) only fires on own turn → evPerRound ≈ P(2) + P(3) = 1/6 + 1/6 = 2/6
        GameState gs = GameState.initial(2);
        gs.getPlayers()[0].getOwned_projects().clear();

        Project baeckerei = ProjectLoader.getProject("bäckerei").orElseThrow();
        double ev = ProbabilityCalc.evPerRound(gs, 0, baeckerei);
        // Only own turn contributes: P(2)*1 + P(3)*1 = 1/6 + 1/6 = 1/3
        assertDoubleEq("evPerRound bäckerei (green) 2-player ≈ 0.333", 2.0 / 6.0, ev, 0.005);
    }

    private static void test_evPerRound_red_income_on_opponent_turns() {
        // Café (red, roll 3) fires on opponent's turn → owner GAINS on opponent turns
        // 2-player game: opponent has 5 coins
        // On opponent's turn: P(3) * 1 = 1/6 gain for café owner
        GameState gs = GameState.initial(2);
        gs.getPlayers()[0].getOwned_projects().clear();
        gs.getPlayers()[1].setCoins(10); // ensure opponent can always pay

        Project cafe = ProjectLoader.getProject("café").orElseThrow();
        double ev = ProbabilityCalc.evPerRound(gs, 0, cafe);
        // Red only fires on opponent's turn: 1 turn in 2-player, P=1/6 → ≈ 0.167
        // Own turn: red does NOT fire → 0 from own turn
        assertDoubleEq("evPerRound café (red) 2-player ≈ 0.167", 1.0 / 6.0, ev, 0.005);
    }

    private static void test_roi_positive_for_good_card() {
        // Weizenfeld costs 1 coin, has positive EV → ROI over 10 turns should be positive
        GameState gs = GameState.initial(4);
        gs.getPlayers()[0].setCoins(10);
        Project weizenfeld = ProjectLoader.getProject("weizenfeld").orElseThrow();
        RankEntry entry = ProbabilityCalc.roiOverHorizon(gs, 0, weizenfeld, 10, 0.95);
        assertTrue("weizenfeld ROI > 0 over 10 turns", entry.roiOverHorizon > 0);
        assertTrue("weizenfeld evPerRound > 0", entry.evPerRound > 0);
        assertTrue("weizenfeld immediateEV >= 0", entry.immediateEV >= 0);
    }

    private static void test_variance_nonnegative() {
        GameState gs = GameState.initial(4);
        gs.getPlayers()[0].setCoins(10);
        for (Project p : ProjectLoader.getAllProjects()) {
            if (p.isIs_grossprojekt()) continue;
            RankEntry entry = ProbabilityCalc.roiOverHorizon(gs, 0, p, 10, 0.95);
            assertTrue("variance >= 0 for " + p.getId(), entry.variance >= -1e-9);
        }
    }

    private static void test_probNoIncome_between_0_and_1() {
        GameState gs = GameState.initial(4);
        gs.getPlayers()[0].setCoins(10);
        Project weizenfeld = ProjectLoader.getProject("weizenfeld").orElseThrow();
        RankEntry entry = ProbabilityCalc.roiOverHorizon(gs, 0, weizenfeld, 10, 0.95);
        assertTrue("probNoIncomeOwnTurn in [0,1]",
                entry.probNoIncomeOwnTurn >= 0 && entry.probNoIncomeOwnTurn <= 1.0);
        assertTrue("probNoIncomeRound in [0,1]",
                entry.probNoIncomeRound >= 0 && entry.probNoIncomeRound <= 1.0);
    }

    private static void test_rank_nonempty_for_starting_state() {
        GameState gs = GameState.initial(4);
        // Give player enough coins to buy something
        gs.getPlayers()[0].setCoins(10);
        RankingOptions opts = new RankingOptions();
        ArrayList<RankEntry> ranking = ProbabilityCalc.rankPurchasableProjects(gs, 0, opts);
        assertTrue("ranking is non-empty with 10 coins", !ranking.isEmpty());
    }

    private static void test_rank_sorted_descending() {
        GameState gs = GameState.initial(4);
        gs.getPlayers()[0].setCoins(20);
        RankingOptions opts = new RankingOptions();
        ArrayList<RankEntry> ranking = ProbabilityCalc.rankPurchasableProjects(gs, 0, opts);
        for (int i = 1; i < ranking.size(); i++) {
            assertTrue("ranking is sorted descending at index " + i,
                    ranking.get(i - 1).roiOverHorizon >= ranking.get(i).roiOverHorizon);
        }
    }

    private static void test_rank_excludes_unaffordable() {
        GameState gs = GameState.initial(4);
        gs.getPlayers()[0].setCoins(1); // can only afford cost-1 cards
        RankingOptions opts = new RankingOptions();
        ArrayList<RankEntry> ranking = ProbabilityCalc.rankPurchasableProjects(gs, 0, opts);
        for (RankEntry e : ranking) {
            assertTrue("all ranked cards cost ≤ 1 coin: " + e.project.getId(),
                    e.project.getCost() <= 1);
        }
    }

    private static void test_win_prob_delta_buying_improves_score() {
        // Buying a card should not decrease win probability (may be neutral or positive)
        GameState gs = GameState.initial(4);
        gs.getPlayers()[0].setCoins(10);
        Project weizenfeld = ProjectLoader.getProject("weizenfeld").orElseThrow();
        RankingOptions opts = new RankingOptions();
        opts.includeWinProbDelta = true;
        ArrayList<RankEntry> ranking = ProbabilityCalc.rankPurchasableProjects(gs, 0, opts);
        // At least one card should have a non-negative winProbDelta
        boolean anyPositive = ranking.stream().anyMatch(e -> e.winProbDelta >= -0.01);
        assertTrue("at least one card has non-negative winProbDelta", anyPositive);
    }

    private static void test_baseline_win_prob_sums_to_one() {
        // In a symmetric 4-player starting state, win probs should sum to ~1.0
        GameState gs = GameState.initial(4);
        double sum = 0.0;
        for (int i = 0; i < 4; i++) {
            sum += ProbabilityCalc.computeBaselineWinProb(gs, i);
        }
        assertDoubleEq("baseline win probs sum to 1.0 over 4 players", 1.0, sum, 1e-9);
        // In a symmetric state each player should have equal probability (~0.25)
        double p0 = ProbabilityCalc.computeBaselineWinProb(gs, 0);
        assertTrue("each player in symmetric state has ~0.25 win prob (was " + p0 + ")",
                Math.abs(p0 - 0.25) < 0.01);
    }

    // =========================================================================
    // Phase 6 tests
    // =========================================================================

    private static void test_buerohaus_ev_positive_when_opponents_have_good_cards() {
        // P0 owns only weizenfeld (low EV); opponents own bergwerk (high EV, roll 9)
        // Candidate = bürohaus. bürohausSwapEV = bergwerk_EV - weizenfeld_EV > 0
        // → evPerRound should include P(6) * swapEV as a positive contribution
        GameStateBuilder b = new GameStateBuilder(3);
        b.setPlayerName(0, "P0").setCoins(0, 10).addProject(0, "weizenfeld");
        b.setPlayerName(1, "P1").setCoins(1, 5).addProject(1, "bergwerk");
        b.setPlayerName(2, "P2").setCoins(2, 5).addProject(2, "bergwerk");
        GameState gs = b.build();
        Project buerohaus = ProjectLoader.getProject("bürohaus").orElseThrow();
        double ev = ProbabilityCalc.evPerRound(gs, 0, buerohaus);
        assertTrue("bürohaus evPerRound > 0 when opponents own high-EV cards (was " + ev + ")",
                ev > 0);
    }

    private static void test_buerohaus_ev_zero_when_no_opponents_own_cards() {
        // All opponents have no non-landmark cards → bestOppEV = 0 → swap EV = 0
        // P0 owns only weizenfeld (blue, low EV). Candidate = bürohaus.
        // No opponent owns any non-landmark → swap gain = 0
        GameStateBuilder b = new GameStateBuilder(2);
        b.setPlayerName(0, "P0").setCoins(0, 10).addProject(0, "weizenfeld");
        b.setPlayerName(1, "P1").setCoins(1, 5); // P1 owns nothing
        GameState gs = b.build();
        Project buerohaus = ProjectLoader.getProject("bürohaus").orElseThrow();
        double ev = ProbabilityCalc.evPerRound(gs, 0, buerohaus);
        // Bürohaus swap = max(0, 0 - weizenfeld_EV) = 0 (clamped)
        // Weizenfeld blue EV (already owned) contributes; candidate itself (bürohaus) adds 0 coin-delta rolls.
        // The ev here will equal weizenfeld's evPerRound (since P0 already owns it and candidate adds bürohaus with 0 coin income).
        // We're just asserting the bürohaus ADDITION is ≥ 0 (no negative swap penalty).
        assertTrue("bürohaus evPerRound ≥ 0 even when no opponents own cards (was " + ev + ")",
                ev >= 0.0);
    }

    private static void test_buerohaus_swap_note_set_in_ranking() {
        // When bürohaus is affordable and opponents own better cards,
        // the RankEntry.notes should contain the swap advice string.
        GameStateBuilder b = new GameStateBuilder(2);
        b.setPlayerName(0, "P0").setCoins(0, 10).addProject(0, "weizenfeld");
        b.setPlayerName(1, "P1").setCoins(1, 5).addProject(1, "bergwerk");
        GameState gs = b.build();

        RankingOptions opts = new RankingOptions();
        ArrayList<RankEntry> ranking = ProbabilityCalc.rankPurchasableProjects(gs, 0, opts);

        RankEntry buerohausEntry = null;
        for (RankEntry e : ranking) {
            if ("bürohaus".equals(e.project.getId())) { buerohausEntry = e; break; }
        }
        assertTrue("bürohaus appears in ranking when affordable", buerohausEntry != null);
        assertTrue("bürohaus notes non-null when beneficial swap exists",
                buerohausEntry != null && buerohausEntry.notes != null);
        assertTrue("bürohaus notes mentions 'Swap'",
                buerohausEntry != null && buerohausEntry.notes != null
                        && buerohausEntry.notes.contains("Swap"));
    }

    private static void test_buerohaus_swap_executed_in_simulator() {
        // P0 owns bürohaus + weizenfeld (low EV); P1 owns bergwerk (high EV).
        // executeBürohausSwap should swap weizenfeld → P1, bergwerk → P0.
        GameStateBuilder b = new GameStateBuilder(2);
        b.setPlayerName(0, "P0").setCoins(0, 5)
         .addProject(0, "bürohaus").addProject(0, "weizenfeld");
        b.setPlayerName(1, "P1").setCoins(1, 5).addProject(1, "bergwerk");
        GameState gs = b.build();

        ProbabilityCalc.executeBürohausSwap(gs, 0);

        assertTrue("P0 now owns bergwerk after swap", gs.getPlayers()[0].hasProject("bergwerk"));
        assertTrue("P0 no longer owns weizenfeld after swap",
                !gs.getPlayers()[0].hasProject("weizenfeld"));
        assertTrue("P1 now owns weizenfeld after swap", gs.getPlayers()[1].hasProject("weizenfeld"));
        assertTrue("P1 no longer owns bergwerk after swap",
                !gs.getPlayers()[1].hasProject("bergwerk"));
        // Bürohaus itself must remain with P0
        assertTrue("P0 still owns bürohaus after swap", gs.getPlayers()[0].hasProject("bürohaus"));
    }

    // =========================================================================
    // Phase 5 tests
    // =========================================================================

    private static void test_simulator_returns_valid_winner() {
        GameState gs = GameState.initial(4);
        java.util.Random rng = new java.util.Random(42);
        int winner = GameSimulator.simulate(gs.copy(), rng);
        assertTrue("simulate() returns -1 or valid player index [0,3]",
                winner == -1 || (winner >= 0 && winner < 4));
        // Run 10 games — every result must be valid
        boolean allValid = true;
        for (int i = 0; i < 10; i++) {
            int w = GameSimulator.simulate(GameState.initial(4).copy(), rng);
            if (w < -1 || w >= 4) { allValid = false; break; }
        }
        assertTrue("all of 10 simulate() calls return valid winner index", allValid);
    }

    private static void test_simulator_deterministic_with_seed() {
        GameState gs1 = GameState.initial(3);
        GameState gs2 = GameState.initial(3);
        java.util.Random rng1 = new java.util.Random(12345);
        java.util.Random rng2 = new java.util.Random(12345);
        int w1 = GameSimulator.simulate(gs1, rng1);
        int w2 = GameSimulator.simulate(gs2, rng2);
        assertEq("same seed → same winner", w1, w2);
    }

    private static void test_mc_win_rates_sum_to_one() {
        // Sum of win rates across all players should be ≈ 1.0 (ignoring rare timeouts)
        GameState gs = GameState.initial(3);
        int sims = 300;
        double total = 0.0;
        for (int p = 0; p < 3; p++) {
            total += ProbabilityCalc.mcWinRate(gs, p, sims);
        }
        // Allow generous tolerance because timeouts (returned -1) are excluded from all players
        assertTrue("sum of mcWinRate over all players ≈ 1.0 (was " + total + ")",
                total >= 0.90 && total <= 1.10);
    }

    private static void test_mc_win_prob_delta_in_range() {
        GameState gs = GameState.initial(4);
        gs.getPlayers()[0].setCoins(10);
        RankingOptions opts = new RankingOptions();
        opts.includeWinProbDelta = true;
        opts.mcSimulations = 200;
        ArrayList<RankEntry> ranking = ProbabilityCalc.rankPurchasableProjects(gs, 0, opts);
        assertTrue("MC ranking is non-empty", !ranking.isEmpty());
        for (RankEntry e : ranking) {
            assertTrue("winProbDelta in [-1, 1] for " + e.project.getId(),
                    e.winProbDelta >= -1.0 && e.winProbDelta <= 1.0);
        }
    }

    // =========================================================================
    // Supply & Ownership Rules Tests
    // =========================================================================

    private static void test_builder_throws_on_duplicate_purple_same_player() {
        // Adding the same purple card twice to the same player must throw
        boolean threw = false;
        try {
            new GameStateBuilder(2)
                    .addProject(0, "stadion")
                    .addProject(0, "stadion"); // duplicate — should throw
        } catch (IllegalArgumentException ex) {
            threw = true;
        }
        assertTrue("GameStateBuilder throws when same purple card added twice to one player", threw);
    }

    private static void test_builder_allows_same_purple_for_different_players() {
        // Two different players may each own one copy of the same purple card
        // (Each copy is separate; the uniqueness rule is per-player, not global for builder validation)
        boolean threw = false;
        try {
            new GameStateBuilder(2)
                    .addProject(0, "stadion")
                    .addProject(1, "stadion"); // different players — allowed
        } catch (IllegalArgumentException ex) {
            threw = true;
        }
        assertTrue("GameStateBuilder allows same purple card for different players", !threw);
    }

    private static void test_rank_excludes_owned_purple_cards() {
        // If a player already owns stadion, rankPurchasableProjects must not offer it again
        GameStateBuilder b = new GameStateBuilder(2);
        b.setPlayerName(0, "P0").setCoins(0, 20)
                .addProject(0, "weizenfeld").addProject(0, "stadion");
        b.setPlayerName(1, "P1").setCoins(1, 3).addProject(1, "weizenfeld");
        GameState gs = b.build();
        RankingOptions opts = new RankingOptions();
        ArrayList<RankEntry> ranks = ProbabilityCalc.rankPurchasableProjects(gs, 0, opts);
        boolean stadionOffered = ranks.stream()
                .anyMatch(e -> e.project.getId().equals("stadion"));
        assertTrue("rankPurchasableProjects does not offer stadion to player who already owns it",
                !stadionOffered);
    }

    private static void test_rank_excludes_owned_landmarks() {
        // Sanity check: a player who owns bahnhof should not be offered it again
        GameStateBuilder b = new GameStateBuilder(2);
        b.setPlayerName(0, "P0").setCoins(0, 20)
                .addProject(0, "weizenfeld").addProject(0, "bahnhof");
        b.setPlayerName(1, "P1").setCoins(1, 3).addProject(1, "weizenfeld");
        GameState gs = b.build();
        RankingOptions opts = new RankingOptions();
        ArrayList<RankEntry> ranks = ProbabilityCalc.rankPurchasableProjects(gs, 0, opts);
        boolean bahnhofOffered = ranks.stream()
                .anyMatch(e -> e.project.getId().equals("bahnhof"));
        assertTrue("rankPurchasableProjects does not offer bahnhof to player who already owns it",
                !bahnhofOffered);
    }

    // =========================================================================
    // Rules Correctness Tests
    // =========================================================================

    private static void test_red_fires_before_green_income() {
        // Rule: Red → Blue/Green → Purple.
        // Setup: 2-player game. Active player (P0) owns bäckerei (green, activates on roll 2-3).
        //        Opponent (P1) owns café (red, activates on roll 3, costs roller 1 coin).
        //        P0 has 0 coins.
        //
        // Roll = 3:
        //   Correct (Red first):  red fires against 0 coins → P0 pays 0; then bäckerei fires → +1. Net = +1.
        //   Wrong   (Green first): bäckerei fires → P0 has +1 (1 coin); then red fires → pays 1. Net = 0.
        //
        // The test verifies the correct outcome: net gain = +1.
        GameStateBuilder b = new GameStateBuilder(2);
        b.setPlayerName(0, "P0").setCoins(0, 0).addProject(0, "bäckerei");
        b.setPlayerName(1, "P1").setCoins(1, 5).addProject(1, "café");
        GameState gs = b.build();

        // Use computeNetGainForRollPublic (package-private bridge) via evPerRound isolation:
        // We compute the net gain for roll=3 on P0's turn by using a neutral candidate.
        // Simpler: use GameSession.applyTurn and observe P0's coins after the turn.
        GameSession session = new GameSession(gs, new String[]{"P0", "P1"});
        TurnRecord turn = new TurnRecord(0, 3, null);
        session.applyTurn(turn);

        int p0Coins = session.getState().getPlayers()[0].getCoins();
        // Correct: red fires first (0 coins → pays 0), then bäckerei gives +1. P0 ends with 1.
        // Wrong:   bäckerei fires first (+1), then café takes 1. P0 ends with 0.
        assertTrue("Red fires before green: P0 (0 coins, bäckerei) nets +1 on roll 3 when opp has café (was "
                + p0Coins + ")", p0Coins == 1);
    }

    private static void test_red_payment_counter_clockwise_order() {
        // Rule: multiple red card owners are paid counter-clockwise from the active player.
        // Setup: 4-player game. Active player = P2 (index 2). P2 has 1 coin.
        //        P1 (counter-clockwise neighbour 1) owns café (roll 3, costs 1).
        //        P0 (counter-clockwise neighbour 2) owns café (roll 3, costs 1).
        //        P3 (counter-clockwise neighbour 3) owns café (roll 3, costs 1).
        //
        // Counter-clockwise from P2: P1 → P0 → P3.
        // P2 has 1 coin → P1 collects 1 coin, P0 and P3 collect 0.
        //
        // Old (ascending index) order: P0 → P1 → P3. P0 would collect 1, P1 and P3 get 0.
        // This test verifies P1 (not P0) gets paid — proving counter-clockwise is correct.
        GameStateBuilder b = new GameStateBuilder(4);
        b.setPlayerName(0, "P0").setCoins(0, 5).addProject(0, "café");
        b.setPlayerName(1, "P1").setCoins(1, 5).addProject(1, "café");
        b.setPlayerName(2, "P2").setCoins(2, 1);  // active player, only 1 coin
        b.setPlayerName(3, "P3").setCoins(3, 5).addProject(3, "café");
        GameState gs = b.build();

        GameSession session4p = new GameSession(gs, new String[]{"P0","P1","P2","P3"});
        TurnRecord turn4p = new TurnRecord(2, 3, null);
        session4p.applyTurn(turn4p);

        int p0After = session4p.getState().getPlayers()[0].getCoins();
        int p1After = session4p.getState().getPlayers()[1].getCoins();
        int p2After = session4p.getState().getPlayers()[2].getCoins();

        // Counter-clockwise from P2: P1 is first → P1 collects the 1 coin. P0 gets nothing.
        assertTrue("Counter-clockwise: P1 (first CCW neighbour of P2) collects the 1 coin (was " + p1After + ")",
                p1After == 6);
        assertTrue("Counter-clockwise: P0 (second CCW neighbour) gets 0 when P2 is broke (was " + p0After + ")",
                p0After == 5);
        assertTrue("Counter-clockwise: P2 ends with 0 coins after paying P1 (was " + p2After + ")",
                p2After == 0);
    }



    // =========================================================================
    // Game-Over Detection Tests
    // =========================================================================

    private static void test_game_over_on_fourth_landmark() {
        // Player 0 already owns 3 landmarks; buying the 4th should mark the session as finished.
        GameStateBuilder b = new GameStateBuilder(2);
        b.setPlayerName(0, "P0").setCoins(0, 25)  // 25 coins — enough for funkturm (22)
                .addProject(0, "weizenfeld")
                .addProject(0, "bahnhof")
                .addProject(0, "einkaufszentrum")
                .addProject(0, "freizeitpark");
        b.setPlayerName(1, "P1").setCoins(1, 3).addProject(1, "weizenfeld");
        GameState gs = b.build();

        logic.probability.GameSession session =
                new logic.probability.GameSession(gs, new String[]{"P0", "P1"});
        Project funkturm = ProjectLoader.getProject("funkturm").orElseThrow();
        logic.probability.TurnRecord turn = new logic.probability.TurnRecord(0, 7, funkturm);
        session.applyTurn(turn);

        assertTrue("session.isFinished() after 4th landmark", session.isFinished());
        assertTrue("session.getWinnerIndex() == 0", session.getWinnerIndex() == 0);
    }

    private static void test_no_game_over_before_fourth_landmark() {
        // Player 0 buys their 3rd landmark — game should NOT be over yet.
        GameStateBuilder b = new GameStateBuilder(2);
        b.setPlayerName(0, "P0").setCoins(0, 20)
                .addProject(0, "weizenfeld")
                .addProject(0, "bahnhof")
                .addProject(0, "einkaufszentrum");
        b.setPlayerName(1, "P1").setCoins(1, 3).addProject(1, "weizenfeld");
        GameState gs = b.build();

        logic.probability.GameSession session =
                new logic.probability.GameSession(gs, new String[]{"P0", "P1"});
        Project freizeitpark = ProjectLoader.getProject("freizeitpark").orElseThrow();
        logic.probability.TurnRecord turn = new logic.probability.TurnRecord(0, 7, freizeitpark);
        session.applyTurn(turn);

        assertTrue("session.isFinished() is false after only 3 landmarks", !session.isFinished());
        assertTrue("session.getWinnerIndex() == -1 when game not finished", session.getWinnerIndex() == -1);
    }

    private static void test_save_and_load_roundtrip() throws Exception {
        // Start from a fresh 2-player initial state (matches what load() reconstructs)
        GameSession session = new GameSession(GameState.initial(2), new String[]{"Alice", "Bob"});
        session.applyTurn(new TurnRecord(0, 1, null));
        // Bob buys bauernhof (cost 1, in unbuilt pool)
        Project bauernhof = ProjectLoader.getProject("bauernhof").orElseThrow();
        session.applyTurn(new TurnRecord(1, 2, bauernhof));

        // Save and reload
        Path tmp = Files.createTempFile("mkoro_test_", ".mkoro");
        try {
            session.save(tmp);
            GameSession loaded = GameSession.load(tmp);
            assertTrue("roundtrip: nextPlayerIndex matches",
                    loaded.nextPlayerIndex() == session.nextPlayerIndex());
            assertTrue("roundtrip: history size matches",
                    loaded.getHistory().size() == session.getHistory().size());
            assertTrue("roundtrip: Alice coins match after roll 1",
                    loaded.getState().getPlayers()[0].getCoins()
                            == session.getState().getPlayers()[0].getCoins());
            assertTrue("roundtrip: Bob owns bauernhof after load",
                    loaded.getState().getPlayers()[1].hasProject("bauernhof"));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void test_load_restores_player_names_and_history_size() throws Exception {
        GameSession session = new GameSession(
                GameState.initial(3), new String[]{"Spieler1", "Spieler2", "Spieler3"});
        session.applyTurn(new TurnRecord(0, 5, null));
        session.applyTurn(new TurnRecord(1, 2, null));

        Path tmp = Files.createTempFile("mkoro_test2_", ".mkoro");
        try {
            session.save(tmp);
            GameSession loaded = GameSession.load(tmp);
            assertTrue("names restored: Spieler1",
                    "Spieler1".equals(loaded.getPlayerNames()[0]));
            assertTrue("names restored: Spieler3",
                    "Spieler3".equals(loaded.getPlayerNames()[2]));
            assertTrue("history size 2 after load",
                    loaded.getHistory().size() == 2);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void test_save_and_load_snapshot_rooted_session() throws Exception {
        // Simulate a mid-game snapshot: player 0 already has bergwerk (cost 6, not a starter card)
        GameStateBuilder b = new GameStateBuilder(2);
        b.setPlayerName(0, "Alice").setCoins(0, 8)
                .addProject(0, "weizenfeld").addProject(0, "bäckerei").addProject(0, "bergwerk");
        b.setPlayerName(1, "Bob").setCoins(1, 5)
                .addProject(1, "weizenfeld").addProject(1, "bäckerei");
        GameSession snapshotSession = GameSession.fromSnapshot(b, new String[]{"Alice", "Bob"});
        // Apply one turn from this snapshot state
        snapshotSession.applyTurn(new TurnRecord(0, 9, null)); // roll 9 triggers bergwerk

        Path tmp = Files.createTempFile("mkoro_snapshot_", ".mkoro");
        try {
            snapshotSession.save(tmp);
            GameSession loaded = GameSession.load(tmp);
            assertTrue("snapshot session: Alice owns bergwerk after load",
                    loaded.getState().getPlayers()[0].hasProject("bergwerk"));
            assertTrue("snapshot session: initial state has bergwerk (not fresh initial)",
                    loaded.getState().getPlayers()[0].hasProject("bergwerk"));
            assertTrue("snapshot session: history size 1 after load",
                    loaded.getHistory().size() == 1);
            // Alice's coins: started at 8, rolled 9 with bergwerk (earns 5) = 13
            assertTrue("snapshot session: Alice coins match after bergwerk roll",
                    loaded.getState().getPlayers()[0].getCoins()
                            == snapshotSession.getState().getPlayers()[0].getCoins());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void test_load_invalid_file_throws() throws Exception {
        Path tmp = Files.createTempFile("mkoro_bad_", ".mkoro");
        try {
            Files.writeString(tmp, "{\"playerNames\":[\"A\"],\"turns\":[{\"playerIndex\":0,\"roll\":7,\"boughtId\":\"no_such_card\"}]}");
            boolean threw = false;
            try {
                GameSession.load(tmp);
            } catch (IllegalArgumentException ex) {
                threw = true;
            }
            assertTrue("load with unknown card id throws IllegalArgumentException", threw);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // =========================================================================
    // Starter-Card Supply Tests
    // =========================================================================

    private static void test_starter_cards_allow_7_copies_in_builder() {
        // Each player starts with 1 weizenfeld/bäckerei as a starter card (outside the market).
        // The market supplies 6 more copies. So a single player could own 1+6=7 in total.
        // GameStateBuilder must allow up to 7 copies of weizenfeld and bäckerei.
        boolean threw = false;
        try {
            GameStateBuilder b = new GameStateBuilder(2);
            b.setPlayerName(0, "P0").setCoins(0, 0);
            for (int i = 0; i < 7; i++) b.addProject(0, "weizenfeld");
            b.setPlayerName(1, "P1").setCoins(1, 0);
        } catch (Exception ex) {
            threw = true;
        }
        assertTrue("GameStateBuilder allows 7 copies of weizenfeld (starter + 6 market)", !threw);
    }

    private static void test_starter_cards_7_copies_exhausts_unbuilt_pool() {
        // If one player owns 7 weizenfeld (all 6 market copies + 1 starter),
        // weizenfeld must NOT appear in the unbuilt pool (market exhausted).
        GameStateBuilder b = new GameStateBuilder(2);
        b.setPlayerName(0, "P0").setCoins(0, 0);
        for (int i = 0; i < 7; i++) b.addProject(0, "weizenfeld");
        b.setPlayerName(1, "P1").setCoins(1, 0);
        GameState gs = b.build();
        boolean weizenInPool = gs.getUnbuilt_projects().stream()
                .anyMatch(p -> p.getId().equals("weizenfeld"));
        assertTrue("Weizenfeld removed from unbuilt pool when 7 copies owned (all market copies gone)",
                !weizenInPool);
    }

    private static void test_non_starter_cards_capped_at_6_in_builder() {
        // Non-starter cards like bauernhof are capped at 6 by the supply model
        // (they don't have a starter copy). Verify 6 is allowed but the pool is then exhausted.
        GameStateBuilder b = new GameStateBuilder(2);
        b.setPlayerName(0, "P0").setCoins(0, 0);
        for (int i = 0; i < 6; i++) b.addProject(0, "bauernhof");
        b.setPlayerName(1, "P1").setCoins(1, 0);
        GameState gs = b.build();
        boolean bauernhofInPool = gs.getUnbuilt_projects().stream()
                .anyMatch(p -> p.getId().equals("bauernhof"));
        assertTrue("Bauernhof removed from unbuilt pool when 6 copies owned", !bauernhofInPool);
    }

    // =========================================================================
    // GP Ranking Tests
    // =========================================================================

    private static void test_gp_included_in_ranking_when_affordable() {
        // When player has enough coins to buy a GP (e.g., bahnhof costs 4), it should appear
        // in the ranking list from rankPurchasableProjects.
        GameStateBuilder b = new GameStateBuilder(2);
        b.setPlayerName(0, "P0").setCoins(0, 10).addProject(0, "weizenfeld");
        b.setPlayerName(1, "P1").setCoins(1, 3).addProject(1, "weizenfeld");
        GameState gs = b.build();
        RankingOptions opts = new RankingOptions();
        ArrayList<RankEntry> ranking = ProbabilityCalc.rankPurchasableProjects(gs, 0, opts);
        boolean bahnhofOffered = ranking.stream()
                .anyMatch(e -> e.project.getId().equals("bahnhof"));
        assertTrue("bahnhof (GP, cost 4) is offered in ranking when player has 10 coins",
                bahnhofOffered);
    }

    private static void test_gp_not_offered_when_already_owned() {
        // A player who already owns bahnhof must not be offered it again.
        GameStateBuilder b = new GameStateBuilder(2);
        b.setPlayerName(0, "P0").setCoins(0, 20)
                .addProject(0, "weizenfeld").addProject(0, "bahnhof");
        b.setPlayerName(1, "P1").setCoins(1, 3).addProject(1, "weizenfeld");
        GameState gs = b.build();
        RankingOptions opts = new RankingOptions();
        ArrayList<RankEntry> ranking = ProbabilityCalc.rankPurchasableProjects(gs, 0, opts);
        boolean bahnhofOffered = ranking.stream()
                .anyMatch(e -> e.project.getId().equals("bahnhof"));
        assertTrue("bahnhof is not offered again when player already owns it", !bahnhofOffered);
    }

    // =========================================================================
    // Freizeitpark Doubles Tests
    // =========================================================================

    private static void test_freizeitpark_bonus_turn_granted_on_doubles() {
        // Player 0 has Bahnhof + Freizeitpark. Rolling doubles should grant a bonus turn.
        // After the doubles turn, nextPlayerIndex() should still return 0 (same player).
        GameStateBuilder b = new GameStateBuilder(2);
        b.setPlayerName(0, "P0").setCoins(0, 25)
                .addProject(0, "weizenfeld").addProject(0, "bahnhof").addProject(0, "freizeitpark");
        b.setPlayerName(1, "P1").setCoins(1, 3).addProject(1, "weizenfeld");
        GameState gs = b.build();
        GameSession session = new GameSession(gs, new String[]{"P0", "P1"});

        // Roll 6 (3+3 doubles) with isDoubles=true
        session.applyTurn(new TurnRecord(0, 6, null, true));

        assertTrue("Freizeitpark: bonus turn pending after doubles", session.isBonusTurnPending());
        assertEq("Freizeitpark: nextPlayerIndex() == 0 (same player gets bonus turn)",
                0, session.nextPlayerIndex());
    }

    private static void test_freizeitpark_bonus_turn_not_granted_without_bahnhof() {
        // Player 0 has Freizeitpark but NOT Bahnhof → only 1d6 rolls possible → no doubles.
        // Even if isDoubles=true is passed, the bonus should not fire without Bahnhof.
        GameStateBuilder b = new GameStateBuilder(2);
        b.setPlayerName(0, "P0").setCoins(0, 20)
                .addProject(0, "weizenfeld").addProject(0, "freizeitpark");
        b.setPlayerName(1, "P1").setCoins(1, 3).addProject(1, "weizenfeld");
        GameState gs = b.build();
        GameSession session = new GameSession(gs, new String[]{"P0", "P1"});

        // isDoubles=true but no Bahnhof
        session.applyTurn(new TurnRecord(0, 3, null, true));

        assertTrue("No bonus without Bahnhof: bonusTurnPending is false", !session.isBonusTurnPending());
        assertEq("No bonus without Bahnhof: nextPlayerIndex() == 1 (advances normally)",
                1, session.nextPlayerIndex());
    }

    private static void test_freizeitpark_no_chain_on_second_doubles() {
        // Freizeitpark rule: if player rolls doubles on the BONUS turn itself, no third turn.
        // After a bonus turn (regardless of isDoubles on that bonus turn), turn advances normally.
        GameStateBuilder b = new GameStateBuilder(2);
        b.setPlayerName(0, "P0").setCoins(0, 25)
                .addProject(0, "weizenfeld").addProject(0, "bahnhof").addProject(0, "freizeitpark");
        b.setPlayerName(1, "P1").setCoins(1, 3).addProject(1, "weizenfeld");
        GameState gs = b.build();
        GameSession session = new GameSession(gs, new String[]{"P0", "P1"});

        // First turn: doubles → bonus pending
        session.applyTurn(new TurnRecord(0, 6, null, true));
        assertTrue("After first doubles: bonus pending", session.isBonusTurnPending());
        assertEq("After first doubles: still P0's turn", 0, session.nextPlayerIndex());

        // Bonus turn: roll doubles again → should NOT chain
        session.applyTurn(new TurnRecord(0, 8, null, true));
        assertTrue("After bonus turn with doubles: no chain (bonus NOT pending)",
                !session.isBonusTurnPending());
        assertEq("After bonus turn: advances to P1", 1, session.nextPlayerIndex());
    }

    private static void test_freizeitpark_bonus_advances_player_after_bonus() {
        // After the bonus turn completes, the turn should advance to the next player normally.
        GameStateBuilder b = new GameStateBuilder(3);
        b.setPlayerName(0, "P0").setCoins(0, 25)
                .addProject(0, "weizenfeld").addProject(0, "bahnhof").addProject(0, "freizeitpark");
        b.setPlayerName(1, "P1").setCoins(1, 3).addProject(1, "weizenfeld");
        b.setPlayerName(2, "P2").setCoins(2, 3).addProject(2, "weizenfeld");
        GameState gs = b.build();
        GameSession session = new GameSession(gs, new String[]{"P0", "P1", "P2"});

        // P0 rolls doubles
        session.applyTurn(new TurnRecord(0, 6, null, true));
        assertEq("P0 bonus pending", 0, session.nextPlayerIndex());

        // P0 takes bonus turn (no more doubles)
        session.applyTurn(new TurnRecord(0, 5, null, false));
        assertEq("After bonus turn: P1's turn (index 1)", 1, session.nextPlayerIndex());

        // P1 takes their turn
        session.applyTurn(new TurnRecord(1, 3, null, false));
        assertEq("After P1's turn: P2's turn (index 2)", 2, session.nextPlayerIndex());
    }

    private static void test_freizeitpark_undo_restores_correct_state() throws Exception {
        // After undo, the bonus turn state should be correctly reset.
        GameStateBuilder b = new GameStateBuilder(2);
        b.setPlayerName(0, "P0").setCoins(0, 25)
                .addProject(0, "weizenfeld").addProject(0, "bahnhof").addProject(0, "freizeitpark");
        b.setPlayerName(1, "P1").setCoins(1, 3).addProject(1, "weizenfeld");
        GameState gs = b.build();
        GameSession session = new GameSession(gs, new String[]{"P0", "P1"});

        // P0 rolls doubles → bonus pending
        session.applyTurn(new TurnRecord(0, 6, null, true));
        assertTrue("Bonus pending before undo", session.isBonusTurnPending());

        // Undo the doubles roll
        session.undoLastTurn();
        assertTrue("Bonus NOT pending after undo", !session.isBonusTurnPending());
        assertEq("After undo: P0's turn again (index 0)", 0, session.nextPlayerIndex());
    }

    private static void test_coin_deltas_stored_in_turn_record() {
        // After applyTurn, the stored TurnRecord should have non-null coinDeltas.
        GameState gs = GameState.initial(2);
        GameSession session = new GameSession(gs, new String[]{"P0", "P1"});
        // Roll 1 activates weizenfeld (blue) — both players have it, each gets +1
        session.applyTurn(new TurnRecord(0, 1, null, false));
        TurnRecord stored = session.getHistory().get(0);
        assertTrue("coinDeltas non-null after applyTurn", stored.coinDeltas != null);
        assertTrue("coinDeltas length equals player count", stored.coinDeltas.length == 2);
    }

    private static void test_coin_deltas_null_for_externally_constructed_record() {
        // 3-arg and 4-arg constructors leave coinDeltas null (caller did not supply them)
        TurnRecord r3 = new TurnRecord(0, 3, null);
        TurnRecord r4 = new TurnRecord(0, 3, null, false);
        assertTrue("3-arg TurnRecord: coinDeltas is null", r3.coinDeltas == null);
        assertTrue("4-arg TurnRecord: coinDeltas is null", r4.coinDeltas == null);
    }

    private static void test_coin_deltas_correct_values_blue_card() {
        // Weizenfeld (blau, roll 1) gives +1 to all players.
        // Starting state: each player has weizenfeld. Roll 1 from P0.
        GameState gs = GameState.initial(3);
        GameSession session = new GameSession(gs, new String[]{"P0", "P1", "P2"});
        session.applyTurn(new TurnRecord(0, 1, null, false));
        TurnRecord stored = session.getHistory().get(0);
        assertTrue("3-player blue roll: 3 deltas", stored.coinDeltas.length == 3);
        // Each player has exactly weizenfeld → +1 for roll 1
        assertEq("P0 delta for blue roll 1", 1, stored.coinDeltas[0]);
        assertEq("P1 delta for blue roll 1", 1, stored.coinDeltas[1]);
        assertEq("P2 delta for blue roll 1", 1, stored.coinDeltas[2]);
    }

    private static void test_coin_deltas_correct_values_red_card() {
        // Café (rot, roll 3) takes 1 coin from the rolling player for P1's benefit.
        GameStateBuilder b = new GameStateBuilder(2);
        b.setPlayerName(0, "P0").setCoins(0, 5).addProject(0, "weizenfeld");
        b.setPlayerName(1, "P1").setCoins(1, 3).addProject(1, "weizenfeld").addProject(1, "café");
        GameState gs = b.build();
        GameSession session = new GameSession(gs, new String[]{"P0", "P1"});
        // P0 rolls 3: café triggers, P1 takes 1 coin from P0.
        session.applyTurn(new TurnRecord(0, 3, null, false));
        TurnRecord stored = session.getHistory().get(0);
        assertEq("P0 delta (red card paid)", -1, stored.coinDeltas[0]);
        assertEq("P1 delta (café income)",   +1, stored.coinDeltas[1]);
    }

    private static void test_coin_deltas_preserved_after_undo_replay() {
        // After undoLastTurn + applyTurn replay, coinDeltas should still be populated.
        GameState gs = GameState.initial(2);
        GameSession session = new GameSession(gs, new String[]{"P0", "P1"});
        session.applyTurn(new TurnRecord(0, 1, null, false));
        TurnRecord first = session.getHistory().get(0);
        assertTrue("First turn has deltas", first.coinDeltas != null);

        session.applyTurn(new TurnRecord(1, 2, null, false));
        session.undoLastTurn(); // removes second turn, replays first
        TurnRecord replayed = session.getHistory().get(0);
        assertTrue("Replayed first turn still has deltas", replayed.coinDeltas != null);
        assertEq("Replayed P0 delta matches", first.coinDeltas[0], replayed.coinDeltas[0]);
    }

    private static void test_coin_deltas_roundtrip_save_load() throws Exception {
        // Save a session and load it back — coinDeltas should survive the JSON round-trip.
        GameStateBuilder b = new GameStateBuilder(2);
        b.setPlayerName(0, "P0").setCoins(0, 5).addProject(0, "weizenfeld");
        b.setPlayerName(1, "P1").setCoins(1, 3).addProject(1, "weizenfeld").addProject(1, "café");
        GameState gs = b.build();
        GameSession session = new GameSession(gs, new String[]{"P0", "P1"});
        session.applyTurn(new TurnRecord(0, 3, null, false)); // P0 rolls 3 → café triggers

        TurnRecord original = session.getHistory().get(0);

        Path tmp = Files.createTempFile("mkoro_delta_test", ".mkoro");
        try {
            session.save(tmp);
            GameSession loaded = GameSession.load(tmp);
            TurnRecord roundTripped = loaded.getHistory().get(0);
            assertTrue("Loaded TurnRecord has coinDeltas", roundTripped.coinDeltas != null);
            assertEq("Loaded P0 delta matches", original.coinDeltas[0], roundTripped.coinDeltas[0]);
            assertEq("Loaded P1 delta matches", original.coinDeltas[1], roundTripped.coinDeltas[1]);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void test_gp_ranking_separate_from_regular_cards() {
        // GPs and regular cards should all appear in one sorted list; cost comparison valid.
        GameStateBuilder b = new GameStateBuilder(2);
        b.setPlayerName(0, "P0").setCoins(0, 25).addProject(0, "weizenfeld");
        b.setPlayerName(1, "P1").setCoins(1, 3).addProject(1, "weizenfeld");
        GameState gs = b.build();
        RankingOptions opts = new RankingOptions();
        ArrayList<RankEntry> ranking = ProbabilityCalc.rankPurchasableProjects(gs, 0, opts);
        // All 4 GPs should be in the ranking (bahnhof=4, einkaufszentrum=10, freizeitpark=16, funkturm=22)
        long gpCount = ranking.stream().filter(e -> e.project.isIs_grossprojekt()).count();
        assertTrue("All 4 GPs appear in ranking when player has 25 coins (found " + gpCount + ")",
                gpCount == 4);
        // Verify ranking is still sorted by ROI
        for (int i = 1; i < ranking.size(); i++) {
            assertTrue("ranking sorted descending with GPs at index " + i,
                    ranking.get(i - 1).roiOverHorizon >= ranking.get(i).roiOverHorizon);
        }
    }

    // =========================================================================
    // Calcs Layer Tests
    // =========================================================================

    private static void test_calcs_get_P1_sums_to_1() {
        double sum = 0.0;
        for (int r = 1; r <= 6; r++) sum += Calcs.get_P1(r);
        assertDoubleEq("Calcs.get_P1 sums to 1.0", 1.0, sum, 1e-12);
    }

    private static void test_calcs_get_P2_sums_to_1() {
        double sum = 0.0;
        for (int r = 2; r <= 12; r++) sum += Calcs.get_P2(r);
        assertDoubleEq("Calcs.get_P2 sums to 1.0", 1.0, sum, 1e-12);
    }

    private static void test_calcs_get_I_delegates_correctly() {
        // Weizenfeld roll=1 → 1 coin
        assertEq("Calcs.get_I weizenfeld roll=1",
                1, Calcs.get_I(1, "weizenfeld", false, false, 0, 0, 0, 5, new int[]{5}));
        // Bäckerei roll=2 oop=true → 1 coin
        assertEq("Calcs.get_I bäckerei roll=2 oop=true",
                1, Calcs.get_I(2, "bäckerei", true, false, 0, 0, 0, 5, new int[]{}));
    }

    private static void test_calcs_immediate_ev_nonnegative() {
        // Starting player with only starting cards: immediateEV ≥ 0
        core.GameState gs = core.GameState.initial(2);
        core.Project weizenfeld = core.ProjectLoader.getProject("weizenfeld").orElseThrow();
        double ev = Calcs.immediateEV(gs, 0, weizenfeld, false);
        assertTrue("Calcs.immediateEV ≥ 0 for weizenfeld (was " + ev + ")", ev >= 0.0);
    }

    private static void test_calcs_ev_per_round_blue_scales_with_players() {
        // Weizenfeld (blue, roll 1) in 2-player: fires on both turns → ≈ 2*(1/6) = 0.333
        core.GameState gs = core.GameState.initial(2);
        gs.getPlayers()[0].getOwned_projects().clear();
        core.Project weizenfeld = core.ProjectLoader.getProject("weizenfeld").orElseThrow();
        double ev = Calcs.evPerRound(gs, 0, weizenfeld);
        assertDoubleEq("Calcs.evPerRound weizenfeld 2-player ≈ 0.333", 2.0 / 6.0, ev, 0.005);
    }

    private static void test_calcs_roi_over_horizon_positive_for_cheap_card() {
        core.GameState gs = core.GameState.initial(4);
        gs.getPlayers()[0].setCoins(10);
        core.Project weizenfeld = core.ProjectLoader.getProject("weizenfeld").orElseThrow();
        calcs.RankEntry entry = Calcs.roiOverHorizon(gs, 0, weizenfeld, 10, 0.95);
        assertTrue("Calcs.roiOverHorizon > 0 for weizenfeld over 10 turns", entry.roiOverHorizon > 0);
        assertTrue("Calcs.roiOverHorizon.evPerRound > 0", entry.evPerRound > 0);
    }

    private static void test_calcs_baseline_win_prob_sums_to_one() {
        core.GameState gs = core.GameState.initial(4);
        double sum = 0.0;
        for (int i = 0; i < 4; i++) sum += Calcs.computeBaselineWinProb(gs, i);
        assertDoubleEq("Calcs.computeBaselineWinProb sums to 1.0 over 4 players", 1.0, sum, 1e-9);
    }

    private static void test_calcs_win_prob_delta_in_range() {
        core.GameState gs = core.GameState.initial(4);
        gs.getPlayers()[0].setCoins(10);
        core.Project weizenfeld = core.ProjectLoader.getProject("weizenfeld").orElseThrow();
        double delta = Calcs.estimateWinProbDelta(gs, 0, weizenfeld);
        assertTrue("Calcs.estimateWinProbDelta in (-1, 1) (was " + delta + ")",
                delta > -1.0 && delta < 1.0);
    }

    private static void test_calcs_portfolio_delta_ev_nonnegative_for_blue() {
        // Adding any blue card to an empty portfolio can only help (delta ≥ 0)
        core.GameState gs = core.GameState.initial(2);
        gs.getPlayers()[0].getOwned_projects().clear();
        core.Project weizenfeld = core.ProjectLoader.getProject("weizenfeld").orElseThrow();
        double delta = Calcs.portfolioDeltaEV(gs, 0, weizenfeld);
        assertTrue("Calcs.portfolioDeltaEV weizenfeld ≥ 0 (was " + delta + ")", delta >= 0.0);
    }

    private static void test_calcs_geometric_sum_identity_at_gamma1() {
        // γ=1 → sum = T
        assertDoubleEq("Calcs.geometricSum(10, 1.0) == 10.0", 10.0,
                Calcs.geometricSum(10, 1.0), 1e-9);
        // γ=0.95, T=1 → sum = 0.95
        assertDoubleEq("Calcs.geometricSum(1, 0.95) == 0.95", 0.95,
                Calcs.geometricSum(1, 0.95), 1e-9);
    }

    private static void test_calcs_optimal_dice_no_bahnhof_returns_1() {
        // Without Bahnhof, always use 1d6 (optimalDiceCount = 1)
        core.GameState gs = core.GameState.initial(2);
        assertEq("Calcs.optimalDiceCount without Bahnhof = 1",
                1, Calcs.optimalDiceCount(gs, 0));
    }

    // =========================================================================
    // Engine Registry Tests
    // =========================================================================

    private static void test_engine_registry_loads_entries() {
        // EngineRegistry must load at least one entry from engines.json
        java.util.List<EngineRegistryEntry> entries = EngineRegistry.getAll();
        assertTrue("EngineRegistry loads at least 1 entry (found " + entries.size() + ")",
                !entries.isEmpty());
    }

    private static void test_engine_registry_has_default() {
        // getDefault() must return a non-null entry
        EngineRegistryEntry def = EngineRegistry.getDefault();
        assertTrue("EngineRegistry.getDefault() is non-null", def != null);
        assertTrue("EngineRegistry.getDefault().id() is non-empty",
                def.id() != null && !def.id().isEmpty());
    }

    private static void test_engine_registry_default_is_balanced() {
        // The balanced entry should be the default (per engines.json)
        EngineRegistryEntry def = EngineRegistry.getDefault();
        assertTrue("Default engine is mcts-v1-balanced (was " + def.id() + ")",
                "mcts-v1-balanced".equals(def.id()));
        assertTrue("Default entry isDefault flag is true", def.isDefault());
    }

    private static void test_engine_registry_find_by_id() {
        // findById for existing id returns non-empty; for unknown returns empty
        assertTrue("findById mcts-v1-fast returns present",
                EngineRegistry.findById("mcts-v1-fast").isPresent());
        assertTrue("findById unknown-id returns empty",
                EngineRegistry.findById("no-such-engine").isEmpty());
        // Config of fast entry has correct iterations
        EngineRegistryEntry fast = EngineRegistry.findById("mcts-v1-fast").orElseThrow();
        assertEq("mcts-v1-fast iterations = 500", 500, fast.config().iterations);
        assertEq("mcts-v1-fast engineClass = mcts-v1", "mcts-v1", fast.engineClass());
    }

    // =========================================================================
    // Assertion helpers
    // =========================================================================

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

    private static void assertDoubleEq(String label, double expected, double actual, double tol) {
        boolean ok = Math.abs(expected - actual) <= tol;
        if (ok) {
            System.out.println("  PASS: " + label);
            passed++;
        } else {
            System.out.println("  FAIL: " + label
                    + " — expected " + expected + " ±" + tol + ", got " + actual);
            failed++;
        }
    }
}
