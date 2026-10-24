package net.knightsandkings.knk.core.domain.enchantment;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class EnchantmentRegistry {
    private static final EnchantmentRegistry INSTANCE = new EnchantmentRegistry();
    private final Map<String, Enchantment> enchantmentsById;

    private EnchantmentRegistry() {
        Map<String, Enchantment> map = new LinkedHashMap<>();
        register(map, new Enchantment("poison", "Poison", 3, 0, EnchantmentType.ATTACK, 0.15d));
        register(map, new Enchantment("wither", "Wither", 3, 0, EnchantmentType.ATTACK, 0.15d));
        register(map, new Enchantment("freeze", "Freeze", 3, 0, EnchantmentType.ATTACK, 0.15d));
        register(map, new Enchantment("blindness", "Blindness", 3, 0, EnchantmentType.ATTACK, null));
        register(map, new Enchantment("confusion", "Confusion", 3, 0, EnchantmentType.ATTACK, 0.15d));
        register(map, new Enchantment("strength", "Strength", 2, 120_000, EnchantmentType.ATTACK, 0.15d));
        // Siege playtest 2026-10-24: gate breaker. Melee hits on a gate door do more, and a right-click on a door
        // block is a charged strike; the cooldown here is level I's (60 s), knk-paper's GateCleaving scales it per
        // level (60/50/40/30 s). No entity effect, so the combat executor has nothing to run for it.
        register(map, new Enchantment("cleaving", "Cleaving", 4, 60_000, EnchantmentType.ATTACK, null));
        register(map, new Enchantment("chaos", "Chaos", 1, 90_000, EnchantmentType.SUPPORT, null));
        register(map, new Enchantment("flash_chaos", "Flash Chaos", 1, 90_000, EnchantmentType.SUPPORT, null));
        register(map, new Enchantment("health_boost", "Health Boost", 1, 120_000, EnchantmentType.SUPPORT, null));
        register(map, new Enchantment("armor_repair", "Armor Repair", 1, 120_000, EnchantmentType.SUPPORT, null));
        register(map, new Enchantment("resistance", "Resistance", 2, 120_000, EnchantmentType.SUPPORT, null));
        register(map, new Enchantment("invisibility", "Invisibility", 1, 90_000, EnchantmentType.SUPPORT, null));
        this.enchantmentsById = Collections.unmodifiableMap(map);
    }

    public static EnchantmentRegistry getInstance() {
        return INSTANCE;
    }

    public Optional<Enchantment> getById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(enchantmentsById.get(id.toLowerCase(Locale.ROOT)));
    }

    public Collection<Enchantment> getAll() {
        return enchantmentsById.values();
    }

    public List<Enchantment> getByType(EnchantmentType type) {
        return enchantmentsById.values().stream()
                .filter(e -> e.type() == type)
                .collect(Collectors.toList());
    }

    public boolean existsAndValidLevel(String id, Integer level) {
        if (level == null || level < 1) {
            return false;
        }
        return getById(id)
                .map(e -> level <= e.maxLevel())
                .orElse(false);
    }

    private void register(Map<String, Enchantment> map, Enchantment enchantment) {
        map.put(enchantment.id(), enchantment);
    }
}
