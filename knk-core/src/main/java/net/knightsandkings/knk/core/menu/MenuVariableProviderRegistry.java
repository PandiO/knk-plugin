package net.knightsandkings.knk.core.menu;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * InventoryMenu Phase 9 (E2): the registry of getter-chain <em>root</em>
 * variables a template can reference ({@code $player…$}, and whatever a
 * feature adds - e.g. Siege's {@code $siege…$}/{@code $siegeViewer…$}).
 * Each root has a declared type (what {@link MenuDefinitionValidator} checks
 * chains against at plugin enable) and a provider {@code (player, ctx) → value}
 * called at render/click time.
 * <p>
 * Generic over the player type {@code P} so this stays Bukkit-free - knk-paper
 * instantiates {@code MenuVariableProviderRegistry<Player>} and registers
 * {@code player} as the default provider; the same split
 * {@link ActionRegistry}/{@link ConditionRegistry} already use.
 * <p>
 * <b>Engine roots</b> ({@link #ENGINE_ROOTS}) are owned by the engine and can't be
 * registered: {@code ctx} ({@link MenuContextParams}), {@code menu}
 * ({@link MenuView}), {@code section} ({@link SectionView}), {@code state}
 * ({@link MenuStateView}, content port G1) and {@code row}
 * (a row template's row - its type is declared per content source, see
 * {@link MenuContentSourceRegistry#registerRows}).
 * <p>
 * <b>Registration order.</b> Every root must be registered before menu
 * definitions are validated at enable; {@link #lock()} is called by the
 * validation runner and any later {@link #register} throws, so a late
 * registration fails loudly instead of leaving menus validated without it.
 * <p>
 * <b>Threading.</b> Providers are invoked on the server main thread (see
 * IMPLEMENTATION_PLAN.md Phase 9 §9.0), lazily and at most once per render or
 * click pass - see {@link #scope}.
 */
public final class MenuVariableProviderRegistry<P> {

    private static final Logger LOGGER = Logger.getLogger(MenuVariableProviderRegistry.class.getName());

    public static final String ROOT_CTX = "ctx";
    public static final String ROOT_MENU = "menu";
    public static final String ROOT_SECTION = "section";
    public static final String ROOT_ROW = "row";
    /** Content port CP6 (G1): per-session menu state, see {@link MenuStateView}. */
    public static final String ROOT_STATE = "state";
    public static final Set<String> ENGINE_ROOTS = Set.of(ROOT_CTX, ROOT_MENU, ROOT_SECTION, ROOT_ROW, ROOT_STATE);

    /** Declared types of the engine roots whose type doesn't depend on the section ({@code row} does). */
    private static final Map<String, Class<?>> ENGINE_ROOT_TYPES = Map.of(
            ROOT_CTX, MenuContextParams.class,
            ROOT_MENU, MenuView.class,
            ROOT_SECTION, SectionView.class,
            ROOT_STATE, MenuStateView.class
    );

    @FunctionalInterface
    public interface VariableProvider<P> {
        /** May return null (a chain on it then resolves to null - see {@link VariableResolver}'s E8 rules). */
        Object provide(P player, MenuContextParams context);
    }

    private record Registration<P>(Class<?> declaredType, VariableProvider<P> provider) {
    }

    private final Map<String, Registration<P>> roots = new ConcurrentHashMap<>();
    private volatile boolean locked;

    /**
     * @throws IllegalArgumentException if {@code root} is an engine root, blank,
     *                                  or not a single identifier ({@code \w+})
     * @throws IllegalStateException    if called after {@link #lock()}
     */
    public void register(String root, Class<?> declaredType, VariableProvider<P> provider) {
        Objects.requireNonNull(declaredType, "declaredType must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        if (root == null || !root.matches("\\w+")) {
            throw new IllegalArgumentException("Menu variable root '" + root + "' must be a single identifier");
        }
        if (ENGINE_ROOTS.contains(root)) {
            throw new IllegalArgumentException("Menu variable root '" + root + "' is reserved by the menu engine");
        }
        if (locked) {
            throw new IllegalStateException("Menu variable root '" + root + "' registered after menu validation ran"
                    + " - register it in a MenuFeature before MenuDefinitionValidationRunner (IMPLEMENTATION_PLAN.md Phase 9, E2)");
        }
        roots.put(root, new Registration<>(declaredType, provider));
    }

    public boolean isRegistered(String root) {
        return roots.containsKey(root);
    }

    /** Called once by the validation runner; later registrations throw. */
    public void lock() {
        locked = true;
    }

    public boolean isLocked() {
        return locked;
    }

    /**
     * Declared types of every registered root plus the section-independent
     * engine roots ({@code ctx}, {@code menu}, {@code section}) - what
     * {@link MenuDefinitionValidator#validate} checks chains against.
     */
    public Map<String, Class<?>> declaredTypes() {
        Map<String, Class<?>> types = new LinkedHashMap<>();
        roots.forEach((root, registration) -> types.put(root, registration.declaredType()));
        types.putAll(ENGINE_ROOT_TYPES);
        return Map.copyOf(types);
    }

    /**
     * A lazily-resolving variable map for one render or click pass: a registered
     * root's provider runs only if some binding in that pass actually references
     * it, and at most once (memoised, including a null result). {@code
     * engineValues} supplies engine roots ({@code ctx}, {@code menu}, …) and
     * wins over providers.
     */
    public MenuVariableScope scope(P player, MenuContextParams context, Map<String, Object> engineValues) {
        MenuContextParams ctx = context != null ? context : MenuContextParams.EMPTY;
        Map<String, Object> engine = new LinkedHashMap<>();
        engine.put(ROOT_CTX, ctx);
        if (engineValues != null) {
            engineValues.forEach((key, value) -> {
                if (value != null) {
                    engine.put(key, value);
                }
            });
        }
        Map<String, Registration<P>> snapshot = Map.copyOf(roots);
        return MenuVariableScope.root(engine, snapshot.keySet(), root -> {
            try {
                return snapshot.get(root).provider().provide(player, ctx);
            } catch (RuntimeException e) {
                // A broken provider must not take the whole render/click down:
                // its root resolves as null (lines using it are dropped/blank).
                LOGGER.log(Level.WARNING, "Menu variable provider for root '" + root + "' failed", e);
                return null;
            }
        });
    }
}
