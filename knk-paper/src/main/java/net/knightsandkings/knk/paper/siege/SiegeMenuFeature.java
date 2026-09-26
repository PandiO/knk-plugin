package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.menu.ConditionOutcome;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster.SpawnChoice;
import net.knightsandkings.knk.core.siege.SiegeSpawnOptions;
import net.knightsandkings.knk.core.siege.VoteTally.VoteChoice;
import net.knightsandkings.knk.core.siege.menu.SiegeBodyRowView;
import net.knightsandkings.knk.core.siege.menu.SiegeLobbyMenuView;
import net.knightsandkings.knk.core.siege.menu.SiegeMenuIds;
import net.knightsandkings.knk.core.siege.menu.SiegeServerMenuView;
import net.knightsandkings.knk.core.siege.menu.SiegeViewerMenuView;
import net.knightsandkings.knk.core.siege.menu.SiegeVoteOptionView;
import net.knightsandkings.knk.core.siege.menu.SpawnOptionView;
import net.knightsandkings.knk.paper.menu.MenuActionContext;
import net.knightsandkings.knk.paper.menu.MenuFeature;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Siege Phase 8b (DESIGN §10.2, MENU_TEMPLATES.md Part C): what the siege menus need from the menu
 * engine, registered before {@code MenuDefinitionValidationRunner} like every other feature.
 * <ul>
 *   <li>roots {@code siege} ({@link SiegeLobbyMenuView} of {@code ctx.lobbyId}), {@code siegeViewer}
 *       ({@link SiegeViewerMenuView}: that lobby, else the viewer's own), {@code siegeServer};</li>
 *   <li>row sources {@code siege.lobbies}, {@code siege.vote-candidates}, {@code siege.body} (members
 *       before the match, objectives during it), {@code siege.spawn-options};</li>
 *   <li>actions {@code siege.join/leave/vote/vote.random/spawn/open-own} - each calls the same
 *       {@link SiegeService} method as the matching command (Kits §4 "one implementation"), prints its
 *       reply and repaints;</li>
 *   <li>conditions {@code siege.phase}, {@code siege.participating}, {@code siege.join-eligible},
 *       {@code siege.vote-open}, {@code siege.spawn-available}, {@code siege.lobbies-empty}.</li>
 * </ul>
 * The {@link SiegeService} is created later in {@code onEnable} than the menu features are registered,
 * so it is looked up through a supplier each time; while it doesn't exist everything answers empty.
 */
public final class SiegeMenuFeature implements MenuFeature {

    private final Supplier<SiegeService> service;

    public SiegeMenuFeature(Supplier<SiegeService> service) {
        this.service = service;
    }

    @Override
    public void registerMenuHandlers(MenuFeatureRegistries registries) {
        var variables = registries.variables();
        variables.register(SiegeMenuIds.ROOT_SIEGE, SiegeLobbyMenuView.class, (player, ctx) -> withService(s ->
                SiegeMenuSnapshots.lobby(s, lobbyIdOf(ctx)).map(rt -> SiegeMenuSnapshots.lobbyView(s, rt, player)).orElse(null)));
        variables.register(SiegeMenuIds.ROOT_VIEWER, SiegeViewerMenuView.class, (player, ctx) -> withService(s ->
                SiegeMenuSnapshots.viewerView(s, SiegeMenuSnapshots.lobby(s, lobbyIdOf(ctx)).orElse(null), player)));
        variables.register(SiegeMenuIds.ROOT_SERVER, SiegeServerMenuView.class, (player, ctx) -> withService(s ->
                SiegeMenuSnapshots.serverView(s, player)));

        var sources = registries.contentSources();
        sources.registerRows(SiegeMenuIds.SOURCE_LOBBIES, SiegeLobbyMenuView.class, (context, params, query) -> page(
                withList(s -> SiegeMenuSnapshots.ordered(s).stream()
                        .map(rt -> SiegeMenuSnapshots.lobbyView(s, rt, context.player())).toList())));
        sources.registerRows(SiegeMenuIds.SOURCE_VOTE_CANDIDATES, SiegeVoteOptionView.class, (context, params, query) -> page(
                withList(s -> SiegeMenuSnapshots.lobby(s, params.get("lobbyId"))
                        .map(rt -> SiegeMenuSnapshots.voteOptions(s, rt, context.player())).orElse(List.of()))));
        sources.registerRows(SiegeMenuIds.SOURCE_BODY, SiegeBodyRowView.class, (context, params, query) -> page(
                withList(s -> SiegeMenuSnapshots.lobby(s, params.get("lobbyId"))
                        .map(rt -> SiegeMenuSnapshots.body(s, rt, context.player())).orElse(List.of()))));
        sources.registerRows(SiegeMenuIds.SOURCE_SPAWN_OPTIONS, SpawnOptionView.class, (context, params, query) -> page(
                withList(s -> SiegeMenuSnapshots.spawnOptions(s, context.player()))));

        var actions = registries.actions();
        actions.register(SiegeMenuIds.ACTION_JOIN, (context, params) -> act(context, s ->
                SiegeMenuSnapshots.lobby(s, params.get("lobbyId"))
                        .map(rt -> s.join(context.player(), rt.key()))
                        .orElse(null)));
        actions.register(SiegeMenuIds.ACTION_LEAVE, (context, params) -> act(context, s -> s.leave(context.player())));
        actions.register(SiegeMenuIds.ACTION_VOTE, (context, params) -> act(context, s -> {
            VoteChoice choice = choiceOf(params.get("scenarioId"));
            return choice == null ? null : s.vote(context.player(), choice);
        }));
        actions.register(SiegeMenuIds.ACTION_VOTE_RANDOM, (context, params) ->
                act(context, s -> s.vote(context.player(), VoteChoice.random())));
        actions.register(SiegeMenuIds.ACTION_SPAWN, (context, params) -> act(context, s -> s.spawn(context.player(), params.get("option"))));
        actions.register(SiegeMenuIds.ACTION_OPEN_OWN, (context, params) -> {
            SiegeService s = service.get();
            if (s != null && context.player() != null) s.openMenu(context.player());
        });

        var conditions = registries.conditions();
        conditions.register(SiegeMenuIds.CONDITION_PHASE, (context, params) -> test(s -> {
            Optional<SiegeLobbyRuntime> rt = SiegeMenuSnapshots.lobby(s, params.get("lobbyId"));
            Set<String> phases = Arrays.stream(params.getOrDefault("phases", "").split("[,|]"))
                    .map(p -> p.trim().toUpperCase(Locale.ROOT)).filter(p -> !p.isEmpty()).collect(Collectors.toSet());
            return rt.isPresent() && phases.contains(rt.get().phase().name());
        }));
        conditions.register(SiegeMenuIds.CONDITION_PARTICIPATING, (context, params) -> {
            boolean expected = !"false".equalsIgnoreCase(params.getOrDefault("expected", "true").trim());
            SiegeService s = service.get();
            Player player = context.player();
            boolean member = s != null && player != null && (params.get("lobbyId") == null
                    ? s.lobbyOf(player.getUniqueId()).isPresent()
                    : SiegeMenuSnapshots.lobby(s, params.get("lobbyId")).map(rt -> rt.isMember(player.getUniqueId())).orElse(false));
            if (member == expected) return ConditionOutcome.allow();
            return ConditionOutcome.deny(expected ? "You must join the Siege before you can do that." : "You are already in this Siege.");
        });
        conditions.register(SiegeMenuIds.CONDITION_JOIN_ELIGIBLE, (context, params) -> {
            SiegeService s = service.get();
            if (s == null || context.player() == null) return ConditionOutcome.deny("Sieges aren't running right now.");
            Optional<SiegeLobbyRuntime> rt = SiegeMenuSnapshots.lobby(s, params.get("lobbyId"));
            if (rt.isEmpty()) return ConditionOutcome.deny("That siege no longer exists.");
            return s.joinDenial(context.player(), rt.get()).map(ConditionOutcome::deny).orElse(ConditionOutcome.allow());
        });
        conditions.register(SiegeMenuIds.CONDITION_VOTE_OPEN, (context, params) -> {
            boolean open = test(s -> SiegeMenuSnapshots.lobby(s, params.get("lobbyId")).map(rt -> rt.machine().isVotingOpen()).orElse(false)).allowed();
            return open ? ConditionOutcome.allow() : ConditionOutcome.deny("Voting is closed.");
        });
        conditions.register(SiegeMenuIds.CONDITION_SPAWN_AVAILABLE, (context, params) -> spawnAvailable(context, params));
        conditions.register(SiegeMenuIds.CONDITION_LOBBIES_EMPTY, (context, params) ->
                test(s -> s.lobbies().isEmpty()));
    }

    // ===== helpers =====

    private <T> T withService(java.util.function.Function<SiegeService, T> body) {
        SiegeService s = service.get();
        return s == null ? null : body.apply(s);
    }

    private <T> List<T> withList(java.util.function.Function<SiegeService, List<T>> body) {
        SiegeService s = service.get();
        return s == null ? List.of() : body.apply(s);
    }

    private ConditionOutcome test(java.util.function.Predicate<SiegeService> predicate) {
        SiegeService s = service.get();
        return s != null && predicate.test(s) ? ConditionOutcome.allow() : ConditionOutcome.deny();
    }

    /** Runs a service operation for the clicking player, shows its reply and repaints the menu. */
    private void act(MenuActionContext context, java.util.function.Function<SiegeService, SiegeService.Reply> operation) {
        SiegeService s = service.get();
        Player player = context.player();
        if (s == null || player == null) return;
        SiegeService.Reply reply = operation.apply(s);
        if (reply != null && reply.message() != null) player.sendMessage(reply.message());
        if (context.menuService() != null) context.menuService().refreshOpenMenu(player);
    }

    /** {@code siege.spawn-available {option: "objective:2"}}: re-checked at click time. */
    private ConditionOutcome spawnAvailable(MenuActionContext context, Map<String, String> params) {
        SiegeService s = service.get();
        Player player = context.player();
        if (s == null || player == null) return ConditionOutcome.deny();
        SiegeMatch match = s.runningMatchOf(player.getUniqueId()).orElse(null);
        if (match == null) return ConditionOutcome.deny("The siege is over.");
        var team = match.teamOf(player.getUniqueId()).orElse(null);
        SpawnChoice choice = choiceOfOption(params.get("option"));
        if (team == null || choice == null) return ConditionOutcome.deny();
        if (SiegeSpawnOptions.isAvailable(team, match.board(), choice)) return ConditionOutcome.allow();
        return ConditionOutcome.deny(choice.kind() == net.knightsandkings.knk.core.siege.SiegeMatchRoster.SpawnKind.OBJECTIVE
                ? "This objective is being captured, or your team no longer holds it." : "You can't spawn there.");
    }

    static SpawnChoice choiceOfOption(String option) {
        if (option == null) return null;
        String[] parts = option.trim().split(":", 2);
        if (parts.length != 2) return null;
        try {
            int id = Integer.parseInt(parts[1].trim());
            return switch (parts[0].trim().toLowerCase(Locale.ROOT)) {
                case "objective" -> SpawnChoice.objective(id);
                case "spawnpoint" -> SpawnChoice.spawnpoint(id);
                default -> null;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static VoteChoice choiceOf(String scenarioId) {
        if (scenarioId == null || scenarioId.isBlank()) return null;
        if (SiegeMenuIds.VOTE_RANDOM.equalsIgnoreCase(scenarioId.trim())) return VoteChoice.random();
        try {
            return VoteChoice.scenario(Integer.parseInt(scenarioId.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String lobbyIdOf(MenuContextParams ctx) {
        return ctx == null ? null : ctx.get(SiegeMenuIds.CTX_LOBBY_ID);
    }

    private static <R> CompletableFuture<Page<R>> page(List<R> rows) {
        return CompletableFuture.completedFuture(new Page<>(rows, rows.size(), 1, Math.max(1, rows.size())));
    }
}
