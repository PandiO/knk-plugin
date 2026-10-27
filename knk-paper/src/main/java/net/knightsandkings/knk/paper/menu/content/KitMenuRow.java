package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.domain.item.KnkKit;
import net.knightsandkings.knk.core.domain.item.KnkKitAvailability;
import net.knightsandkings.knk.core.menu.MenuRowKey;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One row of {@code kits.overview} ({@code kits.available} row source, CONTENT_PORT_PLAN.md CP2):
 * a kit the viewer may see, with its availability for that viewer. Public zero-arg getters are
 * what the seed's {@code $row.…$} chains call (reflection, main thread).
 * <p>
 * {@link #getCooldownText()} is computed from the clock on every call, so a {@code Ttl} binding
 * counts down under the template's auto-refresh; everything else is fixed per fetch.
 * {@link #menuRowKey()} changes whenever anything the player sees changes, so {@code OnDirty}
 * bindings re-resolve after a claim/purchase even if the kit stays in the same slot.
 */
public final class KitMenuRow implements MenuRowKey {

    /** Name + icon of an item blueprint a kit contains (resolved by {@link KitsMenuFeature}). */
    public record BlueprintInfo(String name, String materialKey) {
    }

    static final int DESCRIPTION_WIDTH = 32;
    /** Widest visible "Other contents" line (PandiO/knk-workspace#172: one long line ran off the screen). */
    static final int CONTENTS_WIDTH = 40;
    /** At most this many "Other contents" lines; the last one says "… and N more" when items are left out. */
    static final int CONTENTS_MAX_LINES = 6;
    private static final String OTHER_CONTENTS_LABEL = "Other contents: ";
    static final String FALLBACK_MATERIAL = "CHEST";

    private final int kitId;
    private final String name;
    private final List<String> loreLines;
    private final String material;
    private final String displayMode;
    private final boolean purchase;
    private final String purchasePrompt;
    private final OffsetDateTime cooldownExpiresAt;
    private final Clock clock;

    private KitMenuRow(int kitId, String name, List<String> loreLines, String material, String displayMode,
                       boolean purchase, String purchasePrompt, OffsetDateTime cooldownExpiresAt, Clock clock) {
        this.kitId = kitId;
        this.name = name;
        this.loreLines = List.copyOf(loreLines);
        this.material = material;
        this.displayMode = displayMode;
        this.purchase = purchase;
        this.purchasePrompt = purchasePrompt;
        this.cooldownExpiresAt = cooldownExpiresAt;
        this.clock = clock;
    }

    /** The single disabled row shown when the viewer has no kits at all ("No kits available right now"). */
    public static KitMenuRow none(Clock clock) {
        return new KitMenuRow(0, "&cNo kits available right now", List.of("&7Ask a member of staff about kits."),
                "BARRIER", "DISABLED", false, null, null, clock);
    }

    /**
     * @param kit        the full kit (contents), or null when it couldn't be loaded - the row still
     *                   shows name/description/availability, just no content summary
     * @param blueprints blueprint id → name/material for the kit's items (missing ids are skipped)
     */
    public static KitMenuRow of(KnkKitAvailability availability, KnkKit kit, Map<Integer, BlueprintInfo> blueprints,
                                Clock clock) {
        String name = availability.name() != null && !availability.name().isBlank() ? availability.name() : "Kit #" + availability.kitId();
        boolean purchasable = availability.isSinglePurchasePremium() && !availability.isPurchased();

        List<String> lore = new ArrayList<>();
        for (String line : wrap(availability.description(), DESCRIPTION_WIDTH)) {
            lore.add("&7" + line);
        }
        List<String> contents = contentLines(kit, blueprints);
        if (!contents.isEmpty()) {
            if (!lore.isEmpty()) {
                lore.add("");
            }
            lore.addAll(contents);
        }
        lore.add("");
        lore.addAll(statusLines(availability, purchasable));

        String displayMode = !availability.canClaim() && !purchasable ? "DISABLED" : "NORMAL";
        String prompt = purchasable
                ? "Buy the kit \"" + name + "\" for " + gems(availability.premiumPriceGems()) + "? Click Confirm or Cancel."
                : null;
        return new KitMenuRow(availability.kitId(), name, lore, material(kit, blueprints), displayMode, purchasable, prompt,
                availability.cooldownExpiresAt(), clock);
    }

    private static List<String> statusLines(KnkKitAvailability availability, boolean purchasable) {
        List<String> lines = new ArrayList<>();
        if (availability.costAmount() != null && availability.costAmount() > 0) {
            lines.add("&7Cost: &f" + availability.costAmount() + " " + nullToEmpty(availability.costCurrency()).toLowerCase());
        }
        if (purchasable) {
            lines.add("&7One-time price: &f" + gems(availability.premiumPriceGems()));
            lines.add("&eClick to buy this kit");
        } else if (availability.canClaim()) {
            lines.add("&aClick to claim");
        } else {
            String reason = availability.denialReason();
            lines.add("&c" + (reason != null && !reason.isBlank() ? reason : "Not available"));
        }
        return lines;
    }

    private static List<String> contentLines(KnkKit kit, Map<Integer, BlueprintInfo> blueprints) {
        List<String> lines = new ArrayList<>();
        if (kit == null) {
            return lines;
        }
        addPiece(lines, "Helmet", kit.helmetId(), blueprints);
        addPiece(lines, "Chestplate", kit.chestplateId(), blueprints);
        addPiece(lines, "Leggings", kit.leggingsId(), blueprints);
        addPiece(lines, "Boots", kit.bootsId(), blueprints);
        addPiece(lines, "Hand", kit.handId(), blueprints);
        addPiece(lines, "Shield", kit.shieldId(), blueprints);
        if (kit.contents() != null && !kit.contents().isEmpty()) {
            List<String> others = new ArrayList<>();
            kit.contents().stream()
                    .sorted((a, b) -> Integer.compare(a.slotIndex(), b.slotIndex()))
                    .forEach(content -> {
                        BlueprintInfo info = blueprints.get(content.itemBlueprintId());
                        if (info != null) {
                            others.add(content.quantity() > 1 ? info.name() + " x" + content.quantity() : info.name());
                        }
                    });
            lines.addAll(otherContentLines(others));
        }
        if (!lines.isEmpty()) {
            lines.add(0, "&7Contents:");
        }
        return lines;
    }

    /**
     * PandiO/knk-workspace#172: the "Other contents" list wrapped at {@value #CONTENTS_WIDTH} visible characters
     * (the label counts on the first line; an item is never split, one longer than the width is cut with "…"),
     * at most {@value #CONTENTS_MAX_LINES} lines, the last of them "… and N more" when items didn't fit.
     */
    static List<String> otherContentLines(List<String> items) {
        List<String> result = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            return result;
        }
        List<List<String>> wrapped = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int width = OTHER_CONTENTS_LABEL.length();
        for (String raw : items) {
            String item = raw.length() > CONTENTS_WIDTH ? raw.substring(0, CONTENTS_WIDTH - 1) + "…" : raw;
            // +1: the comma that ends a line when the next item wraps.
            int needed = current.isEmpty() ? item.length() : 2 + item.length();
            if (!current.isEmpty() && width + needed + 1 > CONTENTS_WIDTH) {
                wrapped.add(current);
                current = new ArrayList<>();
                width = 0;
                needed = item.length();
            }
            current.add(item);
            width += needed;
        }
        wrapped.add(current);

        int shownLines = wrapped.size();
        int hidden = 0;
        if (wrapped.size() > CONTENTS_MAX_LINES) {
            shownLines = CONTENTS_MAX_LINES - 1;
            for (int i = shownLines; i < wrapped.size(); i++) {
                hidden += wrapped.get(i).size();
            }
        }
        for (int i = 0; i < shownLines; i++) {
            String text = String.join(", ", wrapped.get(i)) + (i < shownLines - 1 || hidden > 0 ? "," : "");
            result.add(i == 0 ? "&7" + OTHER_CONTENTS_LABEL + "&f" + text : "&f" + text);
        }
        if (hidden > 0) {
            result.add("&7… and " + hidden + " more");
        }
        return result;
    }

    private static void addPiece(List<String> lines, String label, Integer blueprintId, Map<Integer, BlueprintInfo> blueprints) {
        if (blueprintId == null) {
            return;
        }
        BlueprintInfo info = blueprints.get(blueprintId);
        if (info != null) {
            lines.add("&7- " + label + ": &f" + info.name());
        }
    }

    /** The hand item's material, else the chestplate's, else {@code CHEST} (plan CP2). */
    private static String material(KnkKit kit, Map<Integer, BlueprintInfo> blueprints) {
        if (kit != null) {
            for (Integer id : new Integer[] {kit.handId(), kit.chestplateId()}) {
                BlueprintInfo info = id != null ? blueprints.get(id) : null;
                if (info != null && info.materialKey() != null && !info.materialKey().isBlank()) {
                    return info.materialKey();
                }
            }
        }
        return FALLBACK_MATERIAL;
    }

    /** Greedy word wrap; blank/null → no lines. */
    static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return lines;
        }
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    static String formatRemaining(Duration remaining) {
        long seconds = Math.max(0, remaining.getSeconds() + (remaining.getNano() > 0 ? 1 : 0));
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        long secs = seconds % 60;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + secs + "s";
        }
        return secs + "s";
    }

    private static String gems(Integer amount) {
        return (amount != null ? amount : 0) + " gems";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public int getKitId() {
        return kitId;
    }

    public String getName() {
        return name;
    }

    public List<String> getLoreLines() {
        return loreLines;
    }

    public String getMaterial() {
        return material;
    }

    public String getDisplayMode() {
        return displayMode;
    }

    /** True for a not-yet-bought single-purchase premium kit: its click is a (confirmed) purchase, not a claim. */
    public boolean getIsPurchase() {
        return purchase;
    }

    /** Chat prompt for {@code menu.confirm.request}; null when the row isn't a purchase. */
    public String getPurchasePrompt() {
        return purchasePrompt;
    }

    /** "&cAvailable again in 4m 10s" while the viewer's cooldown runs, else null (line dropped, E8). */
    public String getCooldownText() {
        if (cooldownExpiresAt == null) {
            return null;
        }
        Duration remaining = Duration.between(Instant.now(clock), cooldownExpiresAt.toInstant());
        if (remaining.isNegative() || remaining.isZero()) {
            return null;
        }
        return "&cAvailable again in " + formatRemaining(remaining);
    }

    @Override
    public Object menuRowKey() {
        return List.of(kitId, name, loreLines, material, displayMode, purchase, String.valueOf(cooldownExpiresAt));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof KitMenuRow other && menuRowKey().equals(other.menuRowKey());
    }

    @Override
    public int hashCode() {
        return Objects.hash(menuRowKey());
    }
}
