package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.domain.teleport.KnkTeleportDestination;
import net.knightsandkings.knk.core.menu.MenuRowKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One row of {@code teleport.destinations} (teleport DESIGN.md §3.8, KNG-17 Phase 6): a warp
 * destination as the viewer sees it - the same data and lock text as {@code /warps}. The material is
 * the domain type's (the discoveries menu's icons: Town {@code FILLED_MAP}, District {@code OAK_SIGN},
 * Structure {@code BRICKS}, Gate {@code IRON_BARS}); the lore is v1's "Price: N" and "Available! Click
 * here to teleport" / "Locked! &lt;reason&gt;" plus the requirements and, for places that must be
 * discovered first, whether the viewer has. A locked row is {@code DISABLED}, so the engine ignores
 * clicks on it; an available one runs {@code teleport.warp} with its domain id. Public zero-arg
 * getters are what the seed's {@code $row.…$} chains call.
 */
public final class TeleportDestinationRow implements MenuRowKey {

    static final String NO_PERMISSION = "You don't have permission to warp";
    private static final String NOT_DISCOVERED = "NotDiscovered";

    private final int domainId;
    private final String name;
    private final String material;
    private final List<String> loreLines;
    private final String displayMode;

    private TeleportDestinationRow(int domainId, String name, String material, List<String> loreLines, String displayMode) {
        this.domainId = domainId;
        this.name = name;
        this.material = material;
        this.loreLines = List.copyOf(loreLines);
        this.displayMode = displayMode;
    }

    /**
     * @param canWarp            the viewer holds {@code knk.teleport.warp}
     * @param bypassRequirements the viewer holds {@code knk.teleport.bypass.requirements}
     * @param bypassCost         the viewer holds {@code knk.teleport.bypass.cost}
     */
    public static TeleportDestinationRow of(KnkTeleportDestination destination, boolean canWarp,
                                            boolean bypassRequirements, boolean bypassCost) {
        List<String> lore = new ArrayList<>();
        lore.add("&7" + DiscoveryRow.typeName(destination.domainType()));
        boolean free = destination.priceGems() <= 0 || bypassCost;
        lore.add(free ? "&7Price: &afree" : "&7Price: &f" + destination.priceGems() + " gems");
        if (hasText(destination.minTitleName())) {
            lore.add("&7Title: &f" + destination.minTitleName());
        }
        if (hasText(destination.minPremiumTierName())) {
            lore.add("&7Premium tier: &6" + destination.minPremiumTierName());
        }
        if (destination.requiresDiscovery()) {
            lore.add(discoveryLine(destination));
        }
        lore.add("");

        String lock = !canWarp ? NO_PERMISSION : destination.lockReason(bypassRequirements, bypassCost);
        if (lock != null) {
            lore.add("&cLocked! " + lock);
            return new TeleportDestinationRow(destination.domainId(), "&7" + destination.name(),
                    DiscoveryRow.materialFor(destination.domainType()), lore, "DISABLED");
        }
        lore.add("&aAvailable! Click here to teleport");
        return new TeleportDestinationRow(destination.domainId(), "&a" + destination.name(),
                DiscoveryRow.materialFor(destination.domainType()), lore, "NORMAL");
    }

    /**
     * The server checks title, premium tier, discovery in that order and names only the first one
     * missing, so discovery is known when it is that lock or when every requirement is met.
     */
    static String discoveryLine(KnkTeleportDestination destination) {
        if (NOT_DISCOVERED.equals(destination.lockCode())) {
            return "&8Not yet discovered";
        }
        return destination.requirementsMet() ? "&aDiscovered" : "&7Must be discovered first";
    }

    /** The single row shown when no place is open for teleporting. */
    public static TeleportDestinationRow none() {
        return new TeleportDestinationRow(0, "&7No teleport destinations yet", "BARRIER",
                List.of("&7No place is open for teleporting right now."), "DISABLED");
    }

    /** The single row shown when the list can't be loaded (API down, no account, teleports off). */
    public static TeleportDestinationRow unavailable() {
        return new TeleportDestinationRow(0, "&cWarps aren't available right now", "BARRIER",
                List.of("&7Try again in a moment."), "DISABLED");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public int getDomainId() {
        return domainId;
    }

    public String getName() {
        return name;
    }

    public String getMaterial() {
        return material;
    }

    public List<String> getLoreLines() {
        return loreLines;
    }

    public String getDisplayMode() {
        return displayMode;
    }

    @Override
    public Object menuRowKey() {
        return List.of(domainId, name, material, loreLines, displayMode);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TeleportDestinationRow other && menuRowKey().equals(other.menuRowKey());
    }

    @Override
    public int hashCode() {
        return Objects.hash(menuRowKey());
    }
}
