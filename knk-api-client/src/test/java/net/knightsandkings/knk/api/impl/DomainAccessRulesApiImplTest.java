package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.NoAuthProvider;
import net.knightsandkings.knk.core.domain.domains.DomainAccessRule;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KNG-56: GET /api/Domains/access-rules for the region flag sync. */
class DomainAccessRulesApiImplTest {

    private final List<Request> seen = new ArrayList<>();
    private String responseJson = "[]";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request request = chain.request();
        seen.add(request);
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(responseJson, MediaType.get("application/json"))).build();
    }).build();
    private final DomainAccessRulesApiImpl api = new DomainAccessRulesApiImpl("http://api.test/api", client,
            new ObjectMapper(), new NoAuthProvider(), executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void readsEveryDomainsRule() {
        responseJson = """
                [{"id":7,"name":"Old Quarter","wgRegionId":"domain_7","allowEntry":false,"allowExit":true,"domainType":"District"},
                 {"id":9,"name":"North Gate","wgRegionId":"domain_9","allowEntry":true,"allowExit":true,"domainType":"GateStructure","extra":1}]
                """;

        List<DomainAccessRule> rules = api.listAccessRules().join();

        assertEquals("GET", seen.get(0).method());
        assertEquals("http://api.test/api/Domains/access-rules", seen.get(0).url().toString());
        assertEquals(new DomainAccessRule(7, "Old Quarter", "domain_7", false, true, "District"), rules.get(0));
        assertEquals("GateStructure", rules.get(1).domainType());
    }

    @Test
    void anEmptyListIsEmpty() {
        assertTrue(api.listAccessRules().join().isEmpty());
    }
}
