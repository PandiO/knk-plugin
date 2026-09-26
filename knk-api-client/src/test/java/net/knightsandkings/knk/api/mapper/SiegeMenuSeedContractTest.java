package net.knightsandkings.knk.api.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.dto.MenuTemplateDto;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.menu.ActionRegistry;
import net.knightsandkings.knk.core.menu.ConditionOutcome;
import net.knightsandkings.knk.core.menu.ConditionRegistry;
import net.knightsandkings.knk.core.menu.MenuContentSourceRegistry;
import net.knightsandkings.knk.core.menu.MenuDefinitionValidator;
import net.knightsandkings.knk.core.menu.MenuTemplateAssembler;
import net.knightsandkings.knk.core.menu.MenuVariableProviderRegistry;
import net.knightsandkings.knk.core.menu.RuntimeMenu;
import net.knightsandkings.knk.core.siege.menu.SiegeBodyRowView;
import net.knightsandkings.knk.core.siege.menu.SiegeLobbyMenuView;
import net.knightsandkings.knk.core.siege.menu.SiegeMenuIds;
import net.knightsandkings.knk.core.siege.menu.SiegeServerMenuView;
import net.knightsandkings.knk.core.siege.menu.SiegeViewerMenuView;
import net.knightsandkings.knk.core.siege.menu.SiegeVoteOptionView;
import net.knightsandkings.knk.core.siege.menu.SpawnOptionView;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Siege Phase 8b: the seed ↔ plugin contract. {@code menu/siege-seeds.json} is written by knk-web-api's
 * {@code MenuTemplateSiegeSeedTests.SiegeSeeds_ExportAsApiJson} (regenerate it whenever a siege seed
 * changes). Each template is loaded through the real api-client DTO + mapper, assembled, and run
 * through the startup validation knk-paper's {@code MenuDefinitionValidationRunner} performs, against
 * registries that hold what {@code SiegeMenuFeature} registers (roots and row types = the knk-core
 * views, so every {@code $…$} getter the seeds use must exist) plus the engine default ids the seeds
 * reference. A seed/view drift fails here instead of blocking the menu at the server's start.
 */
class SiegeMenuSeedContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static List<MenuTemplateDto> seeds() throws Exception {
        try (InputStream in = SiegeMenuSeedContractTest.class.getResourceAsStream("/menu/siege-seeds.json")) {
            assertNotNull(in, "menu/siege-seeds.json test resource is missing");
            return MAPPER.readValue(in, new TypeReference<List<MenuTemplateDto>>() { });
        }
    }

    private record Registries(ActionRegistry<Object> actions, ConditionRegistry<Object> conditions,
                              MenuContentSourceRegistry<Object> sources, MenuVariableProviderRegistry<Object> variables) {}

    /** What SiegeMenuFeature registers (types and ids only) plus the engine defaults the seeds use. */
    private static Registries registries() {
        var actions = new ActionRegistry<Object>();
        for (String id : List.of(SiegeMenuIds.ACTION_JOIN, SiegeMenuIds.ACTION_LEAVE, SiegeMenuIds.ACTION_VOTE,
                SiegeMenuIds.ACTION_VOTE_RANDOM, SiegeMenuIds.ACTION_SPAWN, SiegeMenuIds.ACTION_OPEN_OWN,
                "menu.open", "menu.back", "menu.close", "menu.page.prev", "menu.page.next", "menu.confirm.doubleclick")) {
            actions.register(id, (context, params) -> { });
        }
        var conditions = new ConditionRegistry<Object>();
        for (String id : List.of(SiegeMenuIds.CONDITION_PHASE, SiegeMenuIds.CONDITION_PARTICIPATING,
                SiegeMenuIds.CONDITION_JOIN_ELIGIBLE, SiegeMenuIds.CONDITION_VOTE_OPEN,
                SiegeMenuIds.CONDITION_SPAWN_AVAILABLE, SiegeMenuIds.CONDITION_LOBBIES_EMPTY,
                SiegeMenuIds.CONDITION_LOBBY_OPEN, "value-equals")) {
            conditions.register(id, (context, params) -> ConditionOutcome.allow());
        }
        var sources = new MenuContentSourceRegistry<Object>();
        sources.registerRows(SiegeMenuIds.SOURCE_LOBBIES, SiegeLobbyMenuView.class, (c, p, q) -> empty());
        sources.registerRows(SiegeMenuIds.SOURCE_VOTE_CANDIDATES, SiegeVoteOptionView.class, (c, p, q) -> empty());
        sources.registerRows(SiegeMenuIds.SOURCE_BODY, SiegeBodyRowView.class, (c, p, q) -> empty());
        sources.registerRows(SiegeMenuIds.SOURCE_SPAWN_OPTIONS, SpawnOptionView.class, (c, p, q) -> empty());
        var variables = new MenuVariableProviderRegistry<Object>();
        variables.register(SiegeMenuIds.ROOT_SIEGE, SiegeLobbyMenuView.class, (player, ctx) -> null);
        variables.register(SiegeMenuIds.ROOT_VIEWER, SiegeViewerMenuView.class, (player, ctx) -> null);
        variables.register(SiegeMenuIds.ROOT_SERVER, SiegeServerMenuView.class, (player, ctx) -> null);
        return new Registries(actions, conditions, sources, variables);
    }

    private static <R> CompletableFuture<Page<R>> empty() {
        return CompletableFuture.completedFuture(new Page<>(List.of(), 0, 1, 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {SiegeMenuIds.MENU_OVERVIEW, SiegeMenuIds.MENU_INFORMATION, SiegeMenuIds.MENU_SPAWNPOINT})
    void seedPassesTheStartupValidationAgainstTheSiegeFeature(String key) throws Exception {
        MenuTemplateDto dto = seeds().stream().filter(s -> key.equals(s.key())).findFirst().orElseThrow();
        RuntimeMenu menu = MenuTemplateAssembler.assemble(MenuTemplateMapper.toCore(dto));
        Registries r = registries();

        MenuDefinitionValidator.validate(menu, r.variables().declaredTypes(), r.sources().rowTypes());
        MenuDefinitionValidator.validateActionsAndConditions(menu, r.actions().registeredIds(), r.conditions().registeredIds());
        MenuDefinitionValidator.validateContentSources(menu, r.sources().registeredIds());

        assertEquals(key, menu.key());
    }
}
