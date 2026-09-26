package net.knightsandkings.knk.core.siege.menu;

import net.knightsandkings.knk.core.menu.MenuRowKey;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster.SpawnKind;
import net.knightsandkings.knk.core.siege.SiegeSpawnOptions.SpawnOption;

import java.util.List;
import java.util.Locale;

/**
 * Siege Phase 8b (MENU_TEMPLATES.md C.4): one respawn option of the viewer's team - a held objective
 * or a team spawnpoint. Row of {@code siege.spawn-options}.
 */
public final class SpawnOptionView implements MenuRowKey {

    private final SpawnOption option;
    private final String bannerPatterns;

    /** @param bannerPatterns the objective's banner (E6 string) for objective options, else null */
    public SpawnOptionView(SpawnOption option, String bannerPatterns) {
        this.option = option;
        this.bannerPatterns = bannerPatterns;
    }

    @Override
    public Object menuRowKey() {
        return "siege-spawn:" + getOption();
    }

    /** "spawnpoint" or "objective" - the prefix {@code /siege spawn} understands. */
    public String getKind() {
        return option.kind() == SpawnKind.OBJECTIVE ? "objective" : "spawnpoint";
    }

    public int getId() {
        return option.id();
    }

    /** The {@code siege.spawn} param: "kind:id". */
    public String getOption() {
        return getKind() + ":" + option.id();
    }

    public String getMaterial() {
        return option.kind() == SpawnKind.OBJECTIVE ? "WHITE_BANNER" : "GREEN_CONCRETE";
    }

    public String getBannerPatterns() {
        return option.kind() == SpawnKind.OBJECTIVE ? bannerPatterns : null;
    }

    /** DISABLED while contested, HIGHLIGHT for the current choice. */
    public String getDisplayMode() {
        if (!option.available()) return "DISABLED";
        return option.current() ? "HIGHLIGHT" : "NORMAL";
    }

    public String getName() {
        String name = option.name() == null || option.name().isBlank() ? "#" + option.id() : option.name().trim();
        return option.kind() == SpawnKind.OBJECTIVE ? "&7" + name : "&7Spawnpoint " + name;
    }

    public List<String> getStatusLines() {
        if (!option.available()) return List.of("&cCan't spawn here", "&cObjective is being captured!");
        if (option.current()) return List.of("&aCurrent spawnpoint");
        return List.of("&aClick to spawn here");
    }

    @Override
    public String toString() {
        return getOption().toLowerCase(Locale.ROOT);
    }
}
