package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.teleport.TeleportRequestBook.Direction;
import net.knightsandkings.knk.paper.teleport.TeleportRequestService;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code teleport} variable root of {@code teleport.destinations} (teleport DESIGN.md §3.8):
 * the viewer's real warmup for the info tile, whether the Spawn tile works for them, and their
 * pending {@code /tpa} requests. Built on the main thread from state the menu feature already holds
 * (the permissions read with the rows, the in-memory request book) - never I/O.
 */
public final class TeleportMenuView {

    private final Integer warmupSeconds;
    private final boolean spawnAvailable;
    private final List<TeleportRequestService.Pending> requests;

    /**
     * @param warmupSeconds  the viewer's warp warmup, or null while unknown (rows not loaded yet)
     * @param spawnAvailable {@code /spawn} is running and the viewer may use it
     * @param requests       the viewer's pending requests, incoming first
     */
    public TeleportMenuView(Integer warmupSeconds, boolean spawnAvailable, List<TeleportRequestService.Pending> requests) {
        this.warmupSeconds = warmupSeconds;
        this.spawnAvailable = spawnAvailable;
        this.requests = requests != null ? List.copyOf(requests) : List.of();
    }

    /** v1's "Teleportation starts after N seconds", with this viewer's own warmup (5 s, 3 s, or none). */
    public String getWarmupLine() {
        if (warmupSeconds == null) {
            return "&7Teleportation starts after a short wait";
        }
        if (warmupSeconds <= 0) {
            return "&7Teleportation starts right away";
        }
        return "&7Teleportation starts after &f" + warmupSeconds + " &7" + (warmupSeconds == 1 ? "second" : "seconds");
    }

    public String getSpawnDisplayMode() {
        return spawnAvailable ? "NORMAL" : "DISABLED";
    }

    /** One line per pending request and a click hint; "No pending requests" when there are none. */
    public List<String> getRequestLines() {
        if (requests.isEmpty()) {
            return List.of("&7No pending requests");
        }
        List<String> lines = new ArrayList<>();
        boolean anyIncoming = false;
        for (TeleportRequestService.Pending request : requests) {
            if (request.incoming()) {
                anyIncoming = true;
                lines.add("&f" + request.otherName() + (request.direction() == Direction.TO_TARGET
                        ? " &7wants to teleport to you" : " &7asks you to come to them"));
            } else {
                lines.add(request.direction() == Direction.TO_TARGET
                        ? "&7You asked to teleport to &f" + request.otherName()
                        : "&7You asked &f" + request.otherName() + " &7to come to you");
            }
        }
        lines.add("");
        lines.add(anyIncoming ? "&eClick to answer them in chat" : "&eClick to see it in chat");
        return lines;
    }

    public String getRequestsDisplayMode() {
        return requests.isEmpty() ? "DISABLED" : "NORMAL";
    }
}
