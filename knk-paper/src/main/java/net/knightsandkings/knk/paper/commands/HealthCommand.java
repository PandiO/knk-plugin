package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.domain.HealthStatus;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.HealthApi;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.function.IntSupplier;

/**
 * Command handler for /knk health.
 * Performs async health check against the API backend, and shows how many private messages wait
 * to be sent to the API's PM log (KNG-18 Phase 3).
 */
public class HealthCommand implements CommandExecutor {
    private final Plugin plugin;
    private final HealthApi healthApi;
    /** Queued PM log entries, or a negative number when the API sink is off; null = not shown. */
    private final IntSupplier pmLogQueueDepth;
    
    public HealthCommand(Plugin plugin, HealthApi healthApi) {
        this(plugin, healthApi, null);
    }

    public HealthCommand(Plugin plugin, HealthApi healthApi, IntSupplier pmLogQueueDepth) {
        this.plugin = plugin;
        this.healthApi = healthApi;
        this.pmLogQueueDepth = pmLogQueueDepth;
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        sender.sendMessage(ChatColor.GRAY + "Checking API health...");
        if (pmLogQueueDepth != null) {
            int depth = pmLogQueueDepth.getAsInt();
            sender.sendMessage(ChatColor.GRAY + (depth < 0 ? "  pm-log: API sink off" : "  pm-log queue: " + depth));
        }
        
        // Execute health check asynchronously
        healthApi.getHealth().thenAccept(health -> {
            // Schedule main-thread task to send message to player
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (health.isHealthy()) {
                    sender.sendMessage(ChatColor.GREEN + "✓ API is healthy");
                    sender.sendMessage(ChatColor.GRAY + "  Status: " + health.status());
                    if (health.version() != null) {
                        sender.sendMessage(ChatColor.GRAY + "  Version: " + health.version());
                    }
                } else {
                    sender.sendMessage(ChatColor.YELLOW + "⚠ API status: " + health.status());
                    if (health.version() != null) {
                        sender.sendMessage(ChatColor.GRAY + "  Version: " + health.version());
                    }
                }
            });
        }).exceptionally(ex -> {
            // Schedule main-thread task to send error message
            Bukkit.getScheduler().runTask(plugin, () -> {
                sender.sendMessage(ChatColor.RED + "✗ API health check failed");
                
                Throwable cause = ex.getCause();
                
                if (cause instanceof ApiException apiEx) {
                    // Non-2xx HTTP response
                    if (apiEx.getStatusCode() > 0) {
                        sender.sendMessage(ChatColor.RED + "  HTTP " + apiEx.getStatusCode());
                        if (apiEx.getRequestUrl() != null) {
                            sender.sendMessage(ChatColor.RED + "  URL: " + apiEx.getRequestUrl());
                        }
                        if (apiEx.getResponseBody() != null && !apiEx.getResponseBody().isEmpty()) {
                            sender.sendMessage(ChatColor.RED + "  Response: " + apiEx.getResponseBody());
                        }
                    } 
                    // Connection/timeout error
                    else if (apiEx.getCause() != null) {
                        String rootExceptionName = apiEx.getCause().getClass().getSimpleName();
                        sender.sendMessage(ChatColor.RED + "  Error: " + rootExceptionName);
                        if (apiEx.getRequestUrl() != null) {
                            sender.sendMessage(ChatColor.RED + "  URL: " + apiEx.getRequestUrl());
                        }
                        sender.sendMessage(ChatColor.RED + "  " + apiEx.getCause().getMessage());
                    }
                    // Other API error
                    else {
                        sender.sendMessage(ChatColor.RED + "  " + apiEx.getMessage());
                        if (apiEx.getRequestUrl() != null) {
                            sender.sendMessage(ChatColor.RED + "  URL: " + apiEx.getRequestUrl());
                        }
                    }
                } else {
                    sender.sendMessage(ChatColor.RED + "  " + (cause != null ? cause.getMessage() : ex.getMessage()));
                }
                
                plugin.getLogger().warning("Health check failed: " + ex.getMessage());
                if (cause != null) {
                    plugin.getLogger().warning("Caused by: " + cause.getClass().getName() + ": " + cause.getMessage());
                }
            });
            return null;
        });
        
        return true;
    }
}
