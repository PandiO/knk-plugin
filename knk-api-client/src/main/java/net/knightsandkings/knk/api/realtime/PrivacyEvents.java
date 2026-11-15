package net.knightsandkings.knk.api.realtime;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import net.knightsandkings.knk.core.privacy.ErasedAccount;

/**
 * Realtime S5 (PandiO/knk-workspace#184, DESIGN §7.4): the GDPR erasure push of the game hub, as sent by
 * {@code knk-web-api/Realtime/Contracts/PrivacyContracts.cs}, and the same account as a row of {@code GET
 * api/privacy/erasures}.
 */
public final class PrivacyEvents {

    /** {@code AccountErased {userId, formerUuid, erasureId}}, version 1. */
    public static final String ACCOUNT_ERASED = "AccountErased";

    private PrivacyEvents() {
    }

    /** The erased account, or null when the payload has no usable erasure id or user id. */
    public static ErasedAccount parseAccountErased(RealtimeEnvelope envelope) {
        JsonNode p = envelope == null ? null : envelope.payload();
        if (p == null) {
            return null;
        }
        JsonNode erasureId = p.get("erasureId");
        JsonNode userId = p.get("userId");
        if (erasureId == null || !erasureId.isIntegralNumber() || erasureId.asLong() <= 0
            || userId == null || !userId.isIntegralNumber() || !userId.canConvertToInt()) {
            return null;
        }
        JsonNode uuid = p.get("formerUuid");
        return new ErasedAccount(erasureId.asLong(), userId.asInt(), uuid == null || uuid.isNull() ? null : uuidOrNull(uuid.asText()));
    }

    /** A UUID string as the API stores it; null when blank or not a UUID (only the user id is then used). */
    public static UUID uuidOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(text.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
