package net.knightsandkings.knk.paper.production.harvest;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.domain.users.TitleBracket;
import net.knightsandkings.knk.core.production.harvest.RequirementEvaluator;

/**
 * The game server's {@link RequirementEvaluator} (PandiO/knk-workspace#134): a permission node through the in-house
 * permission cache ({@code CommandPermissions#has}: group grants and wildcards, ops pass; cache-only, so it fails closed
 * until the player's cache is warm) and the lowest title bracket from the player's experience in the user cache and the
 * brackets' minimum experience. Main thread.
 */
public final class HarvestRequirements implements RequirementEvaluator {

    private final BiPredicate<Player, String> permission;
    private final Function<UUID, Integer> experience;
    private final Supplier<CompletableFuture<List<TitleBracket>>> brackets;
    private final Map<Integer, TitleBracket> byId = new ConcurrentHashMap<>();

    /**
     * @param experience the player's experience points from the user cache, or null when unknown
     * @param brackets   reads the title brackets (refreshed with the catalogue); null = no title checks possible
     */
    public HarvestRequirements(BiPredicate<Player, String> permission, Function<UUID, Integer> experience,
                               Supplier<CompletableFuture<List<TitleBracket>>> brackets) {
        this.permission = permission;
        this.experience = experience;
        this.brackets = brackets;
    }

    /** Re-reads the title brackets. */
    public void refresh() {
        if (brackets == null) {
            return;
        }
        try {
            brackets.get().whenComplete((list, error) -> {
                if (error == null && list != null) {
                    Map<Integer, TitleBracket> fresh = new java.util.HashMap<>();
                    list.forEach(b -> fresh.put(b.id(), b));
                    byId.keySet().retainAll(fresh.keySet());
                    byId.putAll(fresh);
                }
            });
        } catch (RuntimeException e) {
            // no brackets: title rules stay unmet until a read works
        }
    }

    /** For tests and scenarios: the known brackets. */
    public void setBrackets(List<TitleBracket> list) {
        byId.clear();
        list.forEach(b -> byId.put(b.id(), b));
    }

    @Override
    public String unmet(UUID player, Requirement requirement) {
        if (requirement == null || requirement.empty()) {
            return null;
        }
        if (requirement.permission() != null) {
            Player online = Bukkit.getPlayer(player);
            if (online == null || permission == null || !permission.test(online, requirement.permission())) {
                return "you need the permission " + requirement.permission();
            }
        }
        if (requirement.minTitleBracketId() != null) {
            TitleBracket needed = byId.get(requirement.minTitleBracketId());
            Integer xp = experience == null ? null : experience.apply(player);
            if (needed == null || xp == null) {
                return "your title can't be checked right now";
            }
            if (xp < needed.minExperience()) {
                return "you need the title " + needed.maleName() + " or higher";
            }
        }
        return null;
    }
}
