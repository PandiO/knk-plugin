package net.knightsandkings.knk.core.production.inbox;

import java.util.List;

/**
 * {@code POST api/ItemDeliveries} (PandiO/knk-workspace#60 and #684, production DESIGN § 4.6): what the game server could not
 * place for a player goes to their reward inbox. The key makes a retry apply once (the same key with another body is 409
 * {@code IdempotencyKeyReuse}).
 */
public record DeliveryRequest(int userId, Reason reason, String sourceType, String sourceRef, String note,
                              String idempotencyKey, List<Line> lines) {

    public DeliveryRequest {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** Why the items come (the API's {@code ItemDeliveryReason}). */
    public enum Reason {
        KIT("Kit"), LOOTBOX("Lootbox"), QUEST("Quest"), GIFT("Gift"), ADMIN("Admin"), OTHER("Other");

        private final String apiName;

        Reason(String apiName) {
            this.apiName = apiName;
        }

        public String apiName() {
            return apiName;
        }
    }

    /** An amount of a blueprint, or one unique item ({@code itemInstanceId}, amount 1). */
    public record Line(Integer itemBlueprintId, Long itemInstanceId, long amount) {

        public static Line of(int itemBlueprintId, long amount) {
            return new Line(itemBlueprintId, null, amount);
        }

        public static Line unique(long itemInstanceId) {
            return new Line(null, itemInstanceId, 1);
        }
    }

    /** Units in all lines. */
    public long totalAmount() {
        long total = 0;
        for (Line line : lines) {
            total += line.amount();
        }
        return total;
    }
}
