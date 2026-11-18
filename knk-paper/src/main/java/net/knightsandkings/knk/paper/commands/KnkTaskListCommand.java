package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.api.dto.WorldTaskDto;
import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * Command: /knk tasks list [status]
 * Lists world tasks filtered by status (default: Pending).
 */
public class KnkTaskListCommand implements CommandExecutor {
    private final Plugin plugin;
    private final WorldTasksApi worldTasksApi;

    public KnkTaskListCommand(Plugin plugin, WorldTasksApi worldTasksApi) {
        this.plugin = plugin;
        this.worldTasksApi = worldTasksApi;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Default to Pending status if not specified
        String status = args.length > 0 ? args[0] : "Pending";

        sender.sendMessage(ChatColor.GRAY + "Fetching " + status + " tasks...");

        worldTasksApi.listByStatus(status).thenAccept(tasks -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (tasks.isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "No tasks found with status: " + status);
                } else {
                    sender.sendMessage(ChatColor.GREEN + "=== " + status + " Tasks (" + tasks.size() + ") ===");
                    for (WorldTaskDto task : tasks) {
                        sender.sendMessage(formatTask(task));
                    }
                }
            });
        }).exceptionally(ex -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                sender.sendMessage(ChatColor.RED + "Failed to list tasks: " + ex.getMessage());
                plugin.getLogger().warning("Failed to list tasks: " + ex.getMessage());
            });
            return null;
        });

        return true;
    }

    private String formatTask(WorldTaskDto task) {
        ChatColor color = switch (task.status()) {
            case "Pending" -> ChatColor.YELLOW;
            case "InProgress" -> ChatColor.AQUA;
            case "Cancelled", "Expired" -> ChatColor.GRAY;
            case "Completed" -> ChatColor.GREEN;
            case "Failed" -> ChatColor.RED;
            default -> ChatColor.GRAY;
        };

        // Show link code prominently for Pending tasks
        String identifier = task.linkCode() != null && "Pending".equals(task.status())
            ? ChatColor.WHITE + "[" + task.linkCode() + "]" + ChatColor.GRAY + " (ID: #" + task.id() + ")"
            : ChatColor.GRAY + "#" + task.id();

        String claimedInfo = task.claimedByMinecraftUsername() != null 
            ? ChatColor.GRAY + " (claimed by " + task.claimedByMinecraftUsername() + ")" 
            : "";

        return color + identifier
            + ChatColor.GRAY + " - " + task.entityType() + "." + task.fieldName() 
            + claimedInfo;
    }
}
