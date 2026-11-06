package net.knightsandkings.knk.paper.npc;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;

/**
 * The attribute access the adapters need, behind a seam: {@code Attribute.MAX_HEALTH} and friends need a running
 * server's registry, so the adapters name attributes with {@link NpcAttribute} and unit tests substitute a fake.
 * {@link #bukkit()} is the live implementation.
 */
public interface MobAttributes {

    /** The attribute's base value, or {@code fallback} when the entity lacks it. */
    double base(LivingEntity entity, NpcAttribute attribute, double fallback);

    /** The attribute's effective value (base and modifiers), or {@code fallback}. */
    double value(LivingEntity entity, NpcAttribute attribute, double fallback);

    /**
     * Replaces the adapter's own transient modifier {@code key} on {@code attribute} with an ADD_NUMBER of
     * {@code amount}; removes it when {@code amount} is 0. False when the entity lacks the attribute.
     */
    boolean setAddModifier(LivingEntity entity, NpcAttribute attribute, NamespacedKey key, double amount);

    /** Removes the adapter's modifier {@code key} from {@code attribute} if present. */
    void removeModifier(LivingEntity entity, NpcAttribute attribute, NamespacedKey key);

    /** Sets the max-health base and heals to it (round scaling of health, DESIGN §7). False without the attribute. */
    boolean setMaxHealth(LivingEntity entity, double maxHealth);

    static MobAttributes bukkit() {
        return new MobAttributes() {
            @Override
            public double base(LivingEntity entity, NpcAttribute attribute, double fallback) {
                AttributeInstance instance = entity.getAttribute(attribute.bukkit());
                return instance == null ? fallback : instance.getBaseValue();
            }

            @Override
            public double value(LivingEntity entity, NpcAttribute attribute, double fallback) {
                AttributeInstance instance = entity.getAttribute(attribute.bukkit());
                return instance == null ? fallback : instance.getValue();
            }

            @Override
            public boolean setAddModifier(LivingEntity entity, NpcAttribute attribute, NamespacedKey key, double amount) {
                AttributeInstance instance = entity.getAttribute(attribute.bukkit());
                if (instance == null) return false;
                AttributeModifier existing = instance.getModifier(key);
                if (existing != null) instance.removeModifier(existing);
                if (amount != 0 && Double.isFinite(amount)) {
                    instance.addTransientModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.ADD_NUMBER));
                }
                return true;
            }

            @Override
            public void removeModifier(LivingEntity entity, NpcAttribute attribute, NamespacedKey key) {
                AttributeInstance instance = entity.getAttribute(attribute.bukkit());
                if (instance == null) return;
                AttributeModifier existing = instance.getModifier(key);
                if (existing != null) instance.removeModifier(existing);
            }

            @Override
            public boolean setMaxHealth(LivingEntity entity, double maxHealth) {
                AttributeInstance instance = entity.getAttribute(NpcAttribute.MAX_HEALTH.bukkit());
                if (instance == null || !(maxHealth > 0) || !Double.isFinite(maxHealth)) return false;
                instance.setBaseValue(maxHealth);
                entity.setHealth(Math.min(maxHealth, instance.getValue()));
                return true;
            }
        };
    }
}
