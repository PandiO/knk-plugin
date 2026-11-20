package net.knightsandkings.knk.paper.menu.content;

/** Root {@code structureOrders} of the orders list: the count line of the viewer's last orders read. */
public record StructureOrdersView(Integer total, boolean forbidden) {

    public String getCountLine() {
        if (forbidden) {
            return "&cOnly members of the town and staff see the orders.";
        }
        if (total == null) {
            return "&7Loading...";
        }
        return "&7" + total + " order" + (total == 1 ? "" : "s");
    }
}
