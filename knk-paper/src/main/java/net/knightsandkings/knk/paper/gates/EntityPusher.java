package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

/**
 * Applies a directional push to entities based on gate orientation - the fallback when
 * {@link EntityEvacuator} finds no safe spot to move an entity to. KNG-106: the push goes away
 * from the door on the side the entity is on (it used to always follow the face direction, which
 * pushed anyone behind the door through it).
 */
public class EntityPusher {
    private static final double DEFAULT_PUSH_FORCE = 0.6;
    private static final double DIAGONAL_FACTOR = 0.70710678118;

    public static void pushEntity(Entity entity, CachedGateDoor gate) {
        if (entity == null || gate == null) {
            return;
        }

        Vector direction = resolvePushDirection(gate);
        if (direction == null || direction.lengthSquared() == 0) {
            return;
        }

        direction.setY(0);
        if (direction.lengthSquared() == 0) {
            return;
        }

        if (sideOf(entity, gate, direction) < 0) {
            direction.multiply(-1);
        }
        Vector pushForce = direction.normalize().multiply(DEFAULT_PUSH_FORCE);
        entity.setVelocity(pushForce);
    }

    /**
     * -1 when the entity is behind the door's anchor plane along {@code direction}, else +1 (also
     * when its location or the anchor is unknown, keeping the old face-direction push).
     */
    static int sideOf(Entity entity, CachedGateDoor gate, Vector direction) {
        Location location = entity.getLocation();
        Vector anchor = gate.getAnchorPoint();
        if (location == null || anchor == null) {
            return 1;
        }
        double along = (location.getX() - (anchor.getX() + 0.5)) * direction.getX()
            + (location.getZ() - (anchor.getZ() + 0.5)) * direction.getZ();
        return along < 0 ? -1 : 1;
    }

    private static Vector resolvePushDirection(CachedGateDoor gate) {
        Vector faceDirection = vectorFromFaceDirection(gate.getFaceDirection());
        if (faceDirection != null && faceDirection.lengthSquared() > 0) {
            return faceDirection.clone();
        }

        Vector nAxis = gate.getNAxis();
        if (nAxis != null && nAxis.lengthSquared() > 0) {
            return nAxis.clone();
        }

        Vector motionVector = gate.getMotionVector();
        if (motionVector != null && motionVector.lengthSquared() > 0) {
            return motionVector.clone();
        }

        return new Vector(0, 0, 0);
    }

    static Vector vectorFromFaceDirection(String faceDirection) {
        if (faceDirection == null || faceDirection.isBlank()) {
            return null;
        }

        // Backend's GateFaceDirection enum serializes as uppercase-underscore (e.g. "NORTH_EAST") -
        // see GATESTRUCTURE_QOL_IMPLEMENTATION_PLAN.md item 5's FaceDirection type-safety note.
        switch (faceDirection.trim().toUpperCase()) {
            case "NORTH":
                return new Vector(0, 0, -1);
            case "NORTH_EAST":
                return new Vector(DIAGONAL_FACTOR, 0, -DIAGONAL_FACTOR);
            case "EAST":
                return new Vector(1, 0, 0);
            case "SOUTH_EAST":
                return new Vector(DIAGONAL_FACTOR, 0, DIAGONAL_FACTOR);
            case "SOUTH":
                return new Vector(0, 0, 1);
            case "SOUTH_WEST":
                return new Vector(-DIAGONAL_FACTOR, 0, DIAGONAL_FACTOR);
            case "WEST":
                return new Vector(-1, 0, 0);
            case "NORTH_WEST":
                return new Vector(-DIAGONAL_FACTOR, 0, -DIAGONAL_FACTOR);
            default:
                return null;
        }
    }
}
