package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.duel.DuelOfferBook;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The technical knobs of {@code config.yml} {@code duels:} (arena duels DESIGN §12). Gameplay settings live on the
 * Arena and are edited in the web app.
 *
 * @param commandAllowList       commands a duelist may use in the countdown and the fight
 * @param itemBlocklist          materials that can't be staked (upper case)
 * @param apiTimeoutSeconds      how long a lock-in waits for the API before it reads the match by token
 * @param resultRetrySeconds     how often spooled results and the startup recovery are retried
 * @param heldRefreshSeconds     how often a held result is checked for a staff release/void
 */
public record DuelConfig(
        boolean enabled,
        int maxIncoming,
        int sendCooldownSeconds,
        int agreedDrawWindowSeconds,
        List<String> commandAllowList,
        Set<String> itemBlocklist,
        boolean refuseFilledContainers,
        int announceCooldownSeconds,
        int apiTimeoutSeconds,
        int resultRetrySeconds,
        int heldRefreshSeconds
) {
    public static final List<String> DEFAULT_COMMANDS = List.of("/duel", "/msg", "/r", "/staffchat");
    public static final List<String> DEFAULT_BLOCKLIST = List.of("WRITTEN_BOOK", "BARRIER", "COMMAND_BLOCK",
            "STRUCTURE_VOID", "DEBUG_STICK");

    public DuelConfig {
        maxIncoming = Math.max(1, maxIncoming);
        sendCooldownSeconds = Math.max(0, sendCooldownSeconds);
        agreedDrawWindowSeconds = Math.max(1, agreedDrawWindowSeconds);
        commandAllowList = commandAllowList == null ? DEFAULT_COMMANDS : List.copyOf(commandAllowList);
        itemBlocklist = itemBlocklist == null ? Set.of() : itemBlocklist.stream()
                .map(name -> name.trim().toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        announceCooldownSeconds = Math.max(0, announceCooldownSeconds);
        apiTimeoutSeconds = Math.max(1, apiTimeoutSeconds);
        resultRetrySeconds = Math.max(5, resultRetrySeconds);
        heldRefreshSeconds = Math.max(30, heldRefreshSeconds);
    }

    public static DuelConfig defaults() {
        return new DuelConfig(true, DuelOfferBook.DEFAULT_MAX_INCOMING, DuelOfferBook.DEFAULT_SEND_COOLDOWN_SECONDS, 15,
                DEFAULT_COMMANDS, Set.copyOf(DEFAULT_BLOCKLIST), true, 120, 10, 30, 300);
    }

    /** From {@code config.yml}'s {@code duels:} section; a missing section or key keeps its default. */
    public static DuelConfig from(ConfigurationSection section) {
        DuelConfig d = defaults();
        if (section == null) return d;
        List<String> commands = section.isList("command-allow-list") ? section.getStringList("command-allow-list")
                : d.commandAllowList();
        List<String> blocked = section.isList("item-blocklist") ? section.getStringList("item-blocklist")
                : DEFAULT_BLOCKLIST;
        return new DuelConfig(
                section.getBoolean("enabled", d.enabled()),
                section.getInt("invite.max-incoming", d.maxIncoming()),
                section.getInt("invite.send-cooldown-seconds", d.sendCooldownSeconds()),
                section.getInt("agreed-draw-window-seconds", d.agreedDrawWindowSeconds()),
                commands,
                Set.copyOf(blocked),
                section.getBoolean("refuse-filled-containers", d.refuseFilledContainers()),
                section.getInt("announce.cooldown-seconds", d.announceCooldownSeconds()),
                section.getInt("api-timeout-seconds", d.apiTimeoutSeconds()),
                section.getInt("result-retry-seconds", d.resultRetrySeconds()),
                section.getInt("held-refresh-seconds", d.heldRefreshSeconds()));
    }
}
