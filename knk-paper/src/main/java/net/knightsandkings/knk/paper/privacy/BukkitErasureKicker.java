package net.knightsandkings.knk.paper.privacy;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.knightsandkings.knk.core.privacy.AccountErasureService;
import net.knightsandkings.knk.core.privacy.ErasedAccount;

/**
 * Kicks the player of an erased account if they are online (PandiO/knk-workspace#184), with the neutral
 * {@code realtime.erasure.kick-message}, on the main thread. An account without a former UUID can't be online here.
 */
public final class BukkitErasureKicker implements AccountErasureService.Kicker {

    private final Executor mainThread;
    private final Function<UUID, Player> onlinePlayer;
    private final String message;

    public BukkitErasureKicker(Executor mainThread, Function<UUID, Player> onlinePlayer, String message) {
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.onlinePlayer = Objects.requireNonNull(onlinePlayer, "onlinePlayer");
        this.message = Objects.requireNonNull(message, "message");
    }

    @Override
    public CompletableFuture<Boolean> kick(ErasedAccount account) {
        if (account.formerUuid() == null) {
            return CompletableFuture.completedFuture(false);
        }
        return CompletableFuture.supplyAsync(() -> {
            Player player = onlinePlayer.apply(account.formerUuid());
            if (player == null || !player.isOnline()) {
                return false;
            }
            player.kick(Component.text(message));
            return true;
        }, mainThread);
    }
}
