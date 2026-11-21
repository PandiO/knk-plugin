package net.knightsandkings.knk.core.menu.opener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The situations a menu item override can name (siege shop DESIGN § 4.3.3, § 5.7.1, P26; PandiO/knk-workspace#871):
 * a fixed list of names, checked most specific first, each answered by a {@link Check} the owning feature
 * provides. A name without a check never applies, so a feature that is off (or not merged yet) only leaves its
 * override unused. Bukkit-free; the checks are read on every click, so they must answer from memory.
 * <p>
 * Siege Survival (PandiO/knk-workspace#41) provides {@link #SIEGE_SURVIVAL_MATCH} at its merge with
 * {@code provide(SIEGE_SURVIVAL_MATCH, playerId -> ...)}; later features add their names with {@link #register}.
 */
public final class MenuItemSituations {

    private static final Logger LOGGER = Logger.getLogger(MenuItemSituations.class.getName());

    /** A member of a running Siege Survival match. */
    public static final String SIEGE_SURVIVAL_MATCH = "siege.survival.match";
    /** A member of a running PvP Siege match. */
    public static final String SIEGE_MATCH = "siege.match";
    /** A member of a siege round at its scenario hub: the team split before the match, or the result after it. */
    public static final String SIEGE_HUB = "siege.hub";

    /** The fixed list, most specific first (knk-web-api {@code MenuItemSettings.Situations}). */
    public static final List<String> BUILT_IN = List.of(SIEGE_SURVIVAL_MATCH, SIEGE_MATCH, SIEGE_HUB);

    /** Whether a situation applies to a player right now. Main thread, from memory: no API call. */
    @FunctionalInterface
    public interface Check {
        boolean appliesTo(UUID playerId);
    }

    private final List<String> order = new CopyOnWriteArrayList<>(BUILT_IN);
    private final Map<String, Check> checks = new ConcurrentHashMap<>();

    /** Adds a situation name after the built-in ones (a later feature); a known name is left where it is. */
    public synchronized void register(String name) {
        String key = key(name);
        if (!order.contains(key)) {
            order.add(key);
        }
    }

    /** Sets the check of a situation (registering the name when it is new); replaces an earlier check. */
    public void provide(String name, Check check) {
        Objects.requireNonNull(check, "check");
        register(name);
        checks.put(key(name), check);
    }

    /** The names in the order they are checked. */
    public List<String> names() {
        return List.copyOf(order);
    }

    public boolean isKnown(String name) {
        return name != null && order.contains(name.trim());
    }

    /** Whether the situation has a check (its feature is on). */
    public boolean isProvided(String name) {
        return name != null && checks.containsKey(name.trim());
    }

    /**
     * The player's situation: the first name in {@link #names()} whose check applies; empty for none. A check that
     * throws counts as not applying (logged), so one broken feature never breaks the item.
     */
    public Optional<String> situationOf(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        for (String name : order) {
            Check check = checks.get(name);
            if (check == null) {
                continue;
            }
            try {
                if (check.appliesTo(playerId)) {
                    return Optional.of(name);
                }
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "[MenuItem] The situation check '" + name + "' failed", e);
            }
        }
        return Optional.empty();
    }

    /** The names an override map uses that are not in the list, for one warning when settings arrive. */
    public List<String> unknown(Iterable<String> names) {
        List<String> unknown = new ArrayList<>();
        for (String name : names) {
            if (!isKnown(name)) {
                unknown.add(name);
            }
        }
        return unknown;
    }

    private static String key(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a situation needs a name");
        }
        return name.trim();
    }
}
