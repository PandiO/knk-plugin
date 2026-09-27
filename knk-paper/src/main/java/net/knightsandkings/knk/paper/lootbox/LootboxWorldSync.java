package net.knightsandkings.knk.paper.lootbox;

import net.knightsandkings.knk.core.domain.users.PlayerNotification;
import net.knightsandkings.knk.core.lootbox.KnkLootboxToken;
import net.knightsandkings.knk.core.lootbox.KnkLootboxWorldChange;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Applies a {@code LootboxWorldChanged} notification (docs/specs/lootboxes/DESIGN.md §3.9, smoke test 2026-09-27): a
 * box despawned in the web app (or whose area was deleted there) disappears within seconds instead of at the next
 * runtime refresh, and a revoked token item is taken out of every online player's inventory and ender chest, with a
 * message to whoever held it. Offline holders lose it at their next join ({@link LootboxTokenDelivery#removeDeadCopies}),
 * and a copy stored in a chest is refused and removed when someone tries to open it. Main thread.
 */
public final class LootboxWorldSync {

    private final IntConsumer boxGone;
    private final Supplier<? extends Collection<? extends Player>> onlinePlayers;

    public LootboxWorldSync(IntConsumer boxGone, Supplier<? extends Collection<? extends Player>> onlinePlayers) {
        this.boxGone = boxGone;
        this.onlinePlayers = onlinePlayers;
    }

    public void handle(PlayerNotification notification) {
        if (notification != null) {
            apply(notification.lootboxWorldChanged());
        }
    }

    public void apply(KnkLootboxWorldChange change) {
        if (change == null) {
            return;
        }
        for (Integer spawnId : change.removedSpawnIds()) {
            if (spawnId != null) {
                boxGone.accept(spawnId);
            }
        }
        if (change.revokedTokens().isEmpty()) {
            return;
        }
        for (Player player : onlinePlayers.get()) {
            for (UUID token : change.revokedTokens()) {
                LootboxTokenDelivery.removeDead(player, token, KnkLootboxToken.STATUS_REVOKED);
            }
        }
    }
}
