package net.knightsandkings.knk.paper.roads;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.domain.roads.RoadEdgeUpdate;
import net.knightsandkings.knk.core.domain.roads.RoadNodeUpdate;
import net.knightsandkings.knk.core.domain.roads.RoadTile;
import net.knightsandkings.knk.core.domain.roads.RoadTileGraph;
import net.knightsandkings.knk.core.domain.roads.RoadTileProposalSummary;
import net.knightsandkings.knk.core.domain.roads.RoadTileState;
import net.knightsandkings.knk.core.domain.roads.RoadTileUpsertResult;
import net.knightsandkings.knk.core.ports.api.RoadNetworkCommandApi;
import net.knightsandkings.knk.core.ports.api.RoadNetworkQueryApi;
import net.knightsandkings.knk.core.roads.build.BuildParameters;
import net.knightsandkings.knk.core.roads.build.ProposalSelection;
import net.knightsandkings.knk.core.roads.build.TileBuildResult;
import net.knightsandkings.knk.core.roads.build.TileDiff;
import net.knightsandkings.knk.core.roads.build.TileProposal;
import net.knightsandkings.knk.core.roads.build.TileProposal.Item;
import net.knightsandkings.knk.core.roads.build.TileProposal.Kind;
import net.kyori.adventure.text.Component;

/**
 * Curated tiles (rev. 6 Part B, plan §5.7): the proposals the build job makes for Curated tiles, stored through
 * the API (D5) and cached here, and their review - {@code /knk road proposal [list|accept|reject|clear|rejected|
 * unreject]} and {@code /knk road tile curate|uncurate}.
 *
 * <ul>
 *   <li><b>Accept</b> (D3) downloads the tile's current graph, merges the accepted items into it
 *       ({@link TileDiff#merge}; an item an admin overrode since is skipped with a reason) and uploads the result
 *       through the normal upsert. The items left stay pending under the same numbers.</li>
 *   <li><b>Reject</b> (D4) confirms a removed edge (its ends are locked) or locks a removed node, so they are never
 *       proposed for removal again; any other item goes on the tile's rejected list, which hides the same change
 *       in later proposals ({@code unreject} takes it back).</li>
 *   <li>The step that leaves nothing pending uploads with the proposal's figures, so the tile then counts as
 *       built with the proposal's builder version (and is no longer dirty).</li>
 * </ul>
 * Commands act on the tile the admin stands in, or on {@code @x,z} ({@code @world:x,z} from the console) -
 * the clickable buttons always name it.
 */
public final class RoadProposals {
    public static final int PAGE_SIZE = 10;
    /** Sub-commands, for tab completion. */
    public static final List<String> ACTIONS = List.of("show", "list", "accept", "reject", "clear", "rejected", "unreject", "unconfirm");

    /** {@link #propose}: the stored proposal (empty = the build found nothing to review) and how many items the rejected list hid. */
    public record Created(TileProposal proposal, int hidden) {
        public boolean nothingToReview() {
            return proposal.isEmpty();
        }
    }

    private final RoadNetworkQueryApi queryApi;
    private final RoadNetworkCommandApi commandApi;
    private final RoadNetworkCache cache;
    private final Supplier<BuildParameters> params;
    private final Executor mainThread;
    private final Map<TileKey, TileProposal> proposals = new ConcurrentHashMap<>();
    private final Set<TileKey> busy = ConcurrentHashMap.newKeySet();

    public RoadProposals(RoadNetworkQueryApi queryApi, RoadNetworkCommandApi commandApi, RoadNetworkCache cache,
                         Supplier<BuildParameters> params, Executor mainThread) {
        this.queryApi = Objects.requireNonNull(queryApi, "queryApi");
        this.commandApi = Objects.requireNonNull(commandApi, "commandApi");
        this.cache = cache;
        this.params = Objects.requireNonNull(params, "params");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
    }

    TileDiff diff() {
        return new TileDiff(TileDiff.Settings.of(params.get()));
    }

    // ===== data =====

    /** The cached proposal of a tile (pending items may be empty: then only the rejected list is kept). */
    public Optional<TileProposal> cached(TileKey key) {
        return Optional.ofNullable(proposals.get(key));
    }

    /** Pending items of every cached proposal of a world, with their tile (the overlay draws them). */
    public Map<TileKey, List<Item>> pendingItems(String world) {
        Map<TileKey, List<Item>> out = new LinkedHashMap<>();
        proposals.forEach((key, proposal) -> {
            if (key.world().equals(world) && !proposal.isEmpty()) {
                out.put(key, proposal.items());
            }
        });
        return out;
    }

    /** Downloads a tile's proposal into the cache; empty when the tile has none. */
    public CompletableFuture<Optional<TileProposal>> load(TileKey key) {
        return queryApi.proposal(key.world(), key.tileX(), key.tileZ()).thenApply(found -> {
            found.ifPresentOrElse(p -> proposals.put(key, p), () -> proposals.remove(key));
            return found;
        });
    }

    /** Loads every proposal of a world that has pending items (for the overlay and the list). */
    public CompletableFuture<List<RoadTileProposalSummary>> refreshWorld(String world) {
        return queryApi.proposals(world).thenCompose(list -> {
            proposals.keySet().removeIf(key -> key.world().equals(world)
                && list.stream().noneMatch(s -> s.tileX() == key.tileX() && s.tileZ() == key.tileZ()));
            List<CompletableFuture<Optional<TileProposal>>> loads = list.stream().filter(s -> s.pendingCount() > 0)
                .map(s -> load(new TileKey(world, s.tileX(), s.tileZ()))).toList();
            return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).thenApply(v -> list);
        });
    }

    /**
     * The build job's step for a Curated tile: the differences between {@code build} and the stored graph,
     * without what the tile's rejected list hides, stored through the API when there is anything to review.
     */
    public CompletableFuture<Created> propose(TileKey key, RoadTileGraph stored, TileBuildResult build, String createdBy) {
        return queryApi.proposal(key.world(), key.tileX(), key.tileZ()).thenCompose(previous -> {
            List<Item> rejected = previous.map(TileProposal::rejected).orElse(List.of());
            TileDiff diff = diff();
            TileDiff.Filtered filtered = diff.withoutRejected(diff.compute(stored, build), rejected);
            TileProposal proposal = new TileProposal(stored.tile().version(), build.builderVersion(), createdBy,
                build.cellCount(), build.levelCount(), build.warningTexts(), filtered.items(), rejected);
            if (proposal.isEmpty()) {
                return CompletableFuture.completedFuture(new Created(proposal, filtered.hidden()));
            }
            return commandApi.saveProposal(key.world(), key.tileX(), key.tileZ(), proposal).thenApply(saved -> {
                proposals.put(key, proposal);
                return new Created(proposal, filtered.hidden());
            });
        });
    }

    /**
     * A build of a Curated tile that found nothing to review: the stored graph is uploaded unchanged with the
     * build's figures, so the tile counts as built with this builder version and is no longer dirty.
     */
    public CompletableFuture<RoadTileUpsertResult> uploadUnchanged(TileKey key, RoadTileGraph stored, TileBuildResult build) {
        TileDiff.Merge merge = diff().merge(stored, List.of(), Set.of(), build.builderVersion(), build.cellCount(),
            build.levelCount(), build.warningTexts());
        return commandApi.upsertTileGraph(key.world(), key.tileX(), key.tileZ(), merge.upload());
    }

    // ===== commands =====

    /** {@code /knk road proposal …}. */
    public void command(CommandSender sender, String[] args) {
        List<String> rest = new ArrayList<>(Arrays.asList(args));
        Optional<TileKey> key = takeTile(sender, rest);
        String action = rest.isEmpty() ? "show" : rest.remove(0).toLowerCase(Locale.ROOT);
        if ("list".equals(action)) {
            String world = !rest.isEmpty() ? rest.get(0) : worldOf(sender);
            if (world == null) {
                sender.sendMessage(RoadMessages.bad("The console must name the world: /knk road proposal list <world>"));
                return;
            }
            list(sender, world);
            return;
        }
        Integer page = null;
        if (action.matches("\\d+")) { // "/knk road proposal 2" = page 2
            page = Integer.parseInt(action);
            action = "show";
        }
        if (key.isEmpty()) {
            sender.sendMessage(RoadMessages.bad("Stand in the tile, or name it: @<tileX>,<tileZ> (console: @<world>:<tileX>,<tileZ>)."));
            return;
        }
        switch (action) {
            case "show" -> show(sender, key.get(), page != null ? page : rest.isEmpty() ? 1 : Math.max(1, parseIntOr(rest.get(0), 1)));
            case "accept" -> accept(sender, key.get(), rest);
            case "reject" -> reject(sender, key.get(), rest);
            case "clear" -> clear(sender, key.get());
            case "rejected" -> rejected(sender, key.get());
            case "unreject", "unconfirm" -> unreject(sender, key.get(), rest);
            default -> sender.sendMessage(RoadMessages.usage("/knk road proposal [page] | list | accept <all|item…|kind> | reject <all|item…|kind>"
                + " | clear | rejected | unreject <R…|all>  [@tileX,tileZ]"));
        }
    }

    /** {@code /knk road tile curate|uncurate [@x,z]} (plan §5.7 D1). */
    public void tileCommand(CommandSender sender, String[] args) {
        List<String> rest = new ArrayList<>(Arrays.asList(args));
        Optional<TileKey> key = takeTile(sender, rest);
        String action = rest.isEmpty() ? "" : rest.get(0).toLowerCase(Locale.ROOT);
        RoadTileState state = switch (action) {
            case "curate" -> RoadTileState.CURATED;
            case "uncurate" -> RoadTileState.DETECTED;
            default -> null;
        };
        if (state == null || key.isEmpty()) {
            sender.sendMessage(RoadMessages.usage("/knk road tile curate | uncurate [@tileX,tileZ]"));
            return;
        }
        TileKey tile = key.get();
        commandApi.setTileState(tile.world(), tile.tileX(), tile.tileZ(), state).whenComplete((t, ex) -> mainThread.execute(() -> {
            if (RoadAdminCommand.failed(sender, "set tile " + label(tile) + " to " + state.apiName(), ex)) {
                return;
            }
            sender.sendMessage(state == RoadTileState.CURATED
                ? RoadMessages.good("Tile " + label(tile) + " is Curated: its builds make proposals (/knk road proposal).")
                : RoadMessages.good("Tile " + label(tile) + " is Detected: its next build is uploaded directly, and that upload curates it again."));
            if (cache != null) {
                cache.refreshTiles(tile.world());
            }
        }));
    }

    private void list(CommandSender sender, String world) {
        refreshWorld(world).whenComplete((list, ex) -> mainThread.execute(() -> {
            if (RoadAdminCommand.failed(sender, "list the proposals of " + world, ex)) {
                return;
            }
            List<RoadTileProposalSummary> pending = list.stream().filter(s -> s.pendingCount() > 0).toList();
            sender.sendMessage(RoadMessages.prefixed(Component.text("Proposals in " + world + ": " + pending.size() + " tile(s) to review"
                + (list.size() > pending.size() ? ", " + (list.size() - pending.size()) + " with only a rejected list" : ""), RoadMessages.HIGHLIGHT)));
            for (RoadTileProposalSummary s : pending) {
                TileKey key = new TileKey(world, s.tileX(), s.tileZ());
                sender.sendMessage(Component.text(" " + label(key) + ": " + counts(s.addedCount(), s.removedCount(), s.changedCount(), s.movedCount())
                        + " (v" + s.builderVersion() + (s.createdBy() == null ? "" : ", " + s.createdBy())
                        + (s.tileVersion() != s.baseVersion() ? ", graph changed since" : "") + ") ", RoadMessages.INFO)
                    .append(RoadMessages.command("[show]", "/knk road proposal " + at(key))));
            }
        }));
    }

    private void show(CommandSender sender, TileKey key, int page) {
        load(key).whenComplete((found, ex) -> mainThread.execute(() -> {
            if (RoadAdminCommand.failed(sender, "load the proposal of tile " + label(key), ex)) {
                return;
            }
            if (found.isEmpty() || found.get().isEmpty()) {
                int rejected = found.map(p -> p.rejected().size()).orElse(0);
                sender.sendMessage(RoadMessages.info("Tile " + label(key) + " has no proposal to review"
                    + (rejected > 0 ? " (" + rejected + " rejected change(s): /knk road proposal rejected " + at(key) + ")" : "") + "."));
                return;
            }
            showLines(found.get(), key, page).forEach(sender::sendMessage);
        }));
    }

    /** The review page: header, buttons, numbered items with teleports and per-item buttons. */
    static List<Component> showLines(TileProposal proposal, TileKey key, int page) {
        List<Component> lines = new ArrayList<>();
        List<Item> items = proposal.items();
        lines.add(RoadMessages.prefixed(Component.text("Proposal for tile " + label(key) + ": " + items.size() + " change(s) - "
            + counts(proposal), RoadMessages.HIGHLIGHT)));
        lines.add(Component.text(" builder v" + proposal.builderVersion() + (proposal.createdBy() == null ? "" : ", built by " + proposal.createdBy())
                + " ", RoadMessages.INFO)
            .append(RoadMessages.command("[accept all]", "/knk road proposal accept all " + at(key))).append(Component.text(" "))
            .append(RoadMessages.command("[reject all]", "/knk road proposal reject all " + at(key))).append(Component.text(" "))
            .append(RoadMessages.command("[drop]", "/knk road proposal clear " + at(key)))
            .append(proposal.rejected().isEmpty() ? Component.empty() : Component.text(" ")
                .append(RoadMessages.command("[rejected " + proposal.rejected().size() + "]", "/knk road proposal rejected " + at(key)))));
        int pages = Math.max(1, (int) Math.ceil(items.size() / (double) PAGE_SIZE));
        int p = Math.min(Math.max(1, page), pages);
        for (Item item : items.subList((p - 1) * PAGE_SIZE, Math.min(items.size(), p * PAGE_SIZE))) {
            lines.add(itemLine(item, key));
        }
        if (pages > 1) {
            lines.add(Component.text(" Page " + p + "/" + pages + " ", RoadMessages.INFO)
                .append(p < pages ? RoadMessages.command("[next]", "/knk road proposal " + (p + 1) + " " + at(key)) : Component.empty()));
        }
        return lines;
    }

    static Component itemLine(Item item, TileKey key) {
        int[] focus = item.focus();
        return Component.text(" " + item.describe() + " ", colour(item.kind()))
            .append(RoadMessages.teleport(focus[0], focus[1], focus[2])).append(Component.text(" "))
            .append(RoadMessages.command("[accept]", "/knk road proposal accept " + item.n() + " " + at(key))).append(Component.text(" "))
            .append(RoadMessages.command("[reject]", "/knk road proposal reject " + item.n() + " " + at(key)));
    }

    private static net.kyori.adventure.text.format.NamedTextColor colour(Kind kind) {
        return switch (kind) {
            case EDGE_ADDED -> RoadMessages.GOOD;
            case EDGE_REMOVED, NODE_REMOVED -> RoadMessages.BAD;
            default -> RoadMessages.WARN;
        };
    }

    private void accept(CommandSender sender, TileKey key, List<String> tokens) {
        withProposal(sender, key, proposal -> {
            ProposalSelection.Result selection = ProposalSelection.parse(tokens, proposal.items());
            if (!selection.ok()) {
                sender.sendMessage(RoadMessages.bad(selection.error()));
                return CompletableFuture.completedFuture(null);
            }
            Set<Integer> selected = TileDiff.withDependencies(proposal.items(), selection.numbers());
            return queryApi.tileGraph(key.world(), key.tileX(), key.tileZ(), null).thenCompose(download -> {
                RoadTileGraph current = download.body();
                boolean finishing = proposal.items().stream().allMatch(i -> selected.contains(i.n()));
                RoadTile tile = current.tile();
                TileDiff.Merge merge = finishing
                    ? diff().merge(current, proposal.items(), selected, proposal.builderVersion(), proposal.cellCount(),
                        proposal.levelCount(), proposal.warnings())
                    : diff().merge(current, proposal.items(), selected, tile.builderVersion(), tile.cellCount(),
                        tile.levelCount(), tile.warnings());
                List<Item> remaining = proposal.items().stream()
                    .filter(i -> !merge.applied().contains(i.n()) && !merge.skipped().containsKey(i.n())).toList();
                boolean upload = !merge.applied().isEmpty() || remaining.isEmpty();
                CompletableFuture<RoadTileUpsertResult> uploaded = upload
                    ? commandApi.upsertTileGraph(key.world(), key.tileX(), key.tileZ(), merge.upload())
                    : CompletableFuture.completedFuture(null);
                return uploaded.thenCompose(result -> {
                    TileProposal next = proposal.with(remaining, proposal.rejected());
                    int base = result != null ? result.tile().version() : tile.version();
                    CompletableFuture<?> saved = remaining.isEmpty()
                        ? CompletableFuture.completedFuture(null) // the upsert cleared the pending items
                        : commandApi.saveProposal(key.world(), key.tileX(), key.tileZ(), rebased(next, base));
                    return saved.thenRun(() -> mainThread.execute(() -> {
                        proposals.put(key, rebased(next, base));
                        acceptedMessages(merge, remaining, proposal, key, result != null).forEach(sender::sendMessage);
                        if (result != null) {
                            refreshAfterUpload(key, result);
                        }
                    }));
                });
            });
        });
    }

    static List<Component> acceptedMessages(TileDiff.Merge merge, List<Item> remaining, TileProposal proposal, TileKey key, boolean uploaded) {
        List<Component> lines = new ArrayList<>();
        if (!merge.applied().isEmpty()) {
            lines.add(RoadMessages.good("Tile " + label(key) + ": accepted " + items(merge.applied()) + " - applied to the road graph."));
        }
        merge.skipped().forEach((n, reason) -> lines.add(RoadMessages.warn(" item " + n + " skipped: " + reason
            + " (rebuild the tile for a fresh proposal).")));
        if (remaining.isEmpty()) {
            lines.add(RoadMessages.good("Review of tile " + label(key) + " finished"
                + (uploaded ? ": it now counts as built with v" + proposal.builderVersion() : "") + "."));
        } else {
            lines.add(RoadMessages.info(remaining.size() + " item(s) left. ").append(RoadMessages.command("[show]", "/knk road proposal " + at(key))));
        }
        return lines;
    }

    /**
     * Reject (plan §5.7 D4): every rejected item goes on the tile's rejected list as R1, R2 …, which {@code unreject}
     * undoes. A removed edge is also confirmed and a removed node locked (the road part is kept); the entry records which
     * nodes that locked, so unreject unlocks exactly those again. Other changes are only hidden from later proposals.
     */
    private void reject(CommandSender sender, TileKey key, List<String> tokens) {
        withProposal(sender, key, proposal -> {
            ProposalSelection.Result selection = ProposalSelection.parse(tokens, proposal.items());
            if (!selection.ok()) {
                sender.sendMessage(RoadMessages.bad(selection.error()));
                return CompletableFuture.completedFuture(null);
            }
            List<Item> chosen = proposal.items().stream().filter(i -> selection.numbers().contains(i.n())).toList();
            return queryApi.tileGraph(key.world(), key.tileX(), key.tileZ(), null).thenCompose(download -> {
                Set<Integer> unlocked = new java.util.HashSet<>();
                download.body().nodes().stream().filter(n -> !n.locked()).forEach(n -> unlocked.add(n.id()));
                List<String> problems = Collections.synchronizedList(new ArrayList<>());
                List<CompletableFuture<?>> keeps = new ArrayList<>();
                List<Item> rejected = new ArrayList<>(proposal.rejected());
                List<String> moves = new ArrayList<>();
                for (Item item : chosen) {
                    Item entry = item;
                    switch (item.kind()) {
                        case EDGE_REMOVED -> {
                            entry = item.withLockedNodes(java.util.stream.Stream.of(item.from().nodeId(), item.to().nodeId())
                                .filter(unlocked::contains).toList());
                            keeps.add(commandApi.updateEdge(item.edgeId(), RoadEdgeUpdate.confirmed(true))
                                .handle((r, ex) -> note(problems, ex, "confirm edge #" + item.edgeId())));
                        }
                        case NODE_REMOVED -> {
                            entry = item.withLockedNodes(unlocked.contains(item.node().nodeId()) ? List.of(item.node().nodeId()) : List.of());
                            keeps.add(commandApi.updateNode(item.node().nodeId(), RoadNodeUpdate.locked(true))
                                .handle((r, ex) -> note(problems, ex, "lock node #" + item.node().nodeId())));
                        }
                        default -> {
                        }
                    }
                    rejected.add(entry);
                    moves.add("item " + item.n() + " → R" + rejected.size());
                }
                List<Item> remaining = proposal.items().stream().filter(i -> !selection.numbers().contains(i.n())).toList();
                TileProposal next = proposal.with(remaining, rejected);
                long kept = chosen.stream().filter(Item::isRemoval).count();
                return CompletableFuture.allOf(keeps.toArray(CompletableFuture[]::new))
                    .thenCompose(v -> commandApi.saveProposal(key.world(), key.tileX(), key.tileZ(), next))
                    .thenCompose(saved -> remaining.isEmpty() ? finishReview(key, proposal) : CompletableFuture.completedFuture(null))
                    .thenRun(() -> mainThread.execute(() -> {
                        proposals.put(key, next);
                        sender.sendMessage(RoadMessages.good("Tile " + label(key) + ": rejected " + String.join(", ", moves) + "."
                            + (kept > 0 ? " " + kept + " road part(s) the build lost are kept (edge confirmed / node locked)." : "")));
                        sender.sendMessage(RoadMessages.info(" Take a decision back with /knk road proposal unreject R<n> ")
                            .append(RoadMessages.command("[rejected list]", "/knk road proposal rejected " + at(key))));
                        problems.forEach(p -> sender.sendMessage(RoadMessages.warn(" " + p)));
                        if (remaining.isEmpty()) {
                            sender.sendMessage(RoadMessages.good("Review of tile " + label(key) + " finished: it now counts as built with v"
                                + proposal.builderVersion() + "."));
                        } else {
                            sender.sendMessage(RoadMessages.info(remaining.size() + " item(s) left. ")
                                .append(RoadMessages.command("[show]", "/knk road proposal " + at(key))));
                        }
                        if (cache != null) {
                            cache.refreshTiles(key.world());
                        }
                    }));
            });
        });
    }

    /** The last items were rejected: upload the current graph with the proposal's figures (builder version, not dirty). */
    private CompletableFuture<Void> finishReview(TileKey key, TileProposal proposal) {
        return queryApi.tileGraph(key.world(), key.tileX(), key.tileZ(), null).thenCompose(download -> {
            TileDiff.Merge merge = diff().merge(download.body(), List.of(), Set.of(), proposal.builderVersion(),
                proposal.cellCount(), proposal.levelCount(), proposal.warnings());
            return commandApi.upsertTileGraph(key.world(), key.tileX(), key.tileZ(), merge.upload());
        }).thenAccept(result -> mainThread.execute(() -> refreshAfterUpload(key, result)));
    }

    private static Void note(List<String> problems, Throwable ex, String what) {
        if (ex != null) {
            problems.add("Could not " + what + ": " + RoadMessages.describeError(ex));
        }
        return null;
    }

    private void clear(CommandSender sender, TileKey key) {
        withProposal(sender, key, proposal -> {
            TileProposal next = proposal.with(List.of(), proposal.rejected());
            return commandApi.saveProposal(key.world(), key.tileX(), key.tileZ(), next).thenRun(() -> mainThread.execute(() -> {
                proposals.put(key, next);
                sender.sendMessage(RoadMessages.good("Proposal of tile " + label(key) + " dropped (" + proposal.items().size()
                    + " item(s), nothing changed). Rebuild the tile for a new one."));
            }));
        });
    }

    private void rejected(CommandSender sender, TileKey key) {
        load(key).whenComplete((found, ex) -> mainThread.execute(() -> {
            if (RoadAdminCommand.failed(sender, "load the proposal of tile " + label(key), ex)) {
                return;
            }
            List<Item> rejected = found.map(TileProposal::rejected).orElse(List.of());
            if (rejected.isEmpty()) {
                sender.sendMessage(RoadMessages.info("Tile " + label(key) + " has no rejected items."));
                return;
            }
            sender.sendMessage(RoadMessages.prefixed(Component.text("Rejected items of tile " + label(key)
                + " (kept road parts, and changes hidden from later proposals) - /knk road proposal unreject R<n> takes one back:",
                RoadMessages.HIGHLIGHT)));
            for (Component line : rejectedLines(rejected, key)) {
                sender.sendMessage(line);
            }
        }));
    }

    static List<Component> rejectedLines(List<Item> rejected, TileKey key) {
        List<Component> lines = new ArrayList<>();
        for (int i = 0; i < rejected.size(); i++) {
            Item item = rejected.get(i);
            int[] focus = item.focus();
            lines.add(Component.text(" R" + (i + 1) + ": " + item.what() + (item.isRemoval() ? " - kept" : " - hidden") + " ", RoadMessages.INFO)
                .append(RoadMessages.teleport(focus[0], focus[1], focus[2])).append(Component.text(" "))
                .append(RoadMessages.command("[unreject]", "/knk road proposal unreject R" + (i + 1) + " " + at(key))));
        }
        return lines;
    }

    /**
     * Takes rejected items back (R1, R2 … as {@code rejected} lists them; {@code unconfirm} is the same): a kept edge is
     * unconfirmed and the nodes its rejection locked are unlocked again, a kept node is unlocked; any other entry no longer
     * hides its change. The next build may propose all of them again.
     */
    private void unreject(CommandSender sender, TileKey key, List<String> tokens) {
        load(key).whenComplete((found, ex) -> mainThread.execute(() -> {
            if (RoadAdminCommand.failed(sender, "load the proposal of tile " + label(key), ex)) {
                return;
            }
            List<Item> rejected = found.map(TileProposal::rejected).orElse(List.of());
            if (rejected.isEmpty()) {
                sender.sendMessage(RoadMessages.info("Tile " + label(key) + " has no rejected items."));
                return;
            }
            List<Item> positions = new ArrayList<>();
            for (int i = 0; i < rejected.size(); i++) {
                positions.add(rejected.get(i).numbered(i + 1));
            }
            ProposalSelection.Result selection = ProposalSelection.parse(rejectedTokens(tokens), positions);
            if (!selection.ok()) {
                sender.sendMessage(RoadMessages.bad("Say which rejected items: R1" + (rejected.size() > 1 ? "-R" + rejected.size() : "")
                    + " (see /knk road proposal rejected), or all."));
                return;
            }
            List<String> problems = Collections.synchronizedList(new ArrayList<>());
            List<CompletableFuture<?>> undo = new ArrayList<>();
            List<Item> kept = new ArrayList<>();
            List<String> done = new ArrayList<>();
            for (int i = 0; i < rejected.size(); i++) {
                Item item = rejected.get(i);
                if (!selection.numbers().contains(i + 1)) {
                    kept.add(item);
                    continue;
                }
                if (item.kind() == Kind.EDGE_REMOVED) {
                    undo.add(commandApi.updateEdge(item.edgeId(), RoadEdgeUpdate.confirmed(false))
                        .handle((r, e) -> note(problems, e, "unconfirm edge #" + item.edgeId())));
                }
                for (int nodeId : item.lockedNodeIds()) {
                    undo.add(commandApi.updateNode(nodeId, RoadNodeUpdate.locked(false))
                        .handle((r, e) -> note(problems, e, "unlock node #" + nodeId)));
                }
                done.add("R" + (i + 1) + " (" + item.what() + (item.isRemoval()
                    ? (item.kind() == Kind.EDGE_REMOVED ? ": unconfirmed" : ": ") + (item.lockedNodeIds().isEmpty() ? ""
                        : (item.kind() == Kind.EDGE_REMOVED ? ", " : "") + "unlocked " + item.lockedNodeIds().stream().map(n -> "#" + n).toList())
                    : "") + ")");
            }
            TileProposal next = found.get().with(found.get().items(), kept);
            CompletableFuture.allOf(undo.toArray(CompletableFuture[]::new))
                .thenCompose(v -> commandApi.saveProposal(key.world(), key.tileX(), key.tileZ(), next))
                .whenComplete((s, ex2) -> mainThread.execute(() -> {
                    if (RoadAdminCommand.failed(sender, "update the rejected list of tile " + label(key), ex2)) {
                        return;
                    }
                    proposals.put(key, next);
                    sender.sendMessage(RoadMessages.good("Tile " + label(key) + ": took back " + String.join(", ", done)
                        + ". The next build may propose them again."
                        + (kept.isEmpty() ? "" : " The rejected list is renumbered: R1-R" + kept.size() + ".")));
                    problems.forEach(p -> sender.sendMessage(RoadMessages.warn(" " + p)));
                    if (cache != null) {
                        cache.refreshTiles(key.world());
                    }
                }));
        }));
    }

    /** "R3", "r1-R3", "R1,R2" → "3", "1-3", "1,2" (plain numbers and "all" pass through). */
    static List<String> rejectedTokens(List<String> tokens) {
        return tokens.stream().map(t -> t.replaceAll("(?i)\\br(\\d)", "$1")).toList();
    }

    /** Loads the proposal, refuses when there is nothing to review or another review step of the tile runs. */
    private void withProposal(CommandSender sender, TileKey key, java.util.function.Function<TileProposal, CompletableFuture<?>> step) {
        if (!busy.add(key)) {
            sender.sendMessage(RoadMessages.warn("Tile " + label(key) + " is busy with another review step; try again in a moment."));
            return;
        }
        load(key).thenCompose(found -> {
            if (found.isEmpty() || found.get().isEmpty()) {
                mainThread.execute(() -> sender.sendMessage(RoadMessages.info("Tile " + label(key) + " has no proposal to review.")));
                return CompletableFuture.completedFuture(null);
            }
            return step.apply(found.get());
        }).whenComplete((v, ex) -> {
            busy.remove(key);
            if (ex != null) {
                mainThread.execute(() -> RoadAdminCommand.failed(sender, "review tile " + label(key), ex));
            }
        });
    }

    private void refreshAfterUpload(TileKey key, RoadTileUpsertResult result) {
        if (cache == null) {
            return;
        }
        cache.invalidateTile(key);
        for (int bumped : result.bumpedTileIds()) {
            cache.invalidateTileId(key.world(), bumped);
        }
        cache.refreshTiles(key.world());
    }

    private static TileProposal rebased(TileProposal proposal, int version) {
        return new TileProposal(version, proposal.builderVersion(), proposal.createdBy(), proposal.cellCount(),
            proposal.levelCount(), proposal.warnings(), proposal.items(), proposal.rejected());
    }

    // ===== helpers =====

    /**
     * Takes an {@code @x,z} / {@code @world:x,z} token out of the arguments; without one, the tile the player
     * stands in. Empty for the console without a token, or a malformed token.
     */
    static Optional<TileKey> takeTile(CommandSender sender, List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            String token = args.get(i);
            if (!token.startsWith("@")) {
                continue;
            }
            args.remove(i);
            String spec = token.substring(1);
            String world = worldOf(sender);
            int colon = spec.lastIndexOf(':');
            if (colon >= 0) {
                world = spec.substring(0, colon);
                spec = spec.substring(colon + 1);
            }
            String[] parts = spec.split(",");
            if (world == null || parts.length != 2) {
                return Optional.empty();
            }
            try {
                return Optional.of(new TileKey(world, Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        if (sender instanceof Player player) {
            Location at = player.getLocation();
            return Optional.of(TileKey.of(at.getWorld().getName(), at.getBlockX(), at.getBlockZ()));
        }
        return Optional.empty();
    }

    /** {@code @x,z} for a player's buttons (their own world), {@code @world:x,z} would suit the console too. */
    static String at(TileKey key) {
        return "@" + key.world() + ":" + key.tileX() + "," + key.tileZ();
    }

    static String label(TileKey key) {
        return key.tileX() + "," + key.tileZ();
    }

    static String counts(TileProposal proposal) {
        return counts((int) proposal.count(Kind.EDGE_ADDED), (int) (proposal.count(Kind.EDGE_REMOVED) + proposal.count(Kind.NODE_REMOVED)),
            (int) proposal.count(Kind.EDGE_CHANGED), (int) proposal.count(Kind.NODE_MOVED));
    }

    static String counts(int added, int removed, int changed, int moved) {
        List<String> parts = new ArrayList<>();
        if (added > 0) parts.add(added + " added");
        if (removed > 0) parts.add(removed + " removed");
        if (changed > 0) parts.add(changed + " changed");
        if (moved > 0) parts.add(moved + " moved");
        return parts.isEmpty() ? "no changes" : String.join(", ", parts);
    }

    /** "item 6" or "items 1, 3, 5". */
    static String items(Set<Integer> numbers) {
        List<String> sorted = new TreeSet<>(numbers).stream().map(String::valueOf).toList();
        return (sorted.size() == 1 ? "item " : "items ") + String.join(", ", sorted);
    }

    private static String worldOf(CommandSender sender) {
        return sender instanceof Player player ? player.getWorld().getName() : null;
    }

    private static int parseIntOr(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
