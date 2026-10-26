package net.knightsandkings.knk.core.admission;

import java.util.Objects;
import java.util.UUID;

/**
 * A refused join, for the staff notice (PandiO/knk-workspace#159): who tried, why they were turned away and an
 * optional detail (a ban reason, the missing world). Never carries the disconnect screen of the admission gate: that
 * holds the player's claim code, a credential.
 */
public record JoinRefusal(UUID uuid, String name, Reason reason, String detail) {

    /** Why a join was refused, with the words the notice uses. */
    public enum Reason {
        BANNED("banned"),
        NOT_WHITELISTED("not on the whitelist"),
        SERVER_FULL("the server is full"),
        NOT_ADMITTED("invite-only: no admitted account yet (sent a claim code)"),
        DEACTIVATED("their account is deactivated"),
        ACCESS_UNCHECKABLE("their access couldn't be checked (the KnK service is unreachable and the player is unknown)"),
        WORLD_NOT_READY("a required world isn't loaded"),
        HUB_UNUSABLE("the hub can't be used"),
        OTHER("refused");

        private final String text;

        Reason(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    public JoinRefusal {
        Objects.requireNonNull(reason, "reason");
        name = name == null || name.isBlank() ? String.valueOf(uuid) : name;
        detail = detail == null || detail.isBlank() ? null : detail.strip();
    }

    /** The admission gate's message key ({@link AdmissionPolicy#KICK}, ...) as a reason. */
    public static Reason fromAdmissionKey(String messageKey) {
        if (AdmissionPolicy.KICK.equals(messageKey)) {
            return Reason.NOT_ADMITTED;
        }
        if (AdmissionPolicy.DEACTIVATED.equals(messageKey)) {
            return Reason.DEACTIVATED;
        }
        if (AdmissionPolicy.UNAVAILABLE.equals(messageKey)) {
            return Reason.ACCESS_UNCHECKABLE;
        }
        return Reason.OTHER;
    }

    /** The notice's text without colours: "Steve tried to join and was refused: banned (griefing)". */
    public String describe() {
        return name + " tried to join and was refused: " + reason.text() + (detail != null ? " (" + detail + ")" : "");
    }
}
