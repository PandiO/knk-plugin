package net.knightsandkings.knk.paper.npc;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** An in-memory {@link MobAttributes}: bases per attribute, modifiers keyed by entity:attribute:key, max health per entity. */
public final class FakeMobAttributes implements MobAttributes {
    public final Map<NpcAttribute, Double> base = new HashMap<>();
    public final Map<String, Double> modifiers = new HashMap<>();
    public final Map<UUID, Double> maxHealth = new HashMap<>();

    private static String key(LivingEntity entity, NpcAttribute attribute, NamespacedKey key) {
        return entity.getUniqueId() + ":" + attribute + ":" + key.getNamespace() + ":" + key.getKey();
    }

    @Override
    public double base(LivingEntity entity, NpcAttribute attribute, double fallback) {
        return base.getOrDefault(attribute, fallback);
    }

    @Override
    public double value(LivingEntity entity, NpcAttribute attribute, double fallback) {
        double v = base(entity, attribute, fallback);
        for (Map.Entry<String, Double> e : modifiers.entrySet()) {
            if (e.getKey().startsWith(entity.getUniqueId() + ":" + attribute + ":")) v += e.getValue();
        }
        return v;
    }

    @Override
    public boolean setAddModifier(LivingEntity entity, NpcAttribute attribute, NamespacedKey key, double amount) {
        if (!base.containsKey(attribute)) return false;
        if (amount == 0) modifiers.remove(key(entity, attribute, key));
        else modifiers.put(key(entity, attribute, key), amount);
        return true;
    }

    @Override
    public void removeModifier(LivingEntity entity, NpcAttribute attribute, NamespacedKey key) {
        modifiers.remove(key(entity, attribute, key));
    }

    @Override
    public boolean setMaxHealth(LivingEntity entity, double value) {
        maxHealth.put(entity.getUniqueId(), value);
        return true;
    }
}
