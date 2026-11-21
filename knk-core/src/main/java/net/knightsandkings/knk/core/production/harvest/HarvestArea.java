package net.knightsandkings.knk.core.production.harvest;

import net.knightsandkings.knk.core.production.survey.AreaSpec;

/**
 * An area in the harvest catalogue (knk-web-api {@code HarvestCatalogAreaDto}): an area of a structure whose kind has an
 * enabled rule, with its shape ({@link AreaSpec}) and its pool's fill (1 without a pool).
 *
 * @param productionEnabled false when the structure's production is switched off (its reports are refused)
 */
public record HarvestArea(AreaSpec spec, int productionKindId, boolean productionEnabled, double poolFill) {

    public int areaId() {
        return spec.areaId();
    }

    public int structureId() {
        return spec.structureId();
    }

    public HarvestArea withPoolFill(double fill) {
        return new HarvestArea(spec, productionKindId, productionEnabled, fill);
    }
}
