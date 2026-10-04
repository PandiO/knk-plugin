package net.knightsandkings.knk.paper.commands;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /staffchat (/sc) &lt;message&gt; - rebuild of v1's StaffChatCommand. A one-shot broadcast per
 * invocation, not a toggleable channel (v1 never had a "stay in staff chat" mode - confirmed by
 * reading StaffChatCommand.java and its companion StaffChatEvents.java, whose one listener was
 * entirely commented out). Broadcasts to every online holder of knk.staffchat.
 * <p>
 * Using the command is gated on knk.staffchat by {@code PermissionGatedCommand} (KNG-24). The
 * audience is asked the same way - a Bukkit grant or an in-house one - per recipient, so a non-op
 * staff member whose group grants the node receives it too (a bare {@code hasPermission} only saw
 * ops). The sender always sees their own message.
 */
public class StaffChatCommand implements CommandExecutor {
    public static final String NODE = "knk.staffchat";

    private final BiFunction<Player, String, CompletableFuture<Boolean>> audienceCheck;
    private final Executor mainThread;
    private final Supplier<Collection<? extends Player>> onlinePlayers;
    private final Consumer<Player> chime;

    /** Bukkit-only audience (no in-house permission model). */
    public StaffChatCommand() {
        this((player, node) -> CompletableFuture.completedFuture(player.hasPermission(node)), Runnable::run,
                Bukkit::getOnlinePlayers);
    }

    public StaffChatCommand(BiFunction<Player, String, CompletableFuture<Boolean>> audienceCheck, Executor mainThread,
                            Supplier<Collection<? extends Player>> onlinePlayers) {
        this(audienceCheck, mainThread, onlinePlayers,
                player -> player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.0f));
    }

    /** {@code chime} is played to each recipient; injectable because {@link Sound} needs a server. */
    StaffChatCommand(BiFunction<Player, String, CompletableFuture<Boolean>> audienceCheck, Executor mainThread,
                     Supplier<Collection<? extends Player>> onlinePlayers, Consumer<Player> chime) {
        this.audienceCheck = audienceCheck;
        this.mainThread = mainThread;
        this.onlinePlayers = onlinePlayers;
        this.chime = chime;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /staffchat <message>");
            return true;
        }
        String message = String.join(" ", args);
        String senderName = sender instanceof Player ? sender.getName() : "CONSOLE";
        String formatted = ChatColor.LIGHT_PURPLE + "[Staff] " + ChatColor.WHITE + senderName + ChatColor.GRAY + ": " + ChatColor.WHITE + message;

        deliver(sender, formatted);
        for (Player online : onlinePlayers.get()) {
            if (online.equals(sender)) {
                continue;
            }
            audienceCheck.apply(online, NODE)
                    .exceptionally(ex -> false)
                    .thenAccept(allowed -> {
                        if (Boolean.TRUE.equals(allowed)) {
                            mainThread.execute(() -> {
                                if (online.isOnline()) {
                                    deliver(online, formatted);
                                }
                            });
                        }
                    });
        }
        return true;
    }

    private void deliver(CommandSender recipient, String formatted) {
        recipient.sendMessage(formatted);
        if (recipient instanceof Player player) {
            chime.accept(player);
        }
    }
}
