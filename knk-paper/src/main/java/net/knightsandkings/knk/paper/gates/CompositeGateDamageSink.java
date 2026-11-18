package net.knightsandkings.knk.paper.gates;

import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateFireAttribution;
import org.bukkit.entity.Entity;

/**
 * Several {@link GateDamageSink}s behind the one slot the gate mechanics have (statistics, KNG-34, and the siege
 * gate damage reward, PandiO/knk-workspace#165). Direct hits go to every sink. Fire is tracked when any sink tracks
 * it; the igniter is the first non-null one a tracking sink names, and fire shares go only to the sinks that track
 * fire, so a sink with fire attribution off still sees no fire damage. A failing sink doesn't stop the others.
 */
public final class CompositeGateDamageSink implements GateDamageSink {

    private static final Logger LOGGER = Logger.getLogger(CompositeGateDamageSink.class.getName());

    private final List<GateDamageSink> sinks;

    private CompositeGateDamageSink(List<GateDamageSink> sinks) {
        this.sinks = sinks;
    }

    /** The sinks that aren't null or {@link #NONE}; one sink is returned as is, none as {@link #NONE}. */
    public static GateDamageSink of(GateDamageSink... sinks) {
        List<GateDamageSink> list = java.util.Arrays.stream(sinks)
                .filter(Objects::nonNull).filter(s -> s != NONE).toList();
        if (list.isEmpty()) return NONE;
        if (list.size() == 1) return list.get(0);
        return new CompositeGateDamageSink(list);
    }

    @Override
    public void directDamage(CachedGateDoor gate, Entity causingEntity, double loss) {
        for (GateDamageSink sink : sinks) {
            try {
                sink.directDamage(gate, causingEntity, loss);
            } catch (RuntimeException e) {
                LOGGER.warning("Gate damage sink failed: " + e);
            }
        }
    }

    @Override
    public boolean tracksFire() {
        return sinks.stream().anyMatch(GateDamageSink::tracksFire);
    }

    @Override
    public GateFireAttribution.Igniter igniterOf(Entity causingEntity) {
        for (GateDamageSink sink : sinks) {
            if (!sink.tracksFire()) continue;
            GateFireAttribution.Igniter igniter = sink.igniterOf(causingEntity);
            if (igniter != null) return igniter;
        }
        return null;
    }

    @Override
    public void fireDamage(CachedGateDoor gate, GateFireAttribution.Igniter igniter, double loss) {
        for (GateDamageSink sink : sinks) {
            if (!sink.tracksFire()) continue;
            try {
                sink.fireDamage(gate, igniter, loss);
            } catch (RuntimeException e) {
                LOGGER.warning("Gate fire damage sink failed: " + e);
            }
        }
    }
}
