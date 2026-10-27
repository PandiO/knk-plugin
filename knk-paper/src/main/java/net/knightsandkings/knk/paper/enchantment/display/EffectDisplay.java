package net.knightsandkings.knk.paper.enchantment.display;

import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffectType;

/**
 * What the custom enchantments tell the victim's screen when they land a timed debuff (PandiO/knk-workspace#170) or
 * a flash_chaos blackout (#169). Only players are shown anything; other entities are ignored. Main thread.
 */
public interface EffectDisplay {

    EffectDisplay NONE = new EffectDisplay() {
        @Override
        public void debuff(LivingEntity victim, String key, String label, PotionEffectType potion, int durationTicks) {
        }

        @Override
        public void hideNameTags(LivingEntity victim, int durationTicks) {
        }
    };

    /**
     * A timed debuff landed: a boss bar {@code label · 4s} counting down from {@code durationTicks}.
     *
     * @param key    one bar per key; the same key again restarts that bar
     * @param potion the potion effect behind it, so the bar follows its real remaining time (milk, a longer copy);
     *               null for an effect that isn't a potion (freeze)
     */
    void debuff(LivingEntity victim, String key, String label, PotionEffectType potion, int durationTicks);

    /** Hides every other player's name tag in the victim's own view for {@code durationTicks}. */
    void hideNameTags(LivingEntity victim, int durationTicks);
}
