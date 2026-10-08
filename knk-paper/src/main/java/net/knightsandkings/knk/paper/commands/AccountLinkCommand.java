package net.knightsandkings.knk.paper.commands;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.api.dto.DuplicateCheckResponseDto;
import net.knightsandkings.knk.api.dto.LinkCodeResponseDto;
import net.knightsandkings.knk.api.dto.MergeAccountsRequestDto;
import net.knightsandkings.knk.api.dto.UserResponseDto;
import net.knightsandkings.knk.api.dto.ValidateLinkCodeResponseDto;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.ports.api.UserAccountApi;
import net.knightsandkings.knk.paper.KnKPlugin;
import net.knightsandkings.knk.paper.chat.ChatCaptureManager;
import net.knightsandkings.knk.paper.config.KnkConfig;
import net.knightsandkings.knk.paper.user.PlayerUserData;
import net.knightsandkings.knk.paper.user.UserManager;
import net.knightsandkings.knk.paper.utils.CommandCooldownManager;

/**
 * Command handler for /account link.
 * Generates link codes or consumes a provided link code.
 */
public class AccountLinkCommand implements CommandExecutor {
    private final KnKPlugin plugin;
    private final UserManager userManager;
    private final ChatCaptureManager chatCaptureManager;
    private final UserAccountApi userAccountApi;
    private final KnkConfig config;
    private final CommandCooldownManager cooldownManager;

    public AccountLinkCommand(
        KnKPlugin plugin,
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

        PlayerUserData userData = userManager.getCachedUser(player.getUniqueId());
        if (userData == null) {
            sendPrefixed(player, "&cPlease rejoin the server and try again");
            plugin.getLogger().warning("Player " + player.getName() + " has no cached user data for /account link");
            return true;
        }

        if (args.length == 0) {
            plugin.getLogger().fine(player.getName() + " executing /account link (generate code)");
            generateLinkCode(player, userData);
        } else if (args.length == 1) {
            plugin.getLogger().fine(player.getName() + " executing /account link with code");
            consumeLinkCode(player, userData, args[0]);
        } else {
            sendPrefixed(player, "&cUsage: /account link [code]");
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
                    for (String line : LinkCodeMessage.format(config.messages().linkCodeGenerated(), formattedCode,
                            config.account().linkCodeExpiryMinutes(), config.web().publicUrl())) {
                        sendPrefixed(player, line);
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

    private void consumeLinkCode(Player player, PlayerUserData userData, String code) {
        // Check cooldown for link code consumption
        int cooldownSeconds = config.account().cooldowns().linkCodeConsumeSeconds();
        if (!cooldownManager.canExecute(player.getUniqueId(), "link.consume", cooldownSeconds)) {
            int remaining = cooldownManager.getRemainingCooldown(player.getUniqueId(), "link.consume", cooldownSeconds);
            sendPrefixed(player, "&cPlease wait " + remaining + " seconds before trying another link code.");
            plugin.getLogger().fine(player.getName() + " attempted link code consumption but is on cooldown (" + remaining + "s remaining)");
            return;
        }

        plugin.getLogger().info(player.getName() + " is entering a link code");
        cooldownManager.recordExecution(player.getUniqueId(), "link.consume");
        
        // Validate link code (minecraft-first flow)
        userAccountApi.validateLinkCode(code)
            .thenAccept(validationObj -> {
                ValidateLinkCodeResponseDto validation = (ValidateLinkCodeResponseDto) validationObj;
                if (!Boolean.TRUE.equals(validation.isValid())) {
                    plugin.getLogger().info("Invalid link code provided by " + player.getName());
                    runSync(() -> {
                        sendPrefixed(player, config.messages().invalidLinkCode());
                        // Reset cooldown on failure
                        cooldownManager.resetCooldown(player.getUniqueId(), "link.consume");
                    });
                    return;
                }

                // The API only says whether the code is valid and whose it is (no user id or email
                // since the closed-alpha hardening), and validating doesn't link anything server-side.
                // Keep the player's own identity in the cache: their Minecraft name stays their username.
                plugin.getLogger().fine("Link code validated for " + player.getName()
                    + (validation.username() != null ? " (code belongs to " + validation.username() + ")" : ""));

                runSync(() -> {
                    PlayerUserData current = userManager.getCachedUser(player.getUniqueId());
                    PlayerUserData base = current != null ? current : userData;
                    PlayerUserData updated = new PlayerUserData(
                        base.userId(),
                        base.username() != null ? base.username() : player.getName(),
                        player.getUniqueId(),
                        base.email(),
                        base.coins(),
                        base.gems(),
                        base.experiencePoints(),
                        base.hasEmailLinked(),
                        false,
                        null,
                        base.gatePassThroughMethodDefault() != null
                            ? base.gatePassThroughMethodDefault() : GatePassThroughMethod.DEFAULT
                    );

                    userManager.updateCachedUser(player.getUniqueId(), updated);
                    sendPrefixed(player, config.messages().accountLinked());
                    plugin.getLogger().info("Link code accepted for " + player.getName());
                });
            })
            .exceptionally(ex -> {
                plugin.getLogger().severe("Failed to validate link code for " + player.getName() + ": " + ex.getMessage());
                if (ex.getCause() != null) {
                    plugin.getLogger().severe("  Cause: " + ex.getCause().getMessage());
                }
                runSync(() -> {
                    sendPrefixed(player, "&cFailed to link account");
                    // Reset cooldown on failure
                    cooldownManager.resetCooldown(player.getUniqueId(), "link.consume");
                });
                return null;
            });
    }

    private void startMergeFlow(Player player, DuplicateCheckResponseDto check) {
        UserResponseDto primary = check.primaryUser();
        UserResponseDto conflicting = check.conflictingUser();

        if (primary == null || conflicting == null) {
            sendPrefixed(player, "&cAccount merge data unavailable. Please try again later.");
            plugin.getLogger().warning("Merge flow for " + player.getName() + " failed: missing account data");
            return;
        }

        plugin.getLogger().info("Starting merge flow for " + player.getName() + " (Primary ID: " + primary.id() + ", Conflicting ID: " + conflicting.id() + ")");

        chatCaptureManager.startMergeFlow(
            player,
            safeInt(primary.coins()), safeInt(primary.gems()), safeInt(primary.experiencePoints()), primary.email(),
            safeInt(conflicting.coins()), safeInt(conflicting.gems()), safeInt(conflicting.experiencePoints()), conflicting.email(),
            data -> {
                String choice = data.get("choice");
                if (choice == null) {
                    runSync(() -> sendPrefixed(player, "&cInvalid choice. Please try again."));
                    return;
                }

                Integer primaryId = choice.equalsIgnoreCase("A") ? primary.id() : conflicting.id();
                Integer secondaryId = choice.equalsIgnoreCase("A") ? conflicting.id() : primary.id();

                if (primaryId == null || secondaryId == null) {
                    runSync(() -> sendPrefixed(player, "&cMerge failed due to missing account IDs."));
                    return;
                }

                mergeAccounts(player, primaryId, secondaryId);
            },
            () -> runSync(() -> sendPrefixed(player, "&cMerge cancelled"))
        );
    }

    private void mergeAccounts(Player player, Integer primaryId, Integer secondaryId) {
        plugin.getLogger().info("Merging accounts for " + player.getName() + " (keeping: " + primaryId + ", merging: " + secondaryId + ")");
        userAccountApi.mergeAccounts(new MergeAccountsRequestDto(primaryId, secondaryId))
            .thenAccept(mergedObj -> {
                UserResponseDto merged = (UserResponseDto) mergedObj;
                runSync(() -> {
                    PlayerUserData userData = userManager.getCachedUser(player.getUniqueId());
                    updateCachedUser(player, userData, merged);

                    String message = config.messages().mergeComplete()
                        .replace("{coins}", String.valueOf(safeInt(merged.coins())))
                        .replace("{gems}", String.valueOf(safeInt(merged.gems())))
                        .replace("{exp}", String.valueOf(safeInt(merged.experiencePoints())));
                    sendPrefixed(player, message);
                    plugin.getLogger().info("Account merge complete for " + player.getName() + " (final balance: " + safeInt(merged.coins()) + " coins, " + safeInt(merged.gems()) + " gems, " + safeInt(merged.experiencePoints()) + " XP)");
                });
            })
            .exceptionally(ex -> {
                plugin.getLogger().severe("Failed to merge accounts for " + player.getName() + ": " + ex.getMessage());
                if (ex.getCause() != null) {
                    plugin.getLogger().severe("  Cause: " + ex.getCause().getMessage());
                }
                runSync(() -> sendPrefixed(player, "&cFailed to merge accounts"));
                return null;
            });
    }

    private void updateCachedUser(Player player, PlayerUserData existing, UserResponseDto response) {
        PlayerUserData updated = new PlayerUserData(
            response.id() != null ? response.id() : (existing != null ? existing.userId() : null),
            response.username() != null ? response.username() : (existing != null ? existing.username() : player.getName()),
            player.getUniqueId(),
            response.email(),
            safeInt(response.coins()),
            safeInt(response.gems()),
            safeInt(response.experiencePoints()),
            response.email() != null && !response.email().isBlank(),
            false,
            null,
            existing != null ? existing.gatePassThroughMethodDefault() : GatePassThroughMethod.DEFAULT
        );

        userManager.updateCachedUser(player.getUniqueId(), updated);
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

    private int safeInt(Integer value) {
        return value != null ? value : 0;
    }
}