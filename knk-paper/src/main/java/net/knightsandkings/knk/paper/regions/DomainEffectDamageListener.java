package net.knightsandkings.knk.paper.regions;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;

import net.knightsandkings.knk.core.regions.DomainEffectDamage;
import net.knightsandkings.knk.core.regions.RegionDomainResolver;

/**
 * No effect damage for players inside domains unless a flag allows it (PandiO/knk-workspace#162; rule:
 * {@link DomainEffectDamage}). Effect damage is the {@code regions.effect-damage.causes} list in config.yml, by default
 * {@link #DEFAULT_CAUSES}: fire and burning, lava, campfires, magma blocks, poison, wither, harming/magic, dragon's
 * breath and freezing. Hits, arrows, falls and the like are untouched. A burning player whose fire damage is stopped
 * is put out.
 * <ul>
 *   <li><b>Players only</b>, like the combat safezone (KNG-11).</li>
 *   <li><b>Siege members are exempt</b> while their lobby is at the hub or in a match: the siege keeps its own combat
 *       rules, gate fire included.</li>
 *   <li><b>Cache only.</b> Domains come from {@link RegionDomainResolver#resolveRegions} (no I/O, warmed as players
 *       move); a spot whose regions aren't cached yet counts as no domain (fail open, as the safezone).</li>
 * </ul>
 * Switch: {@code regions.effect-damage.enabled} (default on), read on every event.
 */
public final class DomainEffectDamageListener implements Listener {

    public static final String ENABLED_PATH = "regions.effect-damage.enabled";
    public static final String CAUSES_PATH = "regions.effect-damage.causes";

    public static final Set<DamageCause> DEFAULT_CAUSES = Set.copyOf(EnumSet.of(DamageCause.FIRE, DamageCause.FIRE_TICK,
        DamageCause.LAVA, DamageCause.CAMPFIRE, DamageCause.HOT_FLOOR, DamageCause.POISON, DamageCause.WITHER,
        DamageCause.MAGIC, DamageCause.DRAGON_BREATH, DamageCause.FREEZE));

    /** The regions at a spot and the resolved flags there (TRUE allow, FALSE deny, null unset). */
    public record RegionsAt(Set<String> regionIds, Boolean effectFlag, Boolean pvpFlag) {
        public static final RegionsAt NONE = new RegionsAt(Set.of(), null, null);
    }

    /** {@link RegionsAt} for a location; {@link WorldGuardEffectDamageLookup} asks WorldGuard (kept out for tests). */
    @FunctionalInterface
    public interface RegionLookup {
        RegionsAt at(Location location);
    }

    private final BooleanSupplier enabled;
    private final Set<DamageCause> causes;
    private final RegionDomainResolver resolver;
    private final Predicate<Player> exempt;
    private final RegionLookup lookup;

    /**
     * @param enabled  the config switch, read on every event
     * @param causes   the damage causes that count as effect damage ({@link #causes(List, Consumer)})
     * @param resolver region ids to Knights and Kings domains (cache only)
     * @param exempt   players whose damage this rule leaves alone (siege members)
     */
    public DomainEffectDamageListener(BooleanSupplier enabled, Set<DamageCause> causes, RegionDomainResolver resolver,
                                      Predicate<Player> exempt) {
        this(enabled, causes, resolver, exempt, new WorldGuardEffectDamageLookup());
    }

    DomainEffectDamageListener(BooleanSupplier enabled, Set<DamageCause> causes, RegionDomainResolver resolver,
                               Predicate<Player> exempt, RegionLookup lookup) {
        this.enabled = Objects.requireNonNull(enabled, "enabled");
        this.causes = causes == null || causes.isEmpty() ? DEFAULT_CAUSES : Set.copyOf(causes);
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.exempt = exempt != null ? exempt : player -> false;
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    /** The config list as damage causes: unknown names go to {@code warn}; empty or absent means the defaults. */
    public static Set<DamageCause> causes(List<String> names, Consumer<String> warn) {
        if (names == null || names.isEmpty()) {
            return DEFAULT_CAUSES;
        }
        Set<DamageCause> parsed = EnumSet.noneOf(DamageCause.class);
        for (String name : names) {
            try {
                parsed.add(DamageCause.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException | NullPointerException e) {
                warn.accept(CAUSES_PATH + ": unknown damage cause '" + name + "' ignored");
            }
        }
        return parsed.isEmpty() ? DEFAULT_CAUSES : Set.copyOf(parsed);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !causes.contains(event.getCause()) || !enabled.getAsBoolean()) {
            return;
        }
        if (exempt.test(player) || !blockedAt(player.getLocation())) {
            return;
        }
        event.setCancelled(true);
        if (event.getCause() == DamageCause.FIRE_TICK || event.getCause() == DamageCause.FIRE) {
            player.setFireTicks(0);
        }
    }

    /** Whether effect damage is stopped at {@code location}. */
    boolean blockedAt(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        RegionsAt regions = lookup.at(location);
        if (regions.regionIds().isEmpty()) {
            return false;
        }
        return DomainEffectDamage.isBlocked(
            resolver.resolveRegions(location.getWorld().getName(), regions.regionIds()).domains(),
            regions.effectFlag(), regions.pvpFlag());
    }
}
