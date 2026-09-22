package net.knightsandkings.knk.paper.chat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import net.knightsandkings.knk.paper.config.KnkConfig;

/**
 * Manages chat capture sessions for secure player input.
 * Handles multi-step flows like account creation with email/password capture.
 */
public class ChatCaptureManager {
    private static final Pattern EMAIL_PATTERN = 
        Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    
    private final JavaPlugin plugin;
    private final KnkConfig config;
    private final Logger logger;
    private final Map<UUID, ChatCaptureSession> activeSessions;
    
    public ChatCaptureManager(JavaPlugin plugin, KnkConfig config, Logger logger) {
        this.plugin = plugin;
        this.config = config;
        this.logger = logger;
        this.activeSessions = new ConcurrentHashMap<>();
    }
    
    /**
     * Start the account merge flow (display accounts → choose A or B).
     *
     * @param player the player starting the flow
     * @param accountACoin coins in account A
     * @param accountAGems gems in account A
     * @param accountAXp XP in account A
     * @param accountAEmail email linked to account A
     * @param accountBCoin coins in account B
     * @param accountBGems gems in account B
     * @param accountBXp XP in account B
     * @param accountBEmail email linked to account B
     * @param onComplete callback with chosen account ('A' or 'B')
     * @param onCancel callback if player cancels
     */
    public void startMergeFlow(
            Player player,
            int accountACoin, int accountAGems, int accountAXp, String accountAEmail,
            int accountBCoin, int accountBGems, int accountBXp, String accountBEmail,
            Consumer<Map<String, String>> onComplete,
            Runnable onCancel) {
        
        ChatCaptureSession session = new ChatCaptureSession(
            player.getUniqueId(),
            CaptureFlow.ACCOUNT_MERGE,
            CaptureStep.ACCOUNT_CHOICE
        );
        
        session.setOnComplete(onComplete);
        session.setOnCancel(onCancel);
        
        activeSessions.put(player.getUniqueId(), session);
        
        String prefix = config.messages().prefix();
        
        // Display account comparison
        player.sendMessage(prefix + "§6=== Account Merge Required ===");
        player.sendMessage("");
        player.sendMessage("§eAccount A:");
        player.sendMessage("  §7Coins: §a" + accountACoin + " §7| Gems: §b" + accountAGems 
            + " §7| XP: §6" + accountAXp);
        player.sendMessage("  §7Email: §f" + (accountAEmail != null ? accountAEmail : "§cNot linked"));
        player.sendMessage("");
        player.sendMessage("§eAccount B:");
        player.sendMessage("  §7Coins: §a" + accountBCoin + " §7| Gems: §b" + accountBGems 
            + " §7| XP: §6" + accountBXp);
        player.sendMessage("  §7Email: §f" + (accountBEmail != null ? accountBEmail : "§cNot linked"));
        player.sendMessage("");
        player.sendMessage(prefix + "§eType §6A §eor §6B §eto choose which account to keep");
        
        // Start timeout task
        startTimeoutTask(player);
    }
    
    /**
     * IMPLEMENTATION_PLAN.md Phase 5: starts a single-line free-text capture
     * for an arbitrary caller purpose (e.g. an InventoryMenu search query or
     * filter value) - the standard, reusable text-input mechanism, chosen
     * over a one-off anvil-GUI capture specifically because this mechanism
     * already exists and is already idiomatic in this codebase (see
     * ACTIVE_SESSIONS.md's Phase 5 entry, open question 3). Generic over
     * {@link #startMergeFlow}'s account-specific flow: no fixed prompt text,
     * no multi-step state - just "ask once, hand back whatever was typed".
     *
     * @param player      the player to prompt
     * @param promptMessage the full message (already prefixed/colored by the caller) shown before capture starts
     * @param onComplete  callback receiving the raw typed text
     * @param onCancel    callback if the player types "cancel" or times out
     */
    public void startTextCapture(Player player, String promptMessage, Consumer<String> onComplete, Runnable onCancel) {
        ChatCaptureSession session = new ChatCaptureSession(
            player.getUniqueId(),
            CaptureFlow.TEXT_INPUT,
            CaptureStep.TEXT_INPUT
        );

        session.setOnComplete(data -> onComplete.accept(data.getOrDefault("text", "")));
        session.setOnCancel(onCancel);

        activeSessions.put(player.getUniqueId(), session);

        player.sendMessage(promptMessage);
        player.sendMessage(config.messages().prefix() + "§7Type 'cancel' to cancel.");

        startTimeoutTask(player);
    }

    /**
     * Handle chat input for an active capture session.
     *
     * @param player the player sending chat
     * @param message the chat message
     * @return true if the message was handled (should be cancelled), false otherwise
     */
    public boolean handleChatInput(Player player, String message) {
        ChatCaptureSession session = activeSessions.get(player.getUniqueId());
        if (session == null) {
            return false;
        }
        
        String prefix = config.messages().prefix();
        
        // Check for cancel
        if (message.equalsIgnoreCase("cancel")) {
            cancelSession(player);
            return true;
        }
        
        switch (session.getFlow()) {
            case ACCOUNT_MERGE:
                handleMergeInput(player, session, message);
                break;
            case TEXT_INPUT:
                handleTextInput(player, session, message);
                break;
        }
        
        return true; // Event is cancelled
    }
    
    /**
     * Handle input for account merge flow.
     */
    private void handleMergeInput(Player player, ChatCaptureSession session, String input) {
        String prefix = config.messages().prefix();
        
        switch (input.toUpperCase()) {
            case "A":
            case "B":
                session.putData("choice", input.toUpperCase());
                completeSession(player, session);
                break;
            default:
                player.sendMessage(prefix + "§cPlease type 'A' or 'B'");
                break;
        }
    }
    
    /**
     * Handle input for the generic single-line text-capture flow.
     */
    private void handleTextInput(Player player, ChatCaptureSession session, String input) {
        session.putData("text", input);
        completeSession(player, session);
    }

    /**
     * Complete a session and invoke the onComplete callback.
     */
    private void completeSession(Player player, ChatCaptureSession session) {
        activeSessions.remove(player.getUniqueId());
        
        Consumer<Map<String, String>> callback = session.getOnComplete();
        if (callback != null) {
            try {
                callback.accept(session.getData());
            } catch (Exception e) {
                logger.severe("Error in chat capture completion callback for " + player.getName() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        session.clearSensitiveData();
    }
    
    /**
     * Cancel a session and invoke the onCancel callback.
     */
    private void cancelSession(Player player) {
        ChatCaptureSession session = activeSessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        
        logger.info(player.getName() + " cancelled chat capture session (flow: " + session.getFlow() + ")");
        
        String prefix = config.messages().prefix();
        player.sendMessage(prefix + "§cCancelled.");
        
        Runnable callback = session.getOnCancel();
        if (callback != null) {
            try {
                callback.run();
            } catch (Exception e) {
                logger.severe("Error in chat capture cancel callback for " + player.getName() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        session.clearSensitiveData();
    }
    
    /**
     * Check if a player is currently capturing chat input.
     */
    public boolean isCapturingChat(UUID playerId) {
        return activeSessions.containsKey(playerId);
    }
    
    /**
     * Validate email format.
     */
    private boolean isValidEmail(String email) {
        return EMAIL_PATTERN.matcher(email).matches();
    }
    
    /**
     * Start a timeout task for a player's capture session.
     */
    private void startTimeoutTask(Player player) {
        int timeoutTicks = config.account().chatCaptureTimeoutSeconds() * 20;
        
        plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            if (activeSessions.containsKey(player.getUniqueId())) {
                String prefix = config.messages().prefix();
                player.sendMessage(prefix + "§cInput timeout. Please start over.");
                cancelSession(player);
            }
        }, timeoutTicks);
    }
    
    /**
     * Clear all active sessions (e.g., on plugin disable).
     */
    public void clearAllSessions() {
        activeSessions.clear();
    }
    
    /**
     * Get the number of active capture sessions.
     */
    public int getActiveSessionCount() {
        return activeSessions.size();
    }
}
