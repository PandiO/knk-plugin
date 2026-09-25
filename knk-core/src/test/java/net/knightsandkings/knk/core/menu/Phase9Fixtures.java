package net.knightsandkings.knk.core.menu;

import net.knightsandkings.knk.core.domain.menu.KnkActionBinding;
import net.knightsandkings.knk.core.domain.menu.KnkConditionBinding;
import net.knightsandkings.knk.core.domain.menu.KnkVariableBinding;

import java.util.List;
import java.util.Map;

/** Shared builders for the InventoryMenu Phase 9 (E1-E9) tests - keeps each test about behaviour, not setup. */
final class Phase9Fixtures {

    private Phase9Fixtures() {
    }

    static KnkVariableBinding binding(int id, String target, String expression) {
        return new KnkVariableBinding(id, target, 0, expression, "OnDirty", null);
    }

    static KnkVariableBinding binding(int id, String target, int sortOrder, String expression, String policy, Integer ttl) {
        return new KnkVariableBinding(id, target, sortOrder, expression, policy, ttl);
    }

    static KnkConditionBinding condition(int id, String typeId, String paramsJson, String phase) {
        return new KnkConditionBinding(id, typeId, paramsJson, 0, phase);
    }

    static KnkActionBinding action(int id, String typeId, String paramsJson, List<KnkConditionBinding> conditions) {
        return new KnkActionBinding(id, typeId, paramsJson, 0, conditions);
    }

    static RuntimeMenuItem item(int id, Integer slot, List<KnkVariableBinding> bindings) {
        return item(id, slot, bindings, List.of(), List.of(), false);
    }

    static RuntimeMenuItem item(int id, Integer slot, List<KnkVariableBinding> bindings, List<KnkActionBinding> actions,
                                List<KnkConditionBinding> conditions, boolean rowTemplate) {
        return new RuntimeMenuItem(id, id, slot, null, 1, null, null, MenuDisplayMode.NORMAL, null, null,
                bindings, actions, conditions, rowTemplate);
    }

    static RuntimeMenuSection section(int id, String name, int displaySlot, int width, int height,
                                      List<RuntimeMenuItem> items, String contentSourceId, Map<String, String> params) {
        return new RuntimeMenuSection(id, name, MenuSectionKind.CONTENT_GRID, id, displaySlot, width, height,
                MenuPositionMode.STATIC, MenuAlignVertical.TOP, MenuAlignHorizontal.LEFT, MenuOverflowMode.SCROLL,
                MenuListMode.DEFAULT, MenuRenderPriority.MEDIUM, null, false, items, List.of(), contentSourceId, params);
    }

    static RuntimeMenu menu(String key, RuntimeMenuSection... sections) {
        return new RuntimeMenu(key, "Test " + key, 3, MenuGrowth.STATIC, null, List.of(sections), 20);
    }

    /** A public row type, like a feature's view record would be. */
    public record LobbyRow(int lobbyId, String name, List<String> hints, String note) {
        public int getLobbyId() {
            return lobbyId;
        }

        public String getName() {
            return name;
        }

        public List<String> getHints() {
            return hints;
        }

        public String getNote() {
            return note;
        }
    }

    /** A row with a stable identity for the variable cache. */
    public record KeyedRow(int id, String label) implements MenuRowKey {
        public String getLabel() {
            return label;
        }

        @Override
        public Object menuRowKey() {
            return id;
        }
    }

    /** Stand-in for {@code org.bukkit.entity.Player}. */
    public static final class FakePlayer {
        private final String name;
        private final boolean op;

        FakePlayer(String name, boolean op) {
            this.name = name;
            this.op = op;
        }

        public String getName() {
            return name;
        }

        public boolean isOp() {
            return op;
        }
    }
}
