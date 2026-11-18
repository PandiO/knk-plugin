package net.knightsandkings.knk.paper.enchantment;

import net.knightsandkings.knk.core.domain.enchantment.AreaKillAttribution;
import net.knightsandkings.knk.paper.enchantment.effects.SupportEnchantmentEffect;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PandiO/knk-workspace#222: a death inside a Chaos / Flash Chaos hit gets a death message naming the ability and its
 * user; every other death keeps the vanilla message.
 */
class AbilityDeathMessageListenerTest {
    private final AreaKillAttribution attribution = AreaKillAttribution.getInstance();
    private final UUID victimId = UUID.randomUUID();
    private final UUID attackerId = UUID.randomUUID();
    private Player victim;
    private PlayerDeathEvent event;
    private final Component vanilla = Component.text("Steve was slain by Alex");

    @BeforeEach
    void setUp() {
        victim = mock(Player.class);
        when(victim.getUniqueId()).thenReturn(victimId);
        when(victim.getName()).thenReturn("Steve");
        event = mock(PlayerDeathEvent.class);
        when(event.getEntity()).thenReturn(victim);
        when(event.deathMessage()).thenReturn(vanilla);
    }

    private static ConfigurationSection section(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return config.getConfigurationSection("custom-enchantments.death-messages");
    }

    private String messageFor(String enchantmentId, ConfigurationSection section) {
        return messageFor(enchantmentId, section, null);
    }

    private String messageFor(String enchantmentId, ConfigurationSection section, Object item) {
        AbilityDeathMessageListener listener = new AbilityDeathMessageListener(() -> section, attribution);
        attribution.during(victimId, new AreaKillAttribution.Hit(enchantmentId, attackerId, "Alex", item),
                () -> listener.onDeath(event));
        ArgumentCaptor<Component> message = ArgumentCaptor.forClass(Component.class);
        verify(event).deathMessage(message.capture());
        return PlainTextComponentSerializer.plainText().serialize(message.getValue());
    }

    private static ItemStack namedItem(String name) {
        ItemStack item = mock(ItemStack.class);
        when(item.isEmpty()).thenReturn(false);
        when(item.displayName()).thenReturn(Component.text("[" + name + "]"));
        when(item.clone()).thenReturn(item);
        return item;
    }

    @Test
    void flashChaosAndChaosKillsNameTheAbilityTheUserAndTheItem() {
        assertEquals("Steve was killed with Flash Chaos by Alex using [Storm Blade]",
                messageFor("flash_chaos", null, namedItem("Storm Blade")));
    }

    @Test
    void withoutAKnownItemTheUsingPartIsLeftOut() {
        assertEquals("Steve was killed with Flash Chaos by Alex", messageFor("flash_chaos", null));
        ItemStack air = mock(ItemStack.class);
        when(air.isEmpty()).thenReturn(true);
        assertEquals(null, AbilityDeathMessageListener.itemName(air));
    }

    @Test
    void theItemKeepsItsOwnComponent_ForTheHover() {
        Component name = Component.text("[Storm Blade]");
        Component message = AbilityDeathMessageListener.message("&c%victim% using %item%!", name);
        assertEquals("%victim% using [Storm Blade]!", PlainTextComponentSerializer.plainText().serialize(message));
    }

    @Test
    void chaosKillWithTheDefaultFromConfig() throws Exception {
        ConfigurationSection section = section("""
                custom-enchantments:
                  death-messages:
                    enabled: true
                    default: "%victim% was killed with %enchantment% by %player% using %item%"
                    overrides: {}
                """);
        assertEquals("Steve was killed with Chaos by Alex using [Axe]", messageFor("chaos", section, namedItem("Axe")));
        org.mockito.Mockito.reset(event);
        when(event.getEntity()).thenReturn(victim);
        when(event.deathMessage()).thenReturn(vanilla);
        assertEquals("Steve was killed with Chaos by Alex", messageFor("chaos", section));
    }

    @Test
    void aConfiguredOverrideAndColourCodes() throws Exception {
        ConfigurationSection section = section("""
                custom-enchantments:
                  death-messages:
                    overrides:
                      chaos: "&c%victim% was blown apart by %player%'s %enchantment%"
                """);
        assertEquals("Steve was blown apart by Alex's Chaos", messageFor("chaos", section));
    }

    @Test
    void aDeathOutsideAnAbilityHitKeepsTheVanillaMessage() {
        new AbilityDeathMessageListener(() -> null, attribution).onDeath(event);
        verify(event, never()).deathMessage(any());
    }

    @Test
    void aRemovedMessageStaysRemoved_AndDisabledKeepsVanilla() throws Exception {
        when(event.deathMessage()).thenReturn(null);
        AbilityDeathMessageListener listener = new AbilityDeathMessageListener(() -> null, attribution);
        attribution.during(victimId, new AreaKillAttribution.Hit("chaos", attackerId, "Alex"), () -> listener.onDeath(event));
        verify(event, never()).deathMessage(any());

        when(event.deathMessage()).thenReturn(vanilla);
        ConfigurationSection off = section("""
                custom-enchantments:
                  death-messages:
                    enabled: false
                """);
        AbilityDeathMessageListener disabled = new AbilityDeathMessageListener(() -> off, attribution);
        attribution.during(victimId, new AreaKillAttribution.Hit("chaos", attackerId, "Alex"), () -> disabled.onDeath(event));
        verify(event, never()).deathMessage(any());
    }

    @Test
    void theActivationsItemGoesWithTheHit() {
        LivingEntity target = mock(LivingEntity.class);
        when(target.getUniqueId()).thenReturn(victimId);
        Player attacker = mock(Player.class);
        when(attacker.getUniqueId()).thenReturn(attackerId);
        when(attacker.getName()).thenReturn("Alex");
        ItemStack blade = namedItem("Storm Blade");
        List<Optional<AreaKillAttribution.Hit>> seen = new ArrayList<>();
        doAnswer(invocation -> seen.add(attribution.current(victimId))).when(target).damage(anyDouble(), eq(attacker));

        new TestAbility(target).tryExecute(blade, attacker, 1);

        assertEquals(List.of(Optional.of(new AreaKillAttribution.Hit("chaos", attackerId, "Alex", blade))), seen);
    }

    @Test
    void anAbilitysDamageIsRecordedAsItsHitWhileItRuns() {
        LivingEntity target = mock(LivingEntity.class);
        when(target.getUniqueId()).thenReturn(victimId);
        Player attacker = mock(Player.class);
        when(attacker.getUniqueId()).thenReturn(attackerId);
        when(attacker.getName()).thenReturn("Alex");
        List<Optional<AreaKillAttribution.Hit>> seen = new ArrayList<>();
        doAnswer(invocation -> seen.add(attribution.current(victimId))).when(target).damage(anyDouble(), eq(attacker));

        new TestAbility().hit(target, 7.0d, attacker);

        verify(target).damage(7.0d, attacker);
        assertEquals(List.of(Optional.of(new AreaKillAttribution.Hit("chaos", attackerId, "Alex"))), seen);
        assertEquals(Optional.empty(), attribution.current(victimId));
    }

    private static final class TestAbility extends SupportEnchantmentEffect {
        private final LivingEntity target;

        TestAbility() {
            this(null);
        }

        /** @param target hit for 7 on every activation (tryExecute) */
        TestAbility(LivingEntity target) {
            super("chaos", mock(Plugin.class));
            this.target = target;
        }

        void hit(LivingEntity target, double damage, Player attacker) {
            damageAsAbility(target, damage, attacker);
        }

        @Override
        protected void applyEffect(ItemStack item, Player player, int level) {
            if (target != null) {
                damageAsAbility(target, 7.0d, player);
            }
        }
    }
}
