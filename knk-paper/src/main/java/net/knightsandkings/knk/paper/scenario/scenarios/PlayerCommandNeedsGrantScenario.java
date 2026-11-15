package net.knightsandkings.knk.paper.scenario.scenarios;

import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioPlayer;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import org.bukkit.Material;

/**
 * A player's command and its in-house permission check (simulated players, PandiO/knk-workspace#540; KNG-24/KNG-107):
 * two fake players with fake accounts type {@code /feed}. The one without {@code knk.feed} in their in-house set is
 * refused by the in-house gate in front of the command ({@code PermissionGatedCommand}) and stays hungry; the one
 * granted {@code knk.feed} is fed and told so. The command goes through the server's dispatch ({@code Player#chat}),
 * the permission answer through the live {@code KnkPermissible} and permission cache.
 * <p>
 * {@code /feed}, not {@code /heal}: on a server with WorldGuard the plain {@code /heal} label is WorldGuard's own
 * command (found by this scenario; see the scenario guide § 5).
 */
public final class PlayerCommandNeedsGrantScenario implements Scenario<ScenarioScene> {

    static final String REFUSAL = "You don't have permission to use this command.";
    private static final int HUNGRY = 4;

    @Override
    public String name() {
        return "player-command-needs-grant";
    }

    @Override
    public String description() {
        return "/feed is refused for a player without the in-house grant and feeds one who has it";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        ScenarioPlayer refused = scene.player("ScnNoFeed", p -> p.account(scene.fakeId(1)));
        ScenarioPlayer granted = scene.player("ScnFeeder", p -> p.account(scene.fakeId(2)).grant("knk.feed"));
        plan.act("build a floor", () -> ScenarioGates.floor(scene, 8, 8, 24, 24, 1, Material.STONE))
            .act("both players join", () -> {
                refused.join();
                granted.join();
            })
            .await("both are online", 200, () -> refused.online() && granted.online())
            .act("put them on the floor, hungry", () -> {
                refused.teleport(12.5, 2, 16.5);
                granted.teleport(20.5, 2, 16.5);
                refused.player().setFoodLevel(HUNGRY);
                granted.player().setFoodLevel(HUNGRY);
                refused.clearMessages();
                granted.clearMessages();
            })
            .act("the player without the grant types /feed", () -> refused.command("feed"))
            .await("they are refused", 60, () -> refused.received(REFUSAL))
            .expectEquals("their food level", () -> refused.player().getFoodLevel(), HUNGRY)
            .check("they are not told they were fed", () -> refused.received("Replenished")
                ? "got " + refused.recentMessages() : null)
            .act("the granted player types /feed", () -> granted.command("/feed"))
            .await("they are fed", 60, () -> granted.player().getFoodLevel() == 20)
            .await("they are told how much", 60, () -> granted.received("Replenished 16 hunger."))
            .check("they were not refused", () -> granted.received(REFUSAL) ? "got " + granted.recentMessages() : null);
    }
}
