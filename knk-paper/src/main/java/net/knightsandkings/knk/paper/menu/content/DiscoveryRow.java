package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.domain.discovery.DiscoveryProgressRow;
import net.knightsandkings.knk.core.menu.MenuRowKey;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One row of {@code discoveries.main} ({@code discoveries.rows} row source, domain-discovery
 * DESIGN.md §3.7): a discoverable place as seen by the viewer. Discovered places show their type's
 * material, name, town, date and what the discovery paid; the latest one is {@code HIGHLIGHT}.
 * Undiscovered places are {@code DISABLED} gray dye: a Town by name, anything smaller masked as
 * "???" with its town (D6), so the list hints where to explore without giving every house away.
 * Public zero-arg getters are what the seed's {@code $row.…$} chains call.
 */
public final class DiscoveryRow implements MenuRowKey {

    static final String UNDISCOVERED_MATERIAL = "GRAY_DYE";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final int domainId;
    private final String name;
    private final String material;
    private final List<String> loreLines;
    private final String displayMode;

    private DiscoveryRow(int domainId, String name, String material, List<String> loreLines, String displayMode) {
        this.domainId = domainId;
        this.name = name;
        this.material = material;
        this.loreLines = List.copyOf(loreLines);
        this.displayMode = displayMode;
    }

    /** @param latestDomainId the viewer's most recent discovery (highlighted), or null */
    public static DiscoveryRow of(DiscoveryProgressRow row, Integer latestDomainId) {
        String type = typeName(row.domainType());
        String placeName = row.name() == null || row.name().isBlank() ? type + " #" + row.domainId() : row.name();
        boolean hasTown = row.parentName() != null && !row.parentName().isBlank();
        List<String> lore = new ArrayList<>();

        if (row.discovered()) {
            lore.add("&7" + type + (hasTown ? " in &f" + row.parentName() : ""));
            if (row.discoveredAt() != null) {
                lore.add("&7Discovered: &f" + DATE.format(row.discoveredAt()));
            }
            String rewards = rewards(row.coins(), row.gems(), row.exp());
            if (rewards != null) {
                lore.add("&7Reward: " + rewards);
            }
            boolean latest = latestDomainId != null && latestDomainId == row.domainId();
            if (latest) {
                lore.add("&eYour latest discovery");
            }
            return new DiscoveryRow(row.domainId(), "&a" + placeName, materialFor(row.domainType()), lore,
                    latest ? "HIGHLIGHT" : "NORMAL");
        }

        if (isTown(row.domainType())) {
            lore.add("&7" + type);
            lore.add("&8Not yet discovered");
            return new DiscoveryRow(row.domainId(), "&7" + placeName, UNDISCOVERED_MATERIAL, lore, "DISABLED");
        }
        lore.add("&7" + type);
        lore.add(hasTown ? "&7Somewhere in &f" + row.parentName() : "&8Not yet discovered");
        return new DiscoveryRow(row.domainId(), "&8???", UNDISCOVERED_MATERIAL, lore, "DISABLED");
    }

    /** "Town", "District", "Structure", "Gate"; "Place" for anything else. */
    static String typeName(String domainType) {
        String type = domainType == null ? "" : domainType.trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "town" -> "Town";
            case "district" -> "District";
            case "structure" -> "Structure";
            case "gatestructure" -> "Gate";
            default -> "Place";
        };
    }

    /** Town {@code FILLED_MAP}, District {@code OAK_SIGN}, Structure {@code BRICKS}, Gate {@code IRON_BARS}. */
    static String materialFor(String domainType) {
        String type = domainType == null ? "" : domainType.trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "town" -> "FILLED_MAP";
            case "district" -> "OAK_SIGN";
            case "gatestructure" -> "IRON_BARS";
            default -> "BRICKS";
        };
    }

    private static boolean isTown(String domainType) {
        return domainType != null && domainType.trim().equalsIgnoreCase("Town");
    }

    /** "&6+120 coins &b+2 gems &d+40 XP" without the zero amounts; null when nothing was paid. */
    static String rewards(long coins, long gems, long exp) {
        List<String> parts = new ArrayList<>();
        if (coins > 0) parts.add("&6+" + coins + " coins");
        if (gems > 0) parts.add("&b+" + gems + " gems");
        if (exp > 0) parts.add("&d+" + exp + " XP");
        return parts.isEmpty() ? null : String.join(" ", parts);
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
        return o instanceof DiscoveryRow other && menuRowKey().equals(other.menuRowKey());
    }

    @Override
    public int hashCode() {
        return Objects.hash(menuRowKey());
    }
}
