package net.knightsandkings.knk.core.siege.shop;

import net.knightsandkings.knk.core.siege.economy.LedgerResult;
import net.knightsandkings.knk.core.siege.economy.SiegePointsEconomy;

import java.util.Objects;
import java.util.UUID;

/**
 * The shop's {@link ShopPurchaseService.Wallet} on a PvP match's siege points (siege shop P01, P22, P27;
 * PandiO/knk-workspace#868): charges go through {@link SiegePointsEconomy#charge}, so they count as spent in the match
 * result. Survival's {@code MatchEconomy} gets its own small adapter at its merge.
 */
public final class SiegePointsShopWallet implements ShopPurchaseService.Wallet {

    private final SiegePointsEconomy economy;

    public SiegePointsShopWallet(SiegePointsEconomy economy) {
        this.economy = Objects.requireNonNull(economy, "economy");
    }

    @Override
    public long balance(UUID player) {
        return economy.balance(player);
    }

    @Override
    public LedgerResult charge(String chargeId, UUID player, long price, String product) {
        return economy.charge(chargeId, player, price, product);
    }

    @Override
    public void reverse(String reversalId, UUID player, long amount) {
        economy.reverseCharge(reversalId, player, amount);
    }
}
