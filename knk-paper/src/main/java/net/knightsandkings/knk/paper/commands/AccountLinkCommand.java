package net.knightsandkings.knk.paper.commands;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.api.dto.LinkCodeResponseDto;
import net.knightsandkings.knk.core.ports.api.UserAccountApi;
import net.knightsandkings.knk.paper.chat.ChatCaptureManager;
import net.knightsandkings.knk.paper.config.KnkConfig;
import net.knightsandkings.knk.paper.user.PlayerUserData;
import net.knightsandkings.knk.paper.user.UserManager;
import net.knightsandkings.knk.paper.utils.CommandCooldownManager;

/**
 * Command handler for /account link.
 * Generates a link code to enter on the website; a code typed in game only gets an explanation.
 */
public class AccountLinkCommand implements CommandExecutor {
    private final Plugin plugin;
    private final UserManager userManager;
    private final ChatCaptureManager chatCaptureManager;
    private final UserAccountApi userAccountApi;
    private final KnkConfig config;
    private final CommandCooldownManager cooldownManager;

    public AccountLinkCommand(
        Plugin plugin,
        UserManager userManager,
        ChatCaptureManager chatCaptureManager,
        UserAccountApi userAccountApi,
        KnkConfig config,
        CommandCooldownManager cooldownManager
    ) {
        this.plugin = plugin;
        this.userManager = userManager;
        this.chatCaptureManager = chatCaptureManager;
        this.userAccountApi = userAccountApi;
        this.config = config;
        this.cooldownManager = cooldownManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command");
            return true;
        }

        if (args.length == 1) {
            // Codes only go game -> web (D1): a code typed here is never sent anywhere or logged.
            explainWhereCodesAreEntered(player);
            return true;
        }

        PlayerUserData userData = userManager.getCachedUser(player.getUniqueId());
        if (userData == null) {
            sendPrefixed(player, "&cPlease rejoin the server and try again");
            plugin.getLogger().warning("Player " + player.getName() + " has no cached user data for /account link");
            return true;
        }

        if (args.length == 0) {
            plugin.getLogger().fine(player.getName() + " executing /account link (generate code)");
            generateLinkCode(player, userData);
        } else {
            sendPrefixed(player, "&cUsage: /account link");
        }

        return true;
    }

    private void generateLinkCode(Player player, PlayerUserData userData) {
        // Check cooldown for link code generation
        int cooldownSeconds = config.account().cooldowns().linkCodeGenerateSeconds();
        if (!cooldownManager.canExecute(player.getUniqueId(), "link.generate", cooldownSeconds)) {
            int remaining = cooldownManager.getRemainingCooldown(player.getUniqueId(), "link.generate", cooldownSeconds);
            sendPrefixed(player, "&cPlease wait " + remaining + " seconds before generating another link code.");
            plugin.getLogger().fine(player.getName() + " attempted link code generation but is on cooldown (" + remaining + "s remaining)");
            return;
        }

        if (userData.userId() == null) {
            sendPrefixed(player, "&cAccount not ready yet. Please rejoin and try again.");
            plugin.getLogger().warning("Link code generation for " + player.getName() + " failed: user ID not available");
            return;
        }

        plugin.getLogger().info("Generating link code for " + player.getName() + " (ID: " + userData.userId() + ")");
        cooldownManager.recordExecution(player.getUniqueId(), "link.generate");

        userAccountApi.generateLinkCode(userData.userId())
            .thenAccept(responseObj -> {
                LinkCodeResponseDto response = (LinkCodeResponseDto) responseObj;
                runSync(() -> {
                    String formattedCode = response.formattedCode() != null ? response.formattedCode() : response.code();
                    for (String line : LinkCodeMessage.formatGenerated(config.messages().linkCodeGenerated(),
                            formattedCode, config.account().linkCodeExpiryMinutes(), config.web().publicUrl())) {
                        player.sendMessage(LinkCodeMessage.toComponent(config.messages().prefix() + line, formattedCode));
                    }
                    // Never log the code itself: whoever holds it can register the player's web login.
                    plugin.getLogger().info("Link code generated for " + player.getName());
                });
            })
            .exceptionally(ex -> {
                plugin.getLogger().severe("Failed to generate link code for " + player.getName() + ": " + ex.getMessage());
                if (ex.getCause() != null) {
                    plugin.getLogger().severe("  Cause: " + ex.getCause().getMessage());
                }
                runSync(() -> {
                    sendPrefixed(player, "&cFailed to generate link code");
                    // Reset cooldown on failure
                    cooldownManager.resetCooldown(player.getUniqueId(), "link.generate");
                });
                return null;
            });
    }

    /**
     * {@code /account link <code>}: link codes come from the game and are entered on the website (D1), so a
     * code typed in game is not validated or used. Tell the player where codes go instead.
     */
    private void explainWhereCodesAreEntered(Player player) {
        plugin.getLogger().info(player.getName() + " tried to enter a link code in game");
        for (String line : LinkCodeMessage.format(config.messages().linkCodeEnteredInGame(), "",
                config.account().linkCodeExpiryMinutes(), config.web().publicUrl())) {
            player.sendMessage(LinkCodeMessage.toComponent(config.messages().prefix() + line, ""));
        }
    }

    private void sendPrefixed(CommandSender sender, String message) {
        sender.sendMessage(colorize(config.messages().prefix() + message));
    }

    private String colorize(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private void runSync(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }
}