package net.knightsandkings.knk.paper.statistics;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * What a player sees of their AFK state (DESIGN.md §F.2): a private message when entering/leaving
 * AFK and - with {@code statistics.afk.tab-list-marker} - the marker appended to their tab-list name,
 * which keeps their group color (scoreboard team). The previous custom name (none, usually) is
 * remembered and put back when they leave AFK, unless something else (e.g. the
 * Siege scoreboard) has redrawn the name meanwhile - then it is left alone. Not carried over from V1:
 * Siege removal, armour stands, kicks. Main thread.
 */
public final class AfkPresentation {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private record Marked(Component previous, Component marked) {
    }

    private final boolean tabListMarker;
    private final Component marker;
    private final Map<UUID, Marked> marked = new HashMap<>();

    public AfkPresentation(boolean tabListMarker, String markerText) {
        this.tabListMarker = tabListMarker;
        this.marker = LEGACY.deserialize(markerText == null || markerText.isBlank() ? "&7[AFK]" : markerText);
    }

    /** {@code manual}: entered with {@code /afk}; otherwise after the idle threshold. */
    public void entered(Player player, boolean manual) {
        player.sendMessage(LEGACY.deserialize(manual
                ? "&7You are now AFK. Move, chat or type &f/afk &7again to come back."
                : "&7You are now AFK - you haven't done anything for a while."));
        applyMarker(player);
    }

    public void left(Player player) {
        player.sendMessage(LEGACY.deserialize("&7You are no longer AFK."));
        removeMarker(player);
    }

    /** The player quit: forget the remembered name (it is gone with the player). */
    public void forget(UUID playerId) {
        marked.remove(playerId);
    }

    /** Plugin disable: put every remembered name back. */
    public void restoreAll(java.util.function.Function<UUID, Player> online) {
        for (UUID playerId : new java.util.ArrayList<>(marked.keySet())) {
            Player player = online.apply(playerId);
            if (player != null) {
                removeMarker(player);
            }
        }
        marked.clear();
    }

    private void applyMarker(Player player) {
        if (!tabListMarker || marked.containsKey(player.getUniqueId())) {
            return;
        }
        Component previous = customListName(player);
        Component withMarker = (previous == null ? teamStyledName(player) : previous)
                .append(Component.space()).append(marker);
        player.playerListName(withMarker);
        marked.put(player.getUniqueId(), new Marked(previous, withMarker));
    }

    private void removeMarker(Player player) {
        Marked remembered = marked.remove(player.getUniqueId());
        if (remembered == null) {
            return;
        }
        if (Objects.equals(player.playerListName(), remembered.marked())) {
            // null clears the custom name, so the client draws the team-colored name again.
            player.playerListName(remembered.previous());
        }
    }

    /**
     * The custom tab-list name, or null when there is none - Paper then reports the plain name,
     * and putting that back as a custom name would hide the scoreboard team's color (finding 3).
     */
    static Component customListName(Player player) {
        Component current = player.playerListName();
        return current == null || current.equals(Component.text(player.getName())) ? null : current;
    }

    /**
     * The name as the tab list draws it without a custom name: the scoreboard team's prefix, color
     * and suffix (KNG-7 group colors, ScoreboardUtil). A custom name skips the team formatting on
     * the client, so the marked name has to carry it itself.
     */
    static Component teamStyledName(Player player) {
        Component name = Component.text(player.getName());
        Scoreboard scoreboard = player.getScoreboard();
        Team team = scoreboard == null ? null : scoreboard.getEntryTeam(player.getName());
        if (team == null) {
            return name;
        }
        if (team.hasColor()) {
            name = name.color(team.color());
        }
        return Component.empty().append(team.prefix()).append(name).append(team.suffix());
    }
}
