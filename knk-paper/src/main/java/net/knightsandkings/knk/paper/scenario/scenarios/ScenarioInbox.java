package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;

import net.knightsandkings.knk.core.ports.api.ItemDeliveryApi;
import net.knightsandkings.knk.core.production.inbox.ClaimResult;
import net.knightsandkings.knk.core.production.inbox.DeliveryRequest;
import net.knightsandkings.knk.core.production.inbox.DeliveryResult;
import net.knightsandkings.knk.core.production.inbox.InboxView;
import net.knightsandkings.knk.paper.production.terminal.BlueprintItems;
import net.knightsandkings.knk.paper.scenario.ScenarioPlayer;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * The reward inbox scenarios' stand-ins (PandiO/knk-workspace#684): an in-memory {@link ItemDeliveryApi} that records
 * deliveries and claims (every delivery reaches the inbox, every claim is accepted in full) and bread for every
 * blueprint, so no API is needed.
 */
final class ScenarioInbox {

    static final int BREAD = 11;

    final List<DeliveryRequest> deliveries = new ArrayList<>();
    final List<DeliveryRequest.Line> claims = new ArrayList<>();
    final List<String> claimKeys = new ArrayList<>();
    volatile InboxView view;

    final ItemDeliveryApi api = new ItemDeliveryApi() {
        public CompletableFuture<DeliveryResult> deliver(DeliveryRequest request) {
            deliveries.add(request);
            return CompletableFuture.completedFuture(new DeliveryResult(deliveries.size(), "Inboxed", request.totalAmount(), 0,
                    2304, null, false));
        }

        public CompletableFuture<InboxView> inbox(int userId) {
            return CompletableFuture.completedFuture(view != null ? view : InboxView.empty(userId));
        }

        public CompletableFuture<ClaimResult> claim(int userId, String key, List<DeliveryRequest.Line> lines) {
            claims.addAll(lines);
            claimKeys.add(key);
            long accepted = lines.stream().mapToLong(DeliveryRequest.Line::amount).sum();
            return CompletableFuture.completedFuture(new ClaimResult("Applied", false, accepted, List.of(), view));
        }
    };

    static BlueprintItems bread() {
        return new BlueprintItems(null, null) {
            @Override
            public Template cached(int blueprintId) {
                return new Template(new ItemStack(Material.BREAD), 64, true);
            }

            @Override
            public CompletableFuture<Template> template(int blueprintId, String fallbackName) {
                return CompletableFuture.completedFuture(cached(blueprintId));
            }
        };
    }

    /** A stone floor under the player's spot, so dropped items lie where they can be counted. */
    static void floor(ScenarioScene scene) {
        for (int x = 10; x <= 22; x++) {
            for (int z = 10; z <= 22; z++) {
                scene.block(x, 1, z).setType(Material.STONE, false);
            }
        }
    }

    /** Fills every storage slot (hotbar and main inventory) with dirt, then puts {@code bread} bread in slot 0 (0 = none). */
    static void fill(ScenarioPlayer player, int bread) {
        ItemStack[] contents = new ItemStack[player.inventory().getStorageContents().length];
        for (int i = 0; i < contents.length; i++) {
            contents[i] = new ItemStack(Material.DIRT, 64);
        }
        if (bread > 0) {
            contents[0] = new ItemStack(Material.BREAD, bread);
        }
        player.inventory().setStorageContents(contents);
    }

    /** Dropped item entities within 6 blocks of the player. */
    static long dropsNear(ScenarioPlayer player) {
        return player.location().getWorld().getNearbyEntities(player.location(), 6, 6, 6).stream()
                .filter(e -> e instanceof Item).count();
    }

    static int breadIn(ScenarioPlayer player) {
        int total = 0;
        for (ItemStack stack : player.inventory().getStorageContents()) {
            if (stack != null && stack.getType() == Material.BREAD) {
                total += stack.getAmount();
            }
        }
        return total;
    }
}
