package net.knightsandkings.knk.core.survival.kits;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * The kit choice of a lobby in kits mode (DESIGN §8; S23, S36, S38, S57): every player picks one of the scenario's selected
 * kits at the hub; no pick means the scenario's default kit. The choice locks when the match starts (no switching, S36).
 */
public final class KitSelection {

    /** One of the scenario's selected kits. */
    public record KitOption(int kitId, String name, int sortOrder) {
        public KitOption {
            name = name == null || name.isBlank() ? ("kit " + kitId) : name;
        }
    }

    /** A player's effective kit. */
    public record Resolution(int kitId, boolean defaulted) { }

    public enum PickResult { PICKED, UNKNOWN_KIT, LOCKED }

    private final List<KitOption> options;
    private final OptionalInt defaultKitId;
    private final Map<UUID, Integer> picks = new LinkedHashMap<>();
    private boolean locked;

    public KitSelection(List<KitOption> options, OptionalInt defaultKitId) {
        this.options = options == null ? List.of() : List.copyOf(options);
        this.defaultKitId = defaultKitId == null ? OptionalInt.empty() : defaultKitId;
    }

    public List<KitOption> options() {
        return options;
    }

    public OptionalInt defaultKitId() {
        return defaultKitId;
    }

    public Optional<KitOption> option(int kitId) {
        return options.stream().filter(o -> o.kitId() == kitId).findFirst();
    }

    /** Finds an option by exact name (case-insensitive), then by unique prefix. */
    public Optional<KitOption> optionByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String wanted = name.trim();
        Optional<KitOption> exact = options.stream().filter(o -> o.name().equalsIgnoreCase(wanted)).findFirst();
        if (exact.isPresent()) return exact;
        List<KitOption> prefixed = options.stream().filter(o -> o.name().regionMatches(true, 0, wanted, 0, wanted.length())).toList();
        return prefixed.size() == 1 ? Optional.of(prefixed.get(0)) : Optional.empty();
    }

    /** Whether a picker is worth opening: more than one kit to choose from. */
    public boolean worthAsking() {
        return options.size() > 1;
    }

    public boolean locked() {
        return locked;
    }

    public PickResult pick(UUID player, int kitId) {
        Objects.requireNonNull(player, "player");
        if (locked) return PickResult.LOCKED;
        if (option(kitId).isEmpty()) return PickResult.UNKNOWN_KIT;
        picks.put(player, kitId);
        return PickResult.PICKED;
    }

    public Optional<Integer> picked(UUID player) {
        return Optional.ofNullable(picks.get(player));
    }

    /** The kit the player plays with: the pick, else the default kit, else (no default) the first option. */
    public Optional<Resolution> resolve(UUID player) {
        Integer pick = picks.get(player);
        if (pick != null) return Optional.of(new Resolution(pick, false));
        if (defaultKitId.isPresent() && option(defaultKitId.getAsInt()).isPresent()) {
            return Optional.of(new Resolution(defaultKitId.getAsInt(), true));
        }
        return options.isEmpty() ? Optional.empty() : Optional.of(new Resolution(options.get(0).kitId(), true));
    }

    /** The match started: picks are final (S36). */
    public void lock() {
        locked = true;
    }

    public void forget(UUID player) {
        picks.remove(player);
    }
}
