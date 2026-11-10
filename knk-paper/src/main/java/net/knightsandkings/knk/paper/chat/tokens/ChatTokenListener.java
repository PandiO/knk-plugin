package net.knightsandkings.knk.paper.chat.tokens;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Chat hashtag tokens in public chat (PandiO/knk-workspace#233). Runs at HIGH, after the chat-capture and world-task
 * listeners (which cancel the event for their prompts, so tokens never expand there) and after
 * {@code PlayerListener.onChat} set the coloured message; the chat line renderer uses the message this sets.
 * <p>
 * Paper fires player chat on an async thread, so waiting here (at most {@link ChatTokenService#LOOKUP_TIMEOUT} per
 * value) doesn't hold the server; chat order is kept. A synchronous chat event (a plugin calling
 * {@code Player#chat} on the main thread, as the share prompt's resend does) only uses values that are ready.
 */
public final class ChatTokenListener implements Listener {

    private static final Logger LOGGER = Logger.getLogger(ChatTokenListener.class.getName());
    /** Upper bound for one message: every lookup runs in parallel, each bounded by LOOKUP_TIMEOUT. */
    private static final long WAIT_MILLIS = ChatTokenService.LOOKUP_TIMEOUT.toMillis() + 500;

    private final ChatTokenService service;
    private final Executor mainThread;
    private final Executor later;

    /** @param later runs the join prefetch a few seconds after the join (the user summary is loaded by then) */
    public ChatTokenListener(ChatTokenService service, Executor mainThread, Executor later) {
        this.service = service;
        this.mainThread = mainThread;
        this.later = later;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String typed = PlainTextComponentSerializer.plainText().serialize(event.originalMessage());
        Runnable resend = () -> mainThread.execute(() -> {
            if (player.isOnline()) {
                player.chat(typed);
            }
        });
        boolean sync = !event.isAsynchronous();
        ChatTokenService.Result result;
        try {
            CompletableFuture<ChatTokenService.Result> expansion = service.expand(player, event.message(), resend, sync);
            result = sync ? expansion.getNow(null) : expansion.get(WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "chat tokens of " + player.getName() + " sent as text", e);
            return;
        }
        if (result == null) {
            return;
        }
        switch (result.outcome()) {
            case UNCHANGED -> { }
            case EXPANDED -> event.message(result.message());
            case HELD -> event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        var id = event.getPlayer().getUniqueId();
        later.execute(() -> service.prefetch(id));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        service.forget(event.getPlayer().getUniqueId());
    }
}
