package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.api.impl.SiegeSurvivalMatchApiImpl;
import net.knightsandkings.knk.core.dataaccess.EnchantmentDefinitionsDataAccess;
import net.knightsandkings.knk.core.dataaccess.ItemBlueprintsDataAccess;
import net.knightsandkings.knk.core.dataaccess.KitsDataAccess;
import net.knightsandkings.knk.core.dataaccess.MinecraftMaterialRefsDataAccess;
import net.knightsandkings.knk.paper.item.BlueprintItemAssembler;
import net.knightsandkings.knk.paper.siege.survival.items.MatchItems;
import net.knightsandkings.knk.paper.siege.survival.items.SurvivalItemFactory;
import net.knightsandkings.knk.paper.siege.survival.kits.SurvivalKitGrants;
import net.knightsandkings.knk.paper.siege.survival.kits.SurvivalKitPicker;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * What phase 4 (link S7b: equipment modes, purchases, Mystery Box, match items) needs from the server, bundled so the
 * runtime's constructor stays as S5 left it: the match API client, the item/kit catalogue accesses, the assembler and
 * the match item registry. Built once by the plugin; {@code null} fields disable the feature that needs them (logged).
 */
public record SurvivalEconomyServices(SiegeSurvivalMatchApiImpl matchApi, MatchItems items, SurvivalItemFactory itemFactory,
                                      SurvivalKitGrants kitGrants, SurvivalKitPicker kitPicker, Executor mainThread, int reelMaxStepTicks) {

    public SurvivalEconomyServices {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(itemFactory, "itemFactory");
        Objects.requireNonNull(kitGrants, "kitGrants");
        Objects.requireNonNull(kitPicker, "kitPicker");
        Objects.requireNonNull(mainThread, "mainThread");
        if (reelMaxStepTicks < 1) reelMaxStepTicks = 8;
    }

    /** The production wiring. */
    public static SurvivalEconomyServices create(SiegeSurvivalMatchApiImpl matchApi, MatchItems items, KitsDataAccess kits,
                                                 ItemBlueprintsDataAccess blueprints, MinecraftMaterialRefsDataAccess materials,
                                                 EnchantmentDefinitionsDataAccess enchantments, BlueprintItemAssembler assembler,
                                                 Executor mainThread, int reelMaxStepTicks, Logger logger) {
        SurvivalItemFactory factory = new SurvivalItemFactory(blueprints, materials, enchantments, assembler, items, logger);
        SurvivalKitGrants grants = new SurvivalKitGrants(kits, blueprints, materials, items, mainThread, logger);
        return new SurvivalEconomyServices(matchApi, items, factory, grants, new SurvivalKitPicker(), mainThread, reelMaxStepTicks);
    }
}
