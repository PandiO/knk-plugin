package net.knightsandkings.knk.core.domain.admission;

import java.time.Instant;

/**
 * The API's answer to "may this Minecraft account join?" (knk-web-api {@code GET api/Admission/{uuid}?name=},
 * invitations DESIGN §5.2). Under the API's {@code Security:Registration:Mode} other than {@code Invite} it is
 * always admitted.
 *
 * @param admitted  let the player in
 * @param reason    {@link #REASON_NOT_ADMITTED} or {@link #REASON_DEACTIVATED} when not admitted; null otherwise
 * @param code      the claim code, already formatted {@code XXXX-XXXX} for the disconnect screen; only with
 *                  {@link #REASON_NOT_ADMITTED}. A credential: never logged.
 * @param expiresAt when the claim code stops working; null without a code
 */
public record AdmissionResult(boolean admitted, String reason, String code, Instant expiresAt) {
    /** No admitted account holds this UUID: the player gets a claim code to link on the website. */
    public static final String REASON_NOT_ADMITTED = "NotAdmitted";
    /** The account behind this UUID is deactivated or deleted. No code. */
    public static final String REASON_DEACTIVATED = "Deactivated";

    public static AdmissionResult admittedResult() {
        return new AdmissionResult(true, null, null, null);
    }

    public boolean hasCode() {
        return code != null && !code.isBlank();
    }

    /** Never prints the claim code. */
    @Override
    public String toString() {
        return "AdmissionResult[admitted=" + admitted + ", reason=" + reason + ", code=" + (hasCode() ? "<redacted>" : "null")
            + ", expiresAt=" + expiresAt + "]";
    }
}
