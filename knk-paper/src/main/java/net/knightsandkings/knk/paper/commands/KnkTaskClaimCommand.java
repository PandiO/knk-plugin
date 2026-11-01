package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.api.dto.ClaimTaskDto;
import net.knightsandkings.knk.api.dto.WorldTaskDto;
import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import net.knightsandkings.knk.paper.tasks.WorldTaskApiErrors;
import net.knightsandkings.knk.paper.tasks.WorldTaskHandlerRegistry;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Command: {@code /knk task <task code>} (alias {@code /knk task-claim}; {@code /knk itemscan claim} and
 * {@code /knk kitscan claim} land here too). Claims a world task and starts its handler for the player.
 *
 * <p>PandiO/knk-workspace#180: the API checks the task code and refuses a task that is no longer Pending (409, e.g.
 * someone else claimed it a moment earlier). Claiming by numeric task id needs {@value #CLAIM_BY_ID_PERMISSION}
 * (owner/rescue only). A claimed task whose type has no handler on this server is failed with the reason, instead of
 * staying InProgress.
 */
public class KnkTaskClaimCommand implements CommandExecutor {
    public static final String CLAIM_BY_ID_PERMISSION = "knk.tasks.any";

    private final Plugin plugin;
    private final WorldTasksApi worldTasksApi;
    private final WorldTaskHandlerRegistry handlerRegistry;
    private final String serverId;
    private final Executor mainThread;

    public KnkTaskClaimCommand(Plugin plugin, WorldTasksApi worldTasksApi, WorldTaskHandlerRegistry handlerRegistry, String serverId) {
        this(plugin, worldTasksApi, handlerRegistry, serverId, task -> Bukkit.getScheduler().runTask(plugin, task));
    }

    /** @param mainThread runs the follow-up (messages, handler start) on the server's main thread */
    public KnkTaskClaimCommand(Plugin plugin, WorldTasksApi worldTasksApi, WorldTaskHandlerRegistry handlerRegistry,
                               String serverId, Executor mainThread) {
        this.plugin = plugin;
        this.worldTasksApi = worldTasksApi;
        this.handlerRegistry = handlerRegistry;
        this.serverId = serverId;
        this.mainThread = mainThread;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "Usage: /knk task <task code>");
            sender.sendMessage(ChatColor.GRAY + "Tip: use the task code from the web app (e.g. /knk task ABC123)");
            return true;
        }

        // KNG-174: one open task at a time; refuse before fetching or claiming anything
        var refusal = handlerRegistry.openTaskRefusal(player);
        if (refusal.isPresent()) {
            sender.sendMessage(ChatColor.RED + refusal.get());
            return true;
        }

        String input = args[0].trim();
        boolean byId = input.matches("\\d+");
        if (byId && !player.hasPermission(CLAIM_BY_ID_PERMISSION)) {
            sender.sendMessage(ChatColor.RED + "Claim tasks with their task code from the web app (e.g. /knk task ABC123).");
            sender.sendMessage(ChatColor.GRAY + "Claiming by task id needs " + CLAIM_BY_ID_PERMISSION + ".");
            return true;
        }

        sender.sendMessage(ChatColor.GRAY + "Claiming task" + (byId ? " #" + input : "") + "...");

        CompletableFuture<WorldTaskDto> taskFuture = byId
            ? worldTasksApi.getById(Integer.parseInt(input))
            : worldTasksApi.getByLinkCode(input);

        taskFuture.thenAccept(task -> {
            if (task == null || task.id() == null) {
                mainThread.execute(() -> {
                    sender.sendMessage(ChatColor.RED + (byId ? "Task #" + input + " not found." : "No open task has this task code."));
                    if (!byId) {
                        sender.sendMessage(ChatColor.GRAY + "Check the code in the web app; a code stops working once its task is closed.");
                    }
                });
                return;
            }

            if (!task.isPending()) {
                mainThread.execute(() -> {
                    sender.sendMessage(ChatColor.RED + "Task is not available (status: " + task.status() + ")");
                    if (task.claimedByMinecraftUsername() != null) {
                        sender.sendMessage(ChatColor.GRAY + "Already claimed by: " + task.claimedByMinecraftUsername());
                    }
                });
                return;
            }

            String uuid = player.getUniqueId().toString();
            ClaimTaskDto claim = byId
                ? ClaimTaskDto.byId(serverId, player.getName(), uuid)
                : ClaimTaskDto.byCode(input, serverId, player.getName(), uuid);
            worldTasksApi.claim(task.id(), claim).thenAccept(claimedTask -> mainThread.execute(() -> started(player, claimedTask)))
                .exceptionally(ex -> {
                    mainThread.execute(() -> {
                        sender.sendMessage(ChatColor.RED + claimRefusal(ex));
                        plugin.getLogger().warning("Failed to claim task " + task.id() + ": " + ex.getMessage());
                    });
                    return null;
                });
        }).exceptionally(ex -> {
            mainThread.execute(() -> {
                sender.sendMessage(ChatColor.RED + "Failed to fetch task: " + ex.getMessage());
                plugin.getLogger().warning("Failed to fetch task: " + ex.getMessage());
            });
            return null;
        });

        return true;
    }

    private void started(Player player, WorldTaskDto claimedTask) {
        player.sendMessage(ChatColor.GREEN + "✓ Successfully claimed task!");
        player.sendMessage(ChatColor.GRAY + "Task: " + ChatColor.WHITE
            + taskLabel(claimedTask.entityType(), claimedTask.fieldName()));

        boolean started = handlerRegistry.startTask(
            player,
            claimedTask.taskType(),
            claimedTask.fieldName(),
            claimedTask.id(),
            claimedTask.inputJson()
        );

        if (started) {
            player.sendMessage(ChatColor.YELLOW + "Handler started. Follow the prompts to complete the task.");
            return;
        }

        // #180: no handler here means nobody can ever finish it in this state; fail it with the reason (the web app
        // shows it and offers "Start again") instead of leaving it InProgress.
        String reason = "This game server has no handler for task type '" + claimedTask.taskType() + "'"
            + (claimedTask.fieldName() != null ? " (field " + claimedTask.fieldName() + ")" : "") + ".";
        player.sendMessage(ChatColor.RED + "⚠ " + reason + " The task was marked as failed.");
        worldTasksApi.fail(claimedTask.id(), reason).exceptionally(ex -> {
            plugin.getLogger().warning("Failing task " + claimedTask.id() + " without a handler failed: " + ex.getMessage());
            return null;
        });
    }

    /** The player's message for a refused claim. */
    static String claimRefusal(Throwable error) {
        int status = WorldTaskApiErrors.status(error);
        String apiMessage = WorldTaskApiErrors.message(error).orElse(null);
        if (status == 409) {
            return apiMessage != null ? apiMessage : "Someone else claimed this task just now.";
        }
        if (status == 400) {
            return apiMessage != null ? apiMessage : "The task code does not match this task.";
        }
        return "Failed to claim task: " + (apiMessage != null ? apiMessage : error.getMessage());
    }

    /**
     * KNG-174: the claimed task as shown to the player: {@code GateStructure › LocationId}, or just the field when
     * the task has no entity type (the API can send none, which used to print {@code null.LocationId}).
     */
    static String taskLabel(String entityType, String fieldName) {
        boolean hasType = entityType != null && !entityType.isBlank();
        boolean hasField = fieldName != null && !fieldName.isBlank();
        if (hasType && hasField) {
            return entityType.trim() + " › " + fieldName.trim();
        }
        if (hasField) {
            return fieldName.trim();
        }
        return hasType ? entityType.trim() : "(unknown field)";
    }
}
