package net.knightsandkings.knk.core.ports.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.admission.AdmissionResult;

/**
 * The admission gate's question to knk-web-api (invitations plan §6.2): {@code GET api/Admission/{uuid}?name=},
 * with the plugin service key. An admitted check also lets the account's name follow the Mojang name.
 */
public interface AdmissionApi {
    /**
     * @param uuid the Mojang-authenticated UUID from pre-login
     * @param name the Minecraft name ({@code ^[A-Za-z0-9_]{1,16}$}; anything else is a 400)
     * @return the API's answer; completes exceptionally when the API can't be reached or refuses the request
     */
    CompletableFuture<AdmissionResult> check(UUID uuid, String name);
}
