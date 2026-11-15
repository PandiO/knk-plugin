package net.knightsandkings.knk.paper.scenario.scenarios;

import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioPlayer;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.PluginIdentifiableCommand;

/**
 * The plain {@code /heal} reaches KnK's command on a server with WorldGuard (PandiO/knk-workspace#580): WorldGuard
 * enables first and registers its own {@code /heal}, so before the fix a player granted {@code knk.heal} in-house got
 * WorldGuard's "You don't have permission to do that." and an op was healed by WorldGuard. Three fake players with
 * fake accounts and 4 health type {@code /heal}: the one without {@code knk.heal} is refused by KnK's in-house gate
 * and stays hurt; the one granted it is healed and told so by KnK; an op without the grant is healed by KnK too. The
 * granted player is then hurt again and uses {@code /knightsandkings:heal}, which still works. Last, the server's
 * {@code heal} label is KnK's while {@code worldguard:heal} stays WorldGuard's (checked last, so a regression shows
 * first as what a player sees).
 */
public final class PlayerHealNeedsGrantScenario implements Scenario<ScenarioScene> {

    static final String REFUSAL = PlayerCommandNeedsGrantScenario.REFUSAL;
    private static final String HEALED = "Replenished 16 health.";
    private static final double HURT = 4.0;
    private static final double FULL = 20.0;

    @Override
    public String name() {
        return "player-heal-needs-grant";
    }

    @Override
    public String description() {
        return "/heal is KnK's next to WorldGuard: refused without the in-house grant, heals with it and for an op";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        ScenarioPlayer refused = scene.player("ScnNoHeal", p -> p.account(scene.fakeId(1)));
        ScenarioPlayer granted = scene.player("ScnHealer", p -> p.account(scene.fakeId(2)).grant("knk.heal"));
        ScenarioPlayer op = scene.player("ScnHealOp", p -> p.account(scene.fakeId(3)).op());
        plan.act("build a floor", () -> ScenarioGates.floor(scene, 8, 8, 28, 24, 1, Material.STONE))
            .act("the players join", () -> {
                refused.join();
                granted.join();
                op.join();
            })
            .await("all three are online", 200, () -> refused.online() && granted.online() && op.online())
            .act("put them on the floor, hurt", () -> {
                refused.teleport(12.5, 2, 16.5);
                granted.teleport(18.5, 2, 16.5);
                op.teleport(24.5, 2, 16.5);
                refused.player().setHealth(HURT);
                granted.player().setHealth(HURT);
                op.player().setHealth(HURT);
                refused.clearMessages();
                granted.clearMessages();
                op.clearMessages();
            })
            .act("the player without the grant types /heal", () -> refused.command("heal"))
            .await("KnK refuses them", 60, () -> refused.received(REFUSAL))
            .expectEquals("their health", () -> refused.player().getHealth(), HURT)
            .check("they are not told they were healed", () -> refused.received("Replenished")
                ? "got " + refused.recentMessages() : null)
            .act("the granted player types /heal", () -> granted.command("/heal"))
            .await("they are healed", 60, () -> granted.player().getHealth() == FULL)
            .await("KnK tells them how much", 60, () -> granted.received(HEALED))
            .check("they were not refused", () -> granted.received(REFUSAL) || granted.received("You don't have permission")
                ? "got " + granted.recentMessages() : null)
            .act("the op types /heal", () -> op.command("heal"))
            .await("the op is healed", 60, () -> op.player().getHealth() == FULL)
            .await("by KnK's command", 60, () -> op.received(HEALED))
            .act("hurt the granted player again", () -> {
                granted.player().setHealth(HURT);
                granted.clearMessages();
            })
            .act("they type /knightsandkings:heal", () -> granted.command("knightsandkings:heal"))
            .await("they are healed again", 60, () -> granted.player().getHealth() == FULL)
            .await("and told so", 60, () -> granted.received(HEALED))
            .check("the plain heal label is KnK's", () -> owner("heal").equals("KnightsAndKings")
                ? null : "heal belongs to " + owner("heal"))
            .check("WorldGuard's heal keeps its namespaced label", () -> Bukkit.getPluginManager().getPlugin("WorldGuard") == null
                || owner("worldguard:heal").equals("WorldGuard") ? null : "worldguard:heal belongs to " + owner("worldguard:heal"));
    }

    private static String owner(String label) {
        Command command = Bukkit.getCommandMap().getCommand(label);
        if (command instanceof PluginIdentifiableCommand owned) {
            return owned.getPlugin().getName();
        }
        return command == null ? "nobody" : command.getClass().getSimpleName();
    }
}
