package net.knightsandkings.knk.core.production.terminal;

import java.util.Locale;

/**
 * A container block bound to a storage (production DESIGN § 6.2, PandiO/knk-workspace#74; knk-web-api
 * {@code StorageContainerDto}). The block is the canonical one: the left half of a double chest.
 */
public record StorageContainerInfo(int id, int storageId, String storageName, Mode mode, String containerType,
                                   boolean isDouble, int slots, String worldName, int x, int y, int z, String label) {

    /** Terminal: a paged view of the storage, take and put are ledger moves. Display: a read-only sample. */
    public enum Mode {
        TERMINAL("Terminal"),
        DISPLAY("Display");

        private final String apiName;

        Mode(String apiName) {
            this.apiName = apiName;
        }

        public String apiName() {
            return apiName;
        }

        /** Terminal or Display in any case; null for anything else. */
        public static Mode parse(String name) {
            if (name == null) {
                return null;
            }
            return switch (name.trim().toLowerCase(Locale.ROOT)) {
                case "terminal" -> TERMINAL;
                case "display" -> DISPLAY;
                default -> null;
            };
        }
    }

    /** The name shown to players: the label, else the storage's name, else "Storage #id". */
    public String displayName() {
        if (label != null && !label.isBlank()) {
            return label;
        }
        return storageName != null && !storageName.isBlank() ? storageName : "Storage #" + storageId;
    }

    public BlockKey block() {
        return new BlockKey(worldName, x, y, z);
    }
}
