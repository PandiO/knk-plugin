package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.domain.permissions.PermissionGroupSummary;
import net.knightsandkings.knk.core.menu.MenuRowKey;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * One row of {@code premium.tiers} (CONTENT_PORT_PLAN.md CP5): a premium-tier permission group,
 * built from today's v3 data (name, weight order, salary multiplier) - not v1's 2017 donator
 * content. {@code HIGHLIGHT} marks the viewer's own tier. The perk redesign (vision §5.3) will
 * change the data, not this row.
 */
public final class PremiumTierRow implements MenuRowKey {

    /** v1's tier blocks (IRON/GOLD/DIAMOND/REDSTONE), extended for more tiers; by rank among premium tiers. */
    static final List<String> MATERIALS = List.of("IRON_BLOCK", "GOLD_BLOCK", "DIAMOND_BLOCK", "REDSTONE_BLOCK",
            "EMERALD_BLOCK", "NETHERITE_BLOCK");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final int groupId;
    private final String name;
    private final String material;
    private final String salaryMultiplierText;
    private final List<String> loreLines;
    private final String displayMode;

    private PremiumTierRow(int groupId, String name, String material, String salaryMultiplierText, List<String> loreLines,
                           String displayMode) {
        this.groupId = groupId;
        this.name = name;
        this.material = material;
        this.salaryMultiplierText = salaryMultiplierText;
        this.loreLines = List.copyOf(loreLines);
        this.displayMode = displayMode;
    }

    /** Premium groups only, ordered by weight; {@code viewerTierId}/{@code viewerTierUntil} from the viewer's summary. */
    static List<PremiumTierRow> rows(List<PermissionGroupSummary> groups, Integer viewerTierId, OffsetDateTime viewerTierUntil) {
        List<PermissionGroupSummary> tiers = groups.stream()
                .filter(PermissionGroupSummary::isPremiumTier)
                .sorted((a, b) -> a.weight() != b.weight() ? Integer.compare(a.weight(), b.weight()) : Integer.compare(a.id(), b.id()))
                .toList();
        List<PremiumTierRow> rows = new ArrayList<>(tiers.size());
        for (int i = 0; i < tiers.size(); i++) {
            PermissionGroupSummary tier = tiers.get(i);
            boolean own = viewerTierId != null && viewerTierId == tier.id();
            String multiplier = multiplierText(tier.salaryMultiplier());
            List<String> lore = new ArrayList<>();
            lore.add("&7Salary multiplier: &f" + multiplier);
            if (own) {
                lore.add("");
                lore.add("&aYour current tier");
                lore.add(viewerTierUntil != null ? "&7Until &f" + DATE.format(viewerTierUntil) : "&7Permanent");
            }
            rows.add(new PremiumTierRow(tier.id(), tier.name(), MATERIALS.get(Math.min(i, MATERIALS.size() - 1)), multiplier,
                    lore, own ? "HIGHLIGHT" : "NORMAL"));
        }
        return rows;
    }

    /** "x1.5", "x1", "x1.25". */
    static String multiplierText(double multiplier) {
        return "x" + BigDecimal.valueOf(multiplier).stripTrailingZeros().toPlainString();
    }

    public int getGroupId() {
        return groupId;
    }

    public String getName() {
        return name;
    }

    public String getMaterial() {
        return material;
    }

    public String getSalaryMultiplierText() {
        return salaryMultiplierText;
    }

    public List<String> getLoreLines() {
        return loreLines;
    }

    public String getDisplayMode() {
        return displayMode;
    }

    @Override
    public Object menuRowKey() {
        return List.of(groupId, name, material, loreLines, displayMode);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PremiumTierRow other && menuRowKey().equals(other.menuRowKey());
    }

    @Override
    public int hashCode() {
        return menuRowKey().hashCode();
    }
}
