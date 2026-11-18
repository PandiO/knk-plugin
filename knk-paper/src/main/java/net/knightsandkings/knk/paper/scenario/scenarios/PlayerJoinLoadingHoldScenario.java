package net.knightsandkings.knk.paper.scenario.scenarios;

import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioPlayer;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import org.bukkit.GameMode;

/**
 * The join path's loading guard (simulated players, PandiO/knk-workspace#540; {@code JoinLoadingGuard}, KNG-52): a
 * player who joins goes through the real login ({@code AsyncPlayerPreLoginEvent}, then the server's player list and
 * {@code PlayerJoinEvent}), is held while their account loads (told so, in ADVENTURE and immune to damage) and is let
 * go once the join's account sync ends: told "You're all set!", back in the world's default game mode (SURVIVAL on
 * the CI server), no longer invulnerable. On the CI server the API is down, so the sync ends with its fallback, as on
 * a live server during an outage. A player holding {@code knk.mode.owner} in-house is never held. Leaving and joining
 * again holds the player again.
 */
public final class PlayerJoinLoadingHoldScenario implements Scenario<ScenarioScene> {

    static final String HELD = "Loading your account...";
    static final String RELEASED = "You're all set!";

    @Override
    public String name() {
        return "player-join-loading-hold";
    }

    @Override
    public String description() {
        return "A joining player is held while their account loads and let go after; an owner is never held";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        ScenarioPlayer player = scene.player("ScnJoiner", p -> p.account(scene.fakeId(1)));
        ScenarioPlayer owner = scene.player("ScnOwner", p -> p.account(scene.fakeId(2)).grant("knk.mode.owner"));
        plan.act("a player and an owner join", () -> {
                player.join();
                owner.join();
            })
            .await("both are online", 200, () -> player.online() && owner.online())
            .check("the player was told their account is loading", () -> player.received(HELD) ? null
                : "got " + player.recentMessages())
            .await("the player is let go", 300, () -> player.received(RELEASED))
            .expectEquals("the player's game mode after the hold", () -> player.player().getGameMode(), GameMode.SURVIVAL)
            .expect("the player is no longer invulnerable", () -> !player.player().isInvulnerable())
            .waitTicks(40)
            .check("the owner was never held", () -> owner.received(HELD) || owner.received(RELEASED)
                ? "the owner got " + owner.recentMessages() : null)
            .act("the player leaves", player::quit)
            .expect("the player is gone", () -> !player.online())
            .act("the player joins again", player::join)
            .await("the player is online again", 200, player::online)
            .await("the player is held and let go again", 300, () -> player.received(HELD) && player.received(RELEASED))
            .expectEquals("the player's game mode after the second hold", () -> player.player().getGameMode(),
                GameMode.SURVIVAL);
    }
}
