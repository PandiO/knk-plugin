package net.knightsandkings.knk.paper.menu.content;

import java.time.Instant;
import java.util.List;

import net.knightsandkings.knk.core.production.menu.StructureMenuText;
import net.knightsandkings.knk.core.production.menu.TransportOrderRow;

/**
 * A row of {@code structures.orders} (M1, M4): cargo, from, to, state and expected arrival; {@code getCanCancel} and
 * {@code getCanRetry} drive the staff actions' conditions, and the hint shows only to staff.
 */
public record TransportOrderMenuRow(TransportOrderRow order, boolean staff, Instant now) {

    public int getId() {
        return order.id();
    }

    public String getStatus() {
        return order.status() == null ? "?" : order.status();
    }

    public String getMaterial() {
        return StructureMenuText.orderMaterial(order);
    }

    public List<String> getLoreLines() {
        return StructureMenuText.orderLines(order, now);
    }

    public String getStaffHintLine() {
        return staff ? StructureMenuText.staffHint(order) : "";
    }

    public boolean getCanCancel() {
        return staff && order.canCancel();
    }

    public boolean getCanRetry() {
        return staff && order.canRetry();
    }
}
