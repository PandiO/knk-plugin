package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.api.dto.WorldTaskDto;
import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

/**
 * Command: /knk task status <id|linkCode>
 * Shows the current status of a world task.
 */
public class KnkTaskStatusCommand implements CommandExecutor {
    private final Plugin plugin;
    private final WorldTasksApi worldTasksApi;

    public KnkTaskStatusCommand(Plugin plugin, WorldTasksApi worldTasksApi) {
        this.plugin = plugin;
        this.worldTasksApi = worldTasksApi;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "Usage: /knk task status <id|linkCode>");
            return true;
        }

        String idOrLinkCode = args[0];
        sender.sendMessage(ChatColor.GRAY + "Fetching task status...");

        // Determine if it's an ID or link code
        boolean isNumeric = idOrLinkCode.matches("\\d+");

        var taskFuture = isNumeric 
            ? worldTasksApi.getById(Integer.parseInt(idOrLinkCode))
            : worldTasksApi.getByLinkCode(idOrLinkCode);

        taskFuture.thenAccept(task -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (task == null) {
                    sender.sendMessage(ChatColor.RED + "Task not found: " + idOrLinkCode);
                } else {
                    displayTaskStatus(sender, task);
                }
            });
        }).exceptionally(ex -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                sender.sendMessage(ChatColor.RED + "Failed to fetch task: " + ex.getMessage());
                plugin.getLogger().warning("Failed to fetch task: " + ex.getMessage());
            });
            return null;
        });

        return true;
    }

    private void displayTaskStatus(CommandSender sender, WorldTaskDto task) {
        ChatColor statusColor = switch (task.status()) {
            case "Pending" -> ChatColor.YELLOW;
            case "InProgress" -> ChatColor.AQUA;
            case "Cancelled", "Expired" -> ChatColor.GRAY;
            case "Completed" -> ChatColor.GREEN;
            case "Failed" -> ChatColor.RED;
            default -> ChatColor.GRAY;
        };

        sender.sendMessage(ChatColor.GREEN + "=== Task #" + task.id() + " ===");
        sender.sendMessage(ChatColor.GRAY + "Status: " + statusColor + task.status());
        sender.sendMessage(ChatColor.GRAY + "Type: " + ChatColor.WHITE + task.entityType() + "." + task.fieldName());
        
        if (task.linkCode() != null) {
            sender.sendMessage(ChatColor.GRAY + "Link Code: " + ChatColor.WHITE + task.linkCode());
        }
        
        if (task.workflowSessionId() != null) {
            sender.sendMessage(ChatColor.GRAY + "Workflow Session: " + ChatColor.WHITE + "#" + task.workflowSessionId());
        }
        
        if (task.stepNumber() != null) {
            sender.sendMessage(ChatColor.GRAY + "Step: " + ChatColor.WHITE + task.stepNumber());
        }
        
        if (task.claimedByMinecraftUsername() != null) {
            sender.sendMessage(ChatColor.GRAY + "Claimed by: " + ChatColor.WHITE + task.claimedByMinecraftUsername() 
                + ChatColor.GRAY + " (" + task.claimedByServerId() + ")");
            sender.sendMessage(ChatColor.GRAY + "Claimed at: " + ChatColor.WHITE + task.claimedAt());
        }
        
        if (task.completedAt() != null) {
            sender.sendMessage(ChatColor.GRAY + "Completed at: " + ChatColor.GREEN + task.completedAt());
        }
        
        if (task.errorMessage() != null) {
            sender.sendMessage(ChatColor.RED + "Error: " + task.errorMessage());
        }
        
        if (task.inputJson() != null && !task.inputJson().isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "Input: " + ChatColor.WHITE + limitString(task.inputJson(), 60));
        }
        
        if (task.outputJson() != null && !task.outputJson().isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "Output: " + ChatColor.WHITE + limitString(task.outputJson(), 60));
        }
    }

    private String limitString(String str, int maxLength) {
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength) + "...";
    }
}
