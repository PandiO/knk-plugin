package net.knightsandkings.knk.core.production.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** PandiO/knk-workspace#74: terminal click amounts, the display spread, the display throttle and the container index. */
class TerminalLogicTest {

    @Test
    void take_leftIsAStack_rightIsOne_shiftIsWhatFits_neverMoreThanStockOrRoom() {
        assertEquals(64, TerminalLayout.takeAmount(TerminalLayout.Click.LEFT, 500, 64, 2304));
        assertEquals(40, TerminalLayout.takeAmount(TerminalLayout.Click.LEFT, 40, 64, 2304));
        assertEquals(1, TerminalLayout.takeAmount(TerminalLayout.Click.RIGHT, 40, 64, 2304));
        assertEquals(300, TerminalLayout.takeAmount(TerminalLayout.Click.SHIFT, 500, 64, 300));
        assertEquals(500, TerminalLayout.takeAmount(TerminalLayout.Click.SHIFT, 500, 64, 2304));
        assertEquals(10, TerminalLayout.takeAmount(TerminalLayout.Click.LEFT, 500, 64, 10));
        assertEquals(0, TerminalLayout.takeAmount(TerminalLayout.Click.LEFT, 500, 64, 0));
        assertEquals(0, TerminalLayout.takeAmount(TerminalLayout.Click.LEFT, 0, 64, 64));
        assertEquals(1, TerminalLayout.takeAmount(TerminalLayout.Click.LEFT, 3, 1, 36)); // unstackable
    }

    @Test
    void put_isTheWholeStackOrOne() {
        assertEquals(32, TerminalLayout.putAmount(TerminalLayout.Click.LEFT, 32));
        assertEquals(32, TerminalLayout.putAmount(TerminalLayout.Click.SHIFT, 32));
        assertEquals(1, TerminalLayout.putAmount(TerminalLayout.Click.RIGHT, 32));
        assertEquals(0, TerminalLayout.putAmount(TerminalLayout.Click.LEFT, 0));
    }

    @Test
    void layout_entriesAreTheTopFiveRows_andTheTextsReadWell() {
        assertEquals(0, TerminalLayout.entryIndex(0));
        assertEquals(44, TerminalLayout.entryIndex(44));
        assertEquals(-1, TerminalLayout.entryIndex(45));
        assertEquals(-1, TerminalLayout.entryIndex(-1));
        assertEquals("40 of 1,000 units (4%)", TerminalLayout.fillText(40, 1000L));
        assertEquals("1,234 units", TerminalLayout.fillText(1234, null));
        TerminalPage page = new TerminalPage(1, 2, "North", 0, null, false, 2, 45, 100, List.of());
        assertEquals("Page 2 of 3", TerminalLayout.pageText(page));
        assertTrue(page.hasNext());
        assertTrue(page.hasPrevious());
    }

    @Test
    void display_spreadsTheStacksOverTheContainer_andCapsThemAtItsSize() {
        DisplaySample sample = new DisplaySample(1, 2, 27, 0.5, 14,
                List.of(new DisplaySample.Item(3, "Cobblestone", 8), new DisplaySample.Item(4, "Coal", 6)));

        List<Integer> slots = DisplayLayout.slots(sample, 27);

        assertEquals(27, slots.size());
        assertEquals(8, slots.stream().filter(id -> id != null && id == 3).count());
        assertEquals(6, slots.stream().filter(id -> id != null && id == 4).count());
        assertEquals(3, slots.get(0));
        assertEquals(4, slots.get(25)); // spread out to the end, not packed at the top
        assertNull(slots.get(26));
        DisplaySample full = new DisplaySample(1, 2, 9, 1.0, 9, List.of(new DisplaySample.Item(3, "C", 20)));
        assertEquals(9, DisplayLayout.slots(full, 9).stream().filter(id -> id != null).count());
        assertTrue(DisplayLayout.slots(null, 9).stream().allMatch(id -> id == null));
    }

    @Test
    void throttle_runsOncePerInterval_andKeepsTheLastAskForLater() {
        DisplayThrottle throttle = new DisplayThrottle(30_000);

        assertTrue(throttle.tryRun(1, 0));
        assertFalse(throttle.tryRun(1, 10_000));
        assertTrue(throttle.due(20_000).isEmpty());
        assertEquals(List.of(1), throttle.due(31_000));
        assertTrue(throttle.due(32_000).isEmpty());
        assertTrue(throttle.tryRun(2, 10_000));
    }

    @Test
    void index_findsBlocksAndDisplaysByChunk_andFollowsBindAndUnbind() {
        StorageContainerInfo terminal = info(1, StorageContainerInfo.Mode.TERMINAL, 1, 64, 0);
        StorageContainerInfo display = info(2, StorageContainerInfo.Mode.DISPLAY, 17, 64, -1);
        ContainerIndex index = new ContainerIndex();
        index.replace(List.of(terminal, display));

        assertEquals(terminal, index.at(new BlockKey("world", 1, 64, 0)).orElseThrow());
        assertTrue(index.at(new BlockKey("world", 2, 64, 0)).isEmpty());
        assertEquals(List.of(display), index.displaysInChunk("world", 1, -1));
        assertTrue(index.displaysInChunk("world", 0, 0).isEmpty());
        assertEquals(List.of(display), index.displaysOf(9));
        assertTrue(index.anyNear("world", 1, 0));
        assertTrue(index.anyNear("world", 16, 0)); // display at x 17, z -1: next chunk, within one block
        assertFalse(index.anyNear("world", 40, 40));
        assertFalse(index.anyNear("nether", 1, 0));

        index.remove(2);
        assertTrue(index.displays().isEmpty());
        index.put(info(3, StorageContainerInfo.Mode.DISPLAY, 1, 64, 0)); // replaces the terminal on that block
        assertEquals(1, index.size());
        assertEquals(StorageContainerInfo.Mode.DISPLAY, index.at(new BlockKey("world", 1, 64, 0)).orElseThrow().mode());
    }

    @Test
    void messages_sayWhatWentWrong() {
        assertTrue(TerminalMessages.forStatus(403, "").contains("members"));
        assertTrue(TerminalMessages.forStatus(409, "{\"code\":\"ProductionDisabled\"}").contains("switched off"));
        assertTrue(TerminalMessages.forStatus(0, null).contains("can't be reached"));
        assertEquals("not enough in stock", TerminalMessages.rejectReason("NotEnoughStock"));
        assertEquals("the storage is full", TerminalMessages.rejectReason("CapacityFull"));
        assertEquals("Terminal", StorageContainerInfo.Mode.TERMINAL.apiName());
        assertEquals(StorageContainerInfo.Mode.DISPLAY, StorageContainerInfo.Mode.parse(" display "));
        assertNull(StorageContainerInfo.Mode.parse("backed"));
    }

    private static StorageContainerInfo info(int id, StorageContainerInfo.Mode mode, int x, int y, int z) {
        return new StorageContainerInfo(id, 9, "North", mode, "Chest", false, 27, "world", x, y, z, null);
    }
}
