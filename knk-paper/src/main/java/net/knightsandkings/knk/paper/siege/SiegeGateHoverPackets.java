package net.knightsandkings.knk.paper.siege;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Siege DESIGN §8.5 "Gate hover" (PandiO/knk-workspace#217): a gate door keeps its single {@code TextDisplay}
 * hover; players who are not in the match get the door's <b>pre-game</b> hover in their own entity-metadata
 * packets, everyone else the live (siege) one. {@link SiegeGateViewService} publishes, every pass, which display
 * entities get which text for which viewers ({@link #publish}); this PacketEvents listener rewrites the text
 * entry ({@link EntityDataTypes#ADV_COMPONENT} - the only plain component a text display carries, so no metadata
 * index is hard-coded) of matching packets. Runs on Netty threads: the published map is immutable and swapped
 * whole.
 */
public final class SiegeGateHoverPackets implements PacketListener {

    /** The text one display shows to the given viewers. */
    public record HoverText(Component text, Set<UUID> viewers) {}

    private volatile Map<Integer, HoverText> byEntityId = Map.of();

    /** Main thread: replaces the overrides (entity id → text + viewers). */
    public void publish(Map<Integer, HoverText> overrides) {
        byEntityId = Map.copyOf(overrides);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.ENTITY_METADATA) return;
        Map<Integer, HoverText> overrides = byEntityId;
        if (overrides.isEmpty()) return;
        User user = event.getUser();
        UUID viewer = user == null ? null : user.getUUID();
        if (viewer == null) return;
        WrapperPlayServerEntityMetadata packet = new WrapperPlayServerEntityMetadata(event);
        HoverText override = overrides.get(packet.getEntityId());
        if (override == null || !override.viewers().contains(viewer)) return;
        List<EntityData<?>> data = packet.getEntityMetadata();
        List<EntityData<?>> rewritten = new ArrayList<>(data.size());
        boolean changed = false;
        for (EntityData<?> entry : data) {
            if (entry.getType() == EntityDataTypes.ADV_COMPONENT) {
                rewritten.add(new EntityData<>(entry.getIndex(), EntityDataTypes.ADV_COMPONENT, override.text()));
                changed = true;
            } else {
                rewritten.add(entry);
            }
        }
        if (!changed) return;
        packet.setEntityMetadata(rewritten);
        event.markForReEncode(true);
    }
}
