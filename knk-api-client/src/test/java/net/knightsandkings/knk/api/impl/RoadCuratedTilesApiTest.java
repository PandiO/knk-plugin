package net.knightsandkings.knk.api.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import net.knightsandkings.knk.api.auth.NoAuthProvider;
import net.knightsandkings.knk.api.dto.RoadEdgeDto;
import net.knightsandkings.knk.api.dto.RoadTileDto;
import net.knightsandkings.knk.api.mapper.RoadMapper;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeUpdate;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.domain.roads.RoadTile;
import net.knightsandkings.knk.core.domain.roads.RoadTileProposalSummary;
import net.knightsandkings.knk.core.domain.roads.RoadTileState;
import net.knightsandkings.knk.core.roads.build.TileProposal;
import net.knightsandkings.knk.core.roads.build.TileProposal.End;
import net.knightsandkings.knk.core.roads.build.TileProposal.Item;
import net.knightsandkings.knk.core.roads.build.TileProposal.Kind;
import okio.Buffer;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Rev. 6 Part B (plan §5.7): tile state, confirmed edges and stored proposals over the wire. */
class RoadCuratedTilesApiTest {
    private static final String TILE_JSON = "{\"id\":7,\"world\":\"world\",\"tileX\":2,\"tileZ\":-2,\"version\":4,"
        + "\"builtAt\":\"2026-10-04T15:48:00\",\"builderVersion\":5,\"dirty\":false,\"cellCount\":10,\"nodeCount\":3,"
        + "\"edgeCount\":2,\"levelCount\":1,\"warnings\":[],\"state\":\"Curated\",\"curatedAt\":\"2026-10-05T08:00:00\"}";

    private final ObjectMapper mapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .registerModule(new JavaTimeModule());
    private final List<Request> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int status = 200;
    private String responseJson = "{}";

    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request request = chain.request();
        seen.add(request);
        if (request.body() != null) {
            Buffer buffer = new Buffer();
            request.body().writeTo(buffer);
            bodies.add(buffer.readUtf8());
        } else {
            bodies.add(null);
        }
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("x")
            .body(ResponseBody.create(status == 204 ? "" : responseJson, MediaType.get("application/json"))).build();
    }).build();

    private final RoadNetworkCommandApiImpl commands = new RoadNetworkCommandApiImpl("http://api.test/api", client, mapper,
        new NoAuthProvider(), executor, false);
    private final RoadNetworkQueryApiImpl queries = new RoadNetworkQueryApiImpl("http://api.test/api", client, mapper,
        new NoAuthProvider(), executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    private static TileProposal proposal() {
        Item added = new Item(1, Kind.EDGE_ADDED, 0, new End(3588, 1400, 45, -520, RoadNodeKind.JUNCTION),
            new End(End.NEW, 1395, 45, -514, RoadNodeKind.JUNCTION), List.of(new int[] {1400, 45, -520}, new int[] {1395, 45, -514}),
            List.of(), 7.8, 4, OptionalInt.of(2), List.of(), List.of(5), List.of("cinix"), null, null, "");
        Item moved = new Item(2, Kind.NODE_MOVED, 0, null, null, List.of(), List.of(), 0, 0, OptionalInt.empty(), List.of(),
            List.of(), List.of(), new End(3693, 1441, 42, -563, RoadNodeKind.JUNCTION), new int[] {1433, 42, -556}, "");
        Item rejected = new Item(4, Kind.EDGE_CHANGED, 10069, new End(3587, 1, 45, 1, RoadNodeKind.JUNCTION),
            new End(3588, 9, 45, 1, RoadNodeKind.JUNCTION), List.of(new int[] {1, 45, 1}, new int[] {9, 45, 1}),
            List.of(new int[] {1, 45, 1}, new int[] {5, 45, 3}, new int[] {9, 45, 1}), 8, 3, OptionalInt.empty(), List.of(7),
            List.of(), List.of(), null, null, "gate doors [] → [7]");
        return new TileProposal(4, 6, "Pandi", 900, 2, List.of("Cell cap hit at (1, 2, 3)"), List.of(added, moved), List.of(rejected));
    }

    @Test
    void tileStateAndCuratedAtMap() throws Exception {
        RoadTile tile = RoadMapper.mapTile(mapper.readValue(TILE_JSON, RoadTileDto.class));
        RoadTile older = RoadMapper.mapTile(mapper.readValue(TILE_JSON.replace(",\"state\":\"Curated\"", ""), RoadTileDto.class));

        assertEquals(RoadTileState.CURATED, tile.state());
        assertTrue(tile.proposesChanges());
        assertEquals(8, tile.curatedAt().getHour());
        assertEquals(RoadTileState.DETECTED, older.state()); // an API before rev. 6
    }

    @Test
    void edgeConfirmedMapsAndIsSentOnlyWhenSet() throws Exception {
        String edge = "{\"id\":12,\"fromNodeId\":2,\"toNodeId\":3,\"geometry\":[[0,64,0],[9,64,0]],\"length\":9,\"avgWidth\":2,"
            + "\"costMultiplier\":1.0,\"source\":\"Detected\",\"status\":\"Ok\",\"confirmed\":true}";
        assertTrue(RoadMapper.mapEdge(mapper.readValue(edge, RoadEdgeDto.class)).confirmed());

        assertEquals("{\"clearStreet\":false,\"propagate\":false,\"clearProfile\":false,\"confirmed\":true}",
            mapper.writeValueAsString(RoadMapper.toEdgeUpdateDto(RoadEdgeUpdate.confirmed(true))));
        assertFalse(mapper.writeValueAsString(RoadMapper.toEdgeUpdateDto(RoadEdgeUpdate.costMultiplier(2))).contains("confirmed"));
    }

    @Test
    void setTileStatePutsTheStateName() throws Exception {
        responseJson = TILE_JSON.replace("Curated", "Detected");

        RoadTile tile = commands.setTileState("world", 2, -2, RoadTileState.DETECTED).join();

        assertEquals("PUT", seen.get(0).method());
        assertEquals("http://api.test/api/road-tiles/world/2/-2/state", seen.get(0).url().toString());
        assertEquals("{\"state\":\"Detected\"}", bodies.get(0));
        assertEquals(RoadTileState.DETECTED, tile.state());
    }

    @Test
    void saveProposalSendsItemsCountsAndRejected_andTheReadRoundTrips() throws Exception {
        responseJson = "{\"tileId\":7,\"world\":\"world\",\"tileX\":2,\"tileZ\":-2,\"baseVersion\":4,\"tileVersion\":4,"
            + "\"builderVersion\":6,\"createdBy\":\"Pandi\",\"addedCount\":1,\"movedCount\":1,\"rejectedCount\":1}";

        RoadTileProposalSummary summary = commands.saveProposal("world", 2, -2, proposal()).join();

        assertEquals("http://api.test/api/road-tiles/world/2/-2/proposal", seen.get(0).url().toString());
        JsonNode body = mapper.readTree(bodies.get(0));
        assertEquals(1, body.get("addedCount").asInt());
        assertEquals(1, body.get("movedCount").asInt());
        assertEquals(0, body.get("removedCount").asInt());
        assertEquals("EDGE_ADDED", body.get("items").get(0).get("kind").asText());
        assertEquals("1 added edge 8 m (#3588 → new junction)", body.get("items").get(0).get("summary").asText());
        assertEquals(0, body.get("items").get(0).get("to").get("nodeId").asInt());
        assertFalse(body.get("items").get(1).has("geometry")); // node items carry no polyline
        assertEquals("gate doors [] → [7]", body.get("rejected").get(0).get("note").asText());
        assertEquals(2, summary.pendingCount());

        // The API stores the arrays as they came; reading them back gives the same proposal.
        responseJson = "{\"tileId\":7,\"world\":\"world\",\"tileX\":2,\"tileZ\":-2,\"baseVersion\":4,\"tileVersion\":5,"
            + "\"builderVersion\":6,\"createdBy\":\"Pandi\",\"cellCount\":900,\"levelCount\":2,"
            + "\"warnings\":[\"Cell cap hit at (1, 2, 3)\"],\"items\":" + body.get("items") + ",\"rejected\":" + body.get("rejected") + "}";
        TileProposal read = queries.proposal("world", 2, -2).join().orElseThrow();

        assertEquals(proposal().items().size(), read.items().size());
        Item added = read.items().get(0);
        assertEquals(Kind.EDGE_ADDED, added.kind());
        assertTrue(added.to().isNew());
        assertEquals(OptionalInt.of(2), added.profileId());
        assertEquals(List.of("cinix"), added.regionIds());
        assertArrayEquals(new int[] {1433, 42, -556}, read.items().get(1).target());
        assertEquals(3, read.rejected().get(0).before().size());
        assertEquals(List.of(7), read.rejected().get(0).gateDoorIds());
        assertEquals((900), read.cellCount());
        assertEquals(List.of("Cell cap hit at (1, 2, 3)"), read.warnings());
    }

    @Test
    void aTileWithoutProposalIsEmpty_andProposalsListTheWorld() {
        status = 404;
        responseJson = "{\"error\":\"NotFound\",\"message\":\"no proposal\"}";
        assertEquals(Optional.empty(), queries.proposal("world", 0, 0).join());

        status = 200;
        responseJson = "[{\"tileId\":7,\"world\":\"world\",\"tileX\":2,\"tileZ\":-2,\"baseVersion\":4,\"tileVersion\":5,"
            + "\"builderVersion\":6,\"addedCount\":3,\"removedCount\":2,\"changedCount\":1,\"movedCount\":0,\"rejectedCount\":4}]";
        List<RoadTileProposalSummary> list = queries.proposals("my world").join();

        assertEquals("http://api.test/api/road-tiles/proposals?world=my+world", seen.get(1).url().toString());
        assertEquals(6, list.get(0).pendingCount());
        assertEquals(4, list.get(0).rejectedCount());
    }

    @Test
    void deleteProposalIsTrueOn204AndFalseOn404() {
        status = 204;
        assertTrue(commands.deleteProposal("world", 2, -2).join());
        assertEquals("DELETE", seen.get(0).method());
        status = 404;
        responseJson = "{\"error\":\"NotFound\",\"message\":\"no proposal\"}";
        assertFalse(commands.deleteProposal("world", 2, -2).join());
    }
}
