package net.knightsandkings.knk.paper.commands.support;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.domain.users.TitleChangeResult;
import net.knightsandkings.knk.core.domain.users.TitleCrossing;

/**
 * One consolidated promotion/demotion moment for a title-bracket change - a sound, particle
 * flourish, and a message listing every crossed tier plus the total reward granted. Rebuilds
 * v1's TitleChangeEvents (LEVEL_UP sound + MOBSPAWNER_FLAMES particle), but fires exactly ONCE
 * per BalanceAdjustmentResult even when several tiers were crossed at once - v1 looped once per
 * tier on a 2-second timer for a multi-tier jump, which the developer explicitly asked not to
 * repeat (see UserService.AdjustBalancesAsync's consolidation loop on the web-api side, which
 * already folds every crossed tier's CoinBonus/GemBonus/ExpBonus into one total before this ever
 * runs). Demotion gets the message only, no sound/particle - matches v1, which never played
 * promotion effects on a demotion either.
 */
public final class PromotionEffects {
    private PromotionEffects() {}

    public static void show(Player player, TitleChangeResult titleChange) {
        if (titleChange == null) {
            return;
        }
        if (titleChange.isPromotion()) {
            showPromotion(player, titleChange);
        } else {
            showDemotion(player, titleChange);
        }
    }

    private static void showPromotion(Player player, TitleChangeResult titleChange) {
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        Location loc = player.getLocation().add(0, 1, 0);
        // A slightly bigger flourish than v1's single MOBSPAWNER_FLAMES call, per the
        // developer's "more spectacular" request: a ring of flame particles plus a burst of
        // totem sparkles (Paper's closest "special moment" particle).
        player.getWorld().spawnParticle(Particle.FLAME, loc, 40, 0.6, 0.6, 0.6, 0.02);
        player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, loc, 30, 0.5, 0.8, 0.5, 0.1);

        player.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "✦ Promoted! ✦");
        if (titleChange.crossedTitles().size() > 1) {
            StringBuilder names = new StringBuilder();
            for (TitleCrossing crossing : titleChange.crossedTitles()) {
                if (names.length() > 0) names.append(ChatColor.GRAY).append(" -> ").append(ChatColor.YELLOW);
                names.append(crossing.titleName());
            }
            player.sendMessage(ChatColor.YELLOW + "" + names + ChatColor.GRAY + " (" + titleChange.crossedTitles().size() + " tiers at once!)");
        } else {
            player.sendMessage(ChatColor.YELLOW + titleChange.fromTitleName() + ChatColor.GRAY + " -> " + ChatColor.YELLOW + titleChange.toTitleName());
        }

        StringBuilder rewards = new StringBuilder();
        if (titleChange.coinBonusGranted() > 0) rewards.append(ChatColor.GOLD).append("+").append(titleChange.coinBonusGranted()).append(" coins  ");
        if (titleChange.gemBonusGranted() > 0) rewards.append(ChatColor.AQUA).append("+").append(titleChange.gemBonusGranted()).append(" gems  ");
        if (titleChange.expBonusGranted() > 0) rewards.append(ChatColor.LIGHT_PURPLE).append("+").append(titleChange.expBonusGranted()).append(" bonus XP");
        if (rewards.length() > 0) {
            player.sendMessage(rewards.toString().trim());
        }
    }

    private static void showDemotion(Player player, TitleChangeResult titleChange) {
        player.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "Demoted");
        player.sendMessage(ChatColor.RED + titleChange.fromTitleName() + ChatColor.GRAY + " -> " + ChatColor.RED + titleChange.toTitleName());
    }
}
