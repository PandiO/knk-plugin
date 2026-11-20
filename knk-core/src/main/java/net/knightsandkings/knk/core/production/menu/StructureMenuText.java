package net.knightsandkings.knk.core.production.menu;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The text of the in-game structure menus (PandiO/knk-workspace#74, production DESIGN § 11.2, M1-M5), with {@code &}
 * colour codes. Layouts follow V2's {@code ProductionStructureMenu} and {@code StorageOverview}: a summary, then
 * "Amount: N" per item. No Bukkit here, so every line is unit tested.
 */
public final class StructureMenuText {

    /** Lines of a storage's contents shown in one row's lore. */
    public static final int MAX_CONTENT_LINES = 12;
    /** Item material per production kind name (lower case); anything else is a crafting table. */
    static final Map<String, String> KIND_MATERIALS = Map.ofEntries(
            Map.entry("warehouse", "CHEST"),
            Map.entry("farm", "WHEAT"),
            Map.entry("logger", "OAK_LOG"),
            Map.entry("lumberjack", "OAK_LOG"),
            Map.entry("quarry", "COBBLESTONE"),
            Map.entry("mine", "IRON_PICKAXE"),
            Map.entry("fishery", "FISHING_ROD"),
            Map.entry("florist", "POPPY"),
            Map.entry("pasture", "WHITE_WOOL"),
            Map.entry("workshop", "CRAFTING_TABLE"));

    private StructureMenuText() {
    }

    /** The item of a structure: by kind (a warehouse is a chest). */
    public static String material(StructureRow row) {
        if (row == null) {
            return "BARRIER";
        }
        if (row.warehouse()) {
            return "CHEST";
        }
        String kind = row.kindName() == null ? "" : row.kindName().trim().toLowerCase(Locale.ROOT);
        return KIND_MATERIALS.getOrDefault(kind, "CRAFTING_TABLE");
    }

    /** Working green, Stalled gold, NoRoad and Full red. */
    public static String statusColour(String status) {
        if (status == null) {
            return "&7";
        }
        return switch (status) {
            case "Working" -> "&a";
            case "Stalled" -> "&6";
            case "NoRoad", "Full" -> "&c";
            default -> "&7";
        };
    }

    /** "No road" for NoRoad; the rest as they are. */
    public static String statusName(String status) {
        if (status == null || status.isBlank()) {
            return "Unknown";
        }
        return "NoRoad".equals(status) ? "No road" : status;
    }

    /** The public lines of a structure (M3: what every player may see): kind, town, level, products and status. */
    public static List<String> summaryLines(StructureRow row) {
        List<String> lines = new ArrayList<>();
        if (row == null) {
            lines.add("&7Loading...");
            return lines;
        }
        lines.add("&7Kind: &f" + (row.warehouse() ? "Warehouse" : nonBlank(row.kindName(), "Production")));
        lines.add("&7Town: &f" + (row.townName() != null && !row.townName().isBlank() ? row.townName() : "none")
                + (row.rural() ? " &8(rural)" : ""));
        if (row.level() != null) {
            lines.add("&7Level: &f" + row.level());
        }
        if (!row.products().isEmpty()) {
            lines.add("&7Produces: &f" + String.join(", ", row.products()));
        }
        lines.add("&7Status: " + statusColour(row.status()) + statusName(row.status())
                + (row.statusText() != null && !row.statusText().isBlank() ? " &8- &7" + row.statusText() : ""));
        return lines;
    }

    /** "&eProduces 12.5 Cobblestone per hour" per line, with the reasons a line is not producing. */
    public static List<String> rateLines(StructureMenu menu) {
        List<String> lines = new ArrayList<>();
        if (menu == null || !menu.showsDetail()) {
            return lines;
        }
        for (StructureMenu.Rate rate : menu.detail().rates()) {
            String name = nonBlank(rate.itemBlueprintName(), "Item #" + rate.itemBlueprintId());
            lines.add("&f" + name + "&7: &e" + perHour(rate.ratePerHour()) + " per hour"
                    + (rate.status() != null && !rate.status().isBlank() && !"Running".equalsIgnoreCase(rate.status())
                            ? " &8(" + rate.status() + ")" : ""));
            for (String reason : rate.notProducingReasons()) {
                lines.add("  &c" + reason);
            }
        }
        if (lines.isEmpty()) {
            lines.add("&7Nothing is produced here.");
        }
        return lines;
    }

    /** Workers present, away and in total. */
    public static List<String> workerLines(StructureMenu menu) {
        List<String> lines = new ArrayList<>();
        if (menu == null || !menu.showsDetail()) {
            return lines;
        }
        StructureMenu.Detail d = menu.detail();
        if (d.workersTotal() == null || d.workersTotal() == 0) {
            lines.add("&7No workers.");
            return lines;
        }
        lines.add("&7Present: &f" + nz(d.workersPresent()) + " &7of &f" + d.workersTotal());
        lines.add("&7Away: &f" + nz(d.workersAway()));
        return lines;
    }

    /** Warnings and reasons it is not producing; "No warnings." without any. */
    public static List<String> warningLines(StructureMenu menu) {
        List<String> lines = new ArrayList<>();
        if (menu == null || !menu.showsDetail()) {
            return lines;
        }
        for (String warning : menu.detail().warnings()) {
            lines.add("&c" + warning);
        }
        for (String reason : menu.detail().notProducingReasons()) {
            lines.add("&6" + reason);
        }
        if (lines.isEmpty()) {
            lines.add("&aNo warnings.");
        }
        return lines;
    }

    /** "&73 open orders". */
    public static String ordersLine(StructureMenu menu) {
        if (menu == null || !menu.showsDetail()) {
            return "";
        }
        int count = menu.detail().openOrderCount();
        return "&7" + count + " open order" + (count == 1 ? "" : "s");
    }

    /** "&7Filled: 40 of 1,000 units (4%)", or the units without a cap. */
    public static String fillLine(StructureMenu.StorageView storage) {
        if (storage == null) {
            return "";
        }
        if (storage.maxUnits() == null || storage.maxUnits() <= 0) {
            return "&7Holds &f" + grouped(storage.totalUnits()) + " &7units";
        }
        long percent = Math.round(100.0 * storage.totalUnits() / storage.maxUnits());
        return "&7Filled: &f" + grouped(storage.totalUnits()) + " &7of &f" + grouped(storage.maxUnits()) + " &7units ("
                + percent + "%)";
    }

    /** V2 StorageOverview: "&fCobblestone&7: Amount: 40" per line, at most {@link #MAX_CONTENT_LINES}. */
    public static List<String> contentLines(StructureMenu.StorageView storage) {
        List<String> lines = new ArrayList<>();
        if (storage == null) {
            return lines;
        }
        if (storage.lines().isEmpty()) {
            lines.add("&7Empty.");
            return lines;
        }
        int shown = 0;
        for (StructureMenu.Line line : storage.lines()) {
            if (shown == MAX_CONTENT_LINES) {
                lines.add("&8... and " + (storage.lines().size() - shown) + " more");
                break;
            }
            String name = line.customDisplayName() != null && !line.customDisplayName().isBlank() ? line.customDisplayName()
                    : nonBlank(line.itemBlueprintName(), "Item #" + line.itemBlueprintId());
            lines.add("&f" + name + "&7: Amount: &f" + grouped(line.amount()));
            shown++;
        }
        return lines;
    }

    /** One order: cargo units, from, to, state and expected arrival (relative to {@code now}). */
    public static List<String> orderLines(TransportOrderRow order, Instant now) {
        List<String> lines = new ArrayList<>();
        if (order == null) {
            return lines;
        }
        lines.add("&7Cargo: &f" + grouped(order.unitsRequested()) + " &7units"
                + (order.unitsDelivered() > 0 ? " &8(" + grouped(order.unitsDelivered()) + " delivered)" : "")
                + (order.unitsReturned() > 0 ? " &8(" + grouped(order.unitsReturned()) + " returned)" : ""));
        lines.add("&7From: &f" + nonBlank(order.sourceStructureName(), nonBlank(order.sourceStorageName(), "?")));
        lines.add("&7To: &f" + nonBlank(order.destinations(), "?"));
        if (order.statusReason() != null && !order.statusReason().isBlank()) {
            lines.add("&8" + order.statusReason());
        }
        if (order.open() && order.etaAt() != null) {
            lines.add("&7Arrives: &f" + relative(order.etaAt(), now));
        } else if (order.completedAt() != null) {
            lines.add("&7Finished: &f" + relative(order.completedAt(), now));
        }
        if (order.origin() != null && !order.origin().isBlank()) {
            lines.add("&8" + order.origin());
        }
        return lines;
    }

    /** Material of an order by state: moving minecart, done chest, failed barrier, waiting clock. */
    public static String orderMaterial(TransportOrderRow order) {
        if (order == null || order.status() == null) {
            return "PAPER";
        }
        return switch (order.status()) {
            case "InTransit", "Returning", "AtStop", "Loading" -> "MINECART";
            case "Delivered" -> "CHEST_MINECART";
            case "Returned", "Cancelled" -> "BARRIER";
            case "Lost", "Robbed", "Ambushed" -> "TNT_MINECART";
            default -> "CLOCK";
        };
    }

    /** The staff hint under an order: which clicks do what; empty when nothing can be done. */
    public static String staffHint(TransportOrderRow order) {
        if (order == null) {
            return "";
        }
        if (order.canCancel() && order.canRetry()) {
            return "&eStaff: click to cancel or run now";
        }
        if (order.canCancel()) {
            return "&eStaff: click to cancel";
        }
        if (order.canRetry()) {
            return "&eStaff: click to retry";
        }
        return "";
    }

    /** "in 4 min", "2 h ago", "now". */
    public static String relative(Instant at, Instant now) {
        if (at == null || now == null) {
            return "?";
        }
        Duration d = Duration.between(now, at);
        boolean past = d.isNegative();
        long seconds = Math.abs(d.getSeconds());
        if (seconds < 60) {
            return "now";
        }
        String amount = seconds < 3600 ? (seconds / 60) + " min" : seconds < 86400 ? (seconds / 3600) + " h" : (seconds / 86400) + " d";
        return past ? amount + " ago" : "in " + amount;
    }

    /** 12.5, 3, 0.25: at most two decimals. */
    public static String perHour(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        String text = String.format(Locale.ROOT, "%.2f", value);
        return text.endsWith("0") ? text.substring(0, text.length() - 1) : text;
    }

    public static String grouped(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
