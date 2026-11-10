package net.knightsandkings.knk.paper.chat.tokens;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.knightsandkings.knk.core.chat.tokens.ChatToken;
import net.knightsandkings.knk.core.chat.tokens.ChatTokenCompletions;
import net.knightsandkings.knk.paper.commands.support.VisiblePlayers;

/**
 * Keeps each player's chat completions in step with the tokens they may use and the players they can see
 * (PandiO/knk-workspace#233): {@code #kills@} + Tab offers every visible online player. Players hidden by staff or
 * owner mode are left out for viewers who can't see them ({@link VisiblePlayers#canSee}).
 * <p>
 * Refreshed for everyone a tick after a join or quit and every {@link #REFRESH_TICKS} ticks, so a mode switch or a
 * permission change shows up within seconds. Only the difference is sent to the client. Main thread only.
 */
public final class ChatTokenCompletionSync implements Listener {

    public static final long REFRESH_TICKS = 100L;

    private final BiPredicate<Player, String> permission;
    private final Supplier<Collection<? extends Player>> onlinePlayers;
    private final Consumer<Runnable> nextTick;
    private final Map<UUID, List<String>> applied = new HashMap<>();

    /** @param permission the cache-only {@code knk.chat.token.<name>} check */
    public ChatTokenCompletionSync(BiPredicate<Player, String> permission, Supplier<Collection<? extends Player>> onlinePlayers,
                                   Consumer<Runnable> nextTick) {
        this.permission = permission;
        this.onlinePlayers = onlinePlayers;
        this.nextTick = nextTick;
    }

    public void refreshAll() {
        Collection<? extends Player> online = onlinePlayers.get();
        for (Player viewer : online) {
            refresh(viewer, online);
        }
    }

    void refresh(Player viewer, Collection<? extends Player> online) {
        Set<ChatToken> allowed = EnumSet.noneOf(ChatToken.class);
        for (ChatToken token : ChatToken.all()) {
            if (permission.test(viewer, token.node())) {
                allowed.add(token);
            }
        }
        List<String> others = online.stream()
            .filter(player -> !player.getUniqueId().equals(viewer.getUniqueId()))
            .filter(player -> VisiblePlayers.canSee(viewer, player))
            .map(Player::getName)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
        List<String> wanted = ChatTokenCompletions.forViewer(allowed, others);
        List<String> had = applied.getOrDefault(viewer.getUniqueId(), List.of());
        if (wanted.equals(had)) {
            return;
        }
        Set<String> removed = new LinkedHashSet<>(had);
        wanted.forEach(removed::remove);
        Set<String> added = new LinkedHashSet<>(wanted);
        had.forEach(added::remove);
        if (!removed.isEmpty()) {
            viewer.removeCustomChatCompletions(removed);
        }
        if (!added.isEmpty()) {
            viewer.addCustomChatCompletions(added);
        }
        applied.put(viewer.getUniqueId(), wanted);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // A fresh client has no completions: forget what an earlier session of this player had.
        applied.remove(event.getPlayer().getUniqueId());
        nextTick.accept(this::refreshAll);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        applied.remove(event.getPlayer().getUniqueId());
        nextTick.accept(this::refreshAll);
    }
}
