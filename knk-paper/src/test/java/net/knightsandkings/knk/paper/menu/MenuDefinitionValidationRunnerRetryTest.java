package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.dataaccess.FetchResult;
import net.knightsandkings.knk.core.dataaccess.MenuTemplatesDataAccess;
import net.knightsandkings.knk.core.domain.menu.KnkMenuTemplate;
import net.knightsandkings.knk.core.domain.menu.KnkMenuTemplateSummary;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Playtest 2026-10-24: the startup validation keeps retrying until every listed menu is validated or blocked - a
 * list that fails, and a template that can't be fetched while the API is (still reported) down, both schedule a
 * retry - and a recovery re-runs the pass.
 */
class MenuDefinitionValidationRunnerRetryTest {

    private static final String KEY = "siege.overview";

    private MenuTemplatesDataAccess templates;
    private MenuService menus;
    private final Deque<Runnable> scheduled = new ArrayDeque<>();
    private final List<Long> delays = new ArrayList<>();

    @BeforeEach
    void setUp() {
        templates = mock(MenuTemplatesDataAccess.class);
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        menus = new MenuService(plugin, null, null, null, null, null, null);
    }

    private void run() {
        MenuDefinitionValidationRunner.runAtStartup(templates, menus, Logger.getLogger("test"),
                (ticks, task) -> { delays.add(ticks); scheduled.add(task); },
                Set.of(), Set.of(), Set.of(), Map.of(), Map.of());
    }

    private void runNextRetry() {
        Runnable next = scheduled.poll();
        assertNotNull(next, "a retry should have been scheduled");
        next.run();
    }

    private static KnkMenuTemplate template() {
        return new KnkMenuTemplate(1, KEY, "Siege", null, 3, "Static", null, List.of(), null, null, null);
    }

    private void listFails() {
        when(templates.listAllAsync()).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("API down")));
    }

    private void listSucceeds() {
        when(templates.listAllAsync()).thenReturn(CompletableFuture.completedFuture(
                List.of(new KnkMenuTemplateSummary(1, KEY, "Siege", null, 0))));
    }

    private void fetchFails() {
        when(templates.getByKeyAsync(anyString())).thenReturn(CompletableFuture.completedFuture(
                FetchResult.error(new IllegalStateException("API down"))));
    }

    private void fetchSucceeds() {
        when(templates.getByKeyAsync(anyString())).thenReturn(CompletableFuture.completedFuture(FetchResult.hit(template())));
    }

    @Test
    void aFailingListIsRetriedWithBackoffUntilItSucceeds() {
        listFails();
        fetchSucceeds();
        run();
        assertFalse(menus.isMenuAvailable(KEY));
        assertEquals(List.of(200L), delays, "10 s");

        runNextRetry();                       // still down
        assertEquals(List.of(200L, 400L), delays, "then 20 s");
        runNextRetry();
        assertEquals(List.of(200L, 400L, 800L), delays, "then 40 s");
        runNextRetry();
        runNextRetry();
        assertEquals(List.of(200L, 400L, 800L, 1200L, 1200L), delays, "then every minute");

        listSucceeds();
        runNextRetry();
        assertTrue(menus.isMenuAvailable(KEY));
        assertTrue(scheduled.isEmpty(), "nothing left to retry");
    }

    @Test
    void aListedMenuThatCannotBeFetchedKeepsThePassRetryingInsteadOfEndingIt() {
        // The dev-server case: the list answers once the API is up while the per-key fetch still fails
        // (the connectivity monitor hasn't flipped to UP yet). The first version ended the pass here.
        listSucceeds();
        fetchFails();
        run();
        assertFalse(menus.isMenuAvailable(KEY));
        assertFalse(menus.blockedMenus().containsKey(KEY), "an unreachable template is not a broken one");
        assertEquals(1, scheduled.size());

        fetchSucceeds();
        runNextRetry();
        assertTrue(menus.isMenuAvailable(KEY));
        assertTrue(scheduled.isEmpty());
    }

    @Test
    void aRecoveryRunsThePassAgainAndAlreadyValidatedMenusAreNotFetchedTwice() {
        listSucceeds();
        fetchFails();
        run();
        // The pending retry holds the slot: a recovery while it is pending does nothing extra.
        assertFalse(menus.beginValidationPass(), "the slot is held while a retry is pending");
        menus.retryDue();

        fetchSucceeds();
        runNextRetry();
        assertTrue(menus.isMenuAvailable(KEY));

        // A later recovery pass finds everything validated and fetches nothing.
        clearInvocations(templates);
        when(templates.listAllAsync()).thenReturn(CompletableFuture.completedFuture(
                List.of(new KnkMenuTemplateSummary(1, KEY, "Siege", null, 0))));
        MenuDefinitionValidationRunner.validateAll(templates, menus, Logger.getLogger("test"),
                (ticks, task) -> scheduled.add(task), Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), 0);
        verify(templates, never()).getByKeyAsync(anyString());
        assertTrue(scheduled.isEmpty());
        assertTrue(menus.beginValidationPass(), "the slot is free again");
    }

    @Test
    void aBrokenTemplateIsBlockedAndNotRetried() {
        listSucceeds();
        when(templates.getByKeyAsync(anyString())).thenReturn(CompletableFuture.completedFuture(FetchResult.hit(
                new KnkMenuTemplate(1, KEY, "Siege", null, 3, "Sideways", null, List.of(), null, null, null))));
        run();

        assertTrue(menus.blockedMenus().containsKey(KEY));
        assertFalse(menus.isMenuAvailable(KEY));
        assertTrue(scheduled.isEmpty(), "a blocked menu is final");
    }
}
