package net.knightsandkings.knk.paper.tasks;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.knightsandkings.knk.api.dto.MinecraftCatalogSnapshotDto;
import net.knightsandkings.knk.api.dto.WorldTaskDto;
import net.knightsandkings.knk.core.catalog.CatalogEntry;
import net.knightsandkings.knk.core.catalog.CatalogSnapshot;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.MinecraftCatalogApi;
import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import net.knightsandkings.knk.paper.catalog.BukkitMaterialCatalogSource;
import net.knightsandkings.knk.paper.catalog.CatalogSource;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Headless WorldTask handler for the Minecraft catalogue scan (KNG-200, knk-workspace
 * docs/specs/minecraft-catalog/IMPLEMENTATION_PLAN.md §5.2a). Reads the server's materials on the main
 * thread, hashes and sends the snapshot to {@code PUT api/MinecraftCatalog/scans/{scanId}/snapshot}
 * off it, then completes the task with {@code {"scanId":N,"entryCount":M}} or fails it with the reason.
 */
public class MinecraftCatalogScanTaskHandler implements IHeadlessWorldTaskHandler {
    private static final Logger LOGGER = Logger.getLogger(MinecraftCatalogScanTaskHandler.class.getName());
    static final String TASK_TYPE = "MinecraftCatalogScan";
    private static final int MAX_ERROR_BODY_LENGTH = 300;

    private final MinecraftCatalogApi catalogApi;
    private final WorldTasksApi worldTasksApi;
    private final CatalogSource source;
    private final Executor mainThread;
    private final Executor offMainThread;

    public MinecraftCatalogScanTaskHandler(MinecraftCatalogApi catalogApi, WorldTasksApi worldTasksApi, Plugin plugin) {
        this(catalogApi, worldTasksApi, new BukkitMaterialCatalogSource(),
            task -> plugin.getServer().getScheduler().runTask(plugin, task),
            task -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task));
    }

    MinecraftCatalogScanTaskHandler(MinecraftCatalogApi catalogApi, WorldTasksApi worldTasksApi, CatalogSource source,
                                    Executor mainThread, Executor offMainThread) {
        this.catalogApi = catalogApi;
        this.worldTasksApi = worldTasksApi;
        this.source = source;
        this.mainThread = mainThread;
        this.offMainThread = offMainThread;
    }

    @Override
    public boolean supports(String taskType) {
        return TASK_TYPE.equals(taskType);
    }

    @Override
    public void execute(WorldTaskDto task, Runnable onFinished) {
        int taskId = task.id();
        Runnable finished = once(onFinished);
        Integer scanId = parseScanId(task.inputJson());
        if (scanId == null) {
            fail(taskId, "InputJson did not contain a valid scanId.", finished);
            return;
        }
        try {
            mainThread.execute(() -> readCatalogue(taskId, scanId, finished));
        } catch (RuntimeException e) {
            fail(taskId, "Could not schedule the catalogue read for scan " + scanId + ": " + describe(e), finished);
        }
    }

    private void readCatalogue(int taskId, int scanId, Runnable finished) {
        List<CatalogEntry> entries;
        String minecraftVersion;
        String serverVersion;
        try {
            entries = source.entries();
            minecraftVersion = source.minecraftVersion();
            serverVersion = source.serverVersion();
        } catch (RuntimeException e) {
            fail(taskId, "Could not read the server's catalogue for scan " + scanId + ": " + describe(e), finished);
            return;
        }
        if (entries.isEmpty()) {
            fail(taskId, "The server reported no catalogue entries for scan " + scanId + ".", finished);
            return;
        }
        try {
            offMainThread.execute(() -> sendSnapshot(taskId, scanId, minecraftVersion, serverVersion, entries, finished));
        } catch (RuntimeException e) {
            fail(taskId, "Could not schedule the snapshot upload for scan " + scanId + ": " + describe(e), finished);
        }
    }

    private void sendSnapshot(int taskId, int scanId, String minecraftVersion, String serverVersion,
                              List<CatalogEntry> entries, Runnable finished) {
        CompletableFuture<Void> upload;
        int entryCount = entries.size();
        try {
            MinecraftCatalogSnapshotDto snapshot =
                MinecraftCatalogSnapshotDto.from(CatalogSnapshot.of(minecraftVersion, serverVersion, entries));
            upload = catalogApi.putSnapshot(scanId, snapshot);
        } catch (RuntimeException e) {
            upload = CompletableFuture.failedFuture(e);
        }
        upload.whenComplete((ignored, error) -> {
            if (error != null) {
                fail(taskId, "Catalogue snapshot for scan " + scanId + " was not accepted: " + describe(error), finished);
                return;
            }
            LOGGER.info("[MinecraftCatalogScan] Scan " + scanId + ": sent " + entryCount + " entries ("
                + minecraftVersion + ")");
            complete(taskId, "{\"scanId\":" + scanId + ",\"entryCount\":" + entryCount + "}", finished);
        });
    }

    /** The positive {@code scanId} of {@code {"scanId":N}}, or null. */
    static Integer parseScanId(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return null;
        }
        try {
            JsonObject obj = JsonParser.parseString(inputJson).getAsJsonObject();
            JsonElement value = obj.get("scanId");
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                return null;
            }
            int scanId = value.getAsInt();
            return scanId > 0 ? scanId : null;
        } catch (RuntimeException e) {
            LOGGER.warning("[MinecraftCatalogScan] Could not parse InputJson: " + e.getMessage());
            return null;
        }
    }

    /** The reason for the task's error message: the HTTP status and a short body for a refused call. */
    static String describe(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof ApiException api && api.getStatusCode() > 0) {
            String body = api.getResponseBody() == null ? "" : api.getResponseBody().replaceAll("\\s+", " ").trim();
            if (body.length() > MAX_ERROR_BODY_LENGTH) {
                body = body.substring(0, MAX_ERROR_BODY_LENGTH) + "...";
            }
            return "HTTP " + api.getStatusCode() + (body.isEmpty() ? "" : ": " + body);
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private void fail(int taskId, String message, Runnable finished) {
        LOGGER.warning("[MinecraftCatalogScan] Task " + taskId + " failed: " + message);
        try {
            worldTasksApi.fail(taskId, message).whenComplete((result, ex) -> {
                if (ex != null) {
                    LOGGER.warning("[MinecraftCatalogScan] Task " + taskId + " fail() call failed: " + ex.getMessage());
                }
                finished.run();
            });
        } catch (RuntimeException e) {
            LOGGER.warning("[MinecraftCatalogScan] Task " + taskId + " fail() call failed: " + e.getMessage());
            finished.run();
        }
    }

    private void complete(int taskId, String outputJson, Runnable finished) {
        try {
            worldTasksApi.complete(taskId, outputJson).whenComplete((result, ex) -> {
                if (ex != null) {
                    LOGGER.warning("[MinecraftCatalogScan] Task " + taskId + " snapshot sent but complete() call failed: "
                        + ex.getMessage());
                }
                finished.run();
            });
        } catch (RuntimeException e) {
            LOGGER.warning("[MinecraftCatalogScan] Task " + taskId + " snapshot sent but complete() call failed: " + e.getMessage());
            finished.run();
        }
    }

    private static Runnable once(Runnable onFinished) {
        AtomicBoolean ran = new AtomicBoolean();
        return () -> {
            if (ran.compareAndSet(false, true)) {
                onFinished.run();
            }
        };
    }
}
