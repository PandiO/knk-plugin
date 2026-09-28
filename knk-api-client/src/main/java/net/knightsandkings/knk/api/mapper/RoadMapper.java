package net.knightsandkings.knk.api.mapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.dto.RoadBreadcrumbPointDto;
import net.knightsandkings.knk.api.dto.RoadComponentDto;
import net.knightsandkings.knk.api.dto.RoadEdgeDto;
import net.knightsandkings.knk.api.dto.RoadEdgeRecordDto;
import net.knightsandkings.knk.api.dto.RoadEdgeUpdateDto;
import net.knightsandkings.knk.api.dto.RoadEdgeUpdateResultDto;
import net.knightsandkings.knk.api.dto.RoadErrorDto;
import net.knightsandkings.knk.api.dto.RoadMaterialDto;
import net.knightsandkings.knk.api.dto.RoadNetworkMetaDto;
import net.knightsandkings.knk.api.dto.RoadNodeAnchorDto;
import net.knightsandkings.knk.api.dto.RoadNodeDto;
import net.knightsandkings.knk.api.dto.RoadNodeUpdateDto;
import net.knightsandkings.knk.api.dto.RoadProfileDto;
import net.knightsandkings.knk.api.dto.RoadProfileUpsertDto;
import net.knightsandkings.knk.api.dto.RoadSeedCreateDto;
import net.knightsandkings.knk.api.dto.RoadSeedDto;
import net.knightsandkings.knk.api.dto.RoadSeedLocationDto;
import net.knightsandkings.knk.api.dto.RoadStreetRefDto;
import net.knightsandkings.knk.api.dto.RoadSurveyCreateDto;
import net.knightsandkings.knk.api.dto.RoadSurveyDto;
import net.knightsandkings.knk.api.dto.RoadTileDto;
import net.knightsandkings.knk.api.dto.RoadTileGraphDto;
import net.knightsandkings.knk.api.dto.RoadTileGraphEdgeDto;
import net.knightsandkings.knk.api.dto.RoadTileGraphNodeDto;
import net.knightsandkings.knk.api.dto.RoadTileGraphUpsertDto;
import net.knightsandkings.knk.api.dto.RoadTileUpsertResultDto;
import net.knightsandkings.knk.core.domain.roads.RoadApiError;
import net.knightsandkings.knk.core.domain.roads.RoadBreadcrumbPoint;
import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.domain.roads.RoadComponent;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeRecord;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeUpdate;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeUpdateResult;
import net.knightsandkings.knk.core.domain.roads.RoadMaterialRole;
import net.knightsandkings.knk.core.domain.roads.RoadNetworkMeta;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeAnchor;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.domain.roads.RoadNodeUpdate;
import net.knightsandkings.knk.core.domain.roads.RoadProfile;
import net.knightsandkings.knk.core.domain.roads.RoadProfileUpsert;
import net.knightsandkings.knk.core.domain.roads.RoadSeed;
import net.knightsandkings.knk.core.domain.roads.RoadSeedCreate;
import net.knightsandkings.knk.core.domain.roads.RoadSeedLocation;
import net.knightsandkings.knk.core.domain.roads.RoadSeedSource;
import net.knightsandkings.knk.core.domain.roads.RoadSurvey;
import net.knightsandkings.knk.core.domain.roads.RoadSurveyCreate;
import net.knightsandkings.knk.core.domain.roads.RoadTile;
import net.knightsandkings.knk.core.domain.roads.RoadTileGraph;
import net.knightsandkings.knk.core.domain.roads.RoadTileUpsertResult;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.roads.build.NodeMatcher;
import net.knightsandkings.knk.core.roads.build.ProfileSet;
import net.knightsandkings.knk.core.roads.build.SkeletonGraph;
import net.knightsandkings.knk.core.roads.build.TileBuildResult;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.survey.ProposedProfile;

/**
 * Road navigation DTOs (knk-web-api's RoadDtos.cs) to and from knk-core records (plan Phase 2e).
 * Enum values travel as their API names ({@code apiName()}/{@code fromApiName()}); the opaque
 * {@code stats} objects travel as Jackson trees and are carried in knk-core as JSON text
 * ({@code SurveyStats.toJson()}/{@code fromJson()} read and write that text).
 *
 * <p>The last section adapts knk-core records to each other for Phase 3 (the 2c and 2d status
 * blocks asked 2e for these): a {@link RoadProfile} to the builder's {@link ProfileSet.Profile} and
 * the router's {@link RoadNetworkSnapshot.Profile}, a {@link RoadTileGraph} to the
 * {@link NodeMatcher.PreviousGraph} and the {@link SkeletonGraph.Anchor}s of a rebuild.
 */
public final class RoadMapper {
    /** Only ever parses plain JSON text into a tree; no dates, no records. */
    private static final ObjectMapper JSON = new ObjectMapper();

    private RoadMapper() {}

    // ---- read side ---------------------------------------------------------------------------

    public static RoadTile mapTile(RoadTileDto dto) {
        if (dto == null) {
            return null;
        }
        return new RoadTile(dto.id(), dto.world(), dto.tileX(), dto.tileZ(), dto.version(), dto.builtAt(),
            dto.builderVersion(), dto.dirty(), dto.cellCount(), dto.nodeCount(), dto.edgeCount(), dto.levelCount(),
            strings(dto.warnings()));
    }

    public static RoadNode mapNode(RoadNodeDto dto) {
        return new RoadNode(dto.id(), dto.x(), dto.y(), dto.z(), RoadNodeKind.fromApiName(dto.kind()), dto.name(),
            dto.componentId(), dto.locked());
    }

    public static RoadEdge mapEdge(RoadEdgeDto dto) {
        return new RoadEdge(dto.id(), dto.fromNodeId(), dto.toNodeId(), geometry(dto.geometry()), dto.length(),
            dto.avgWidth(), optional(dto.profileId()), optional(dto.streetId()), dto.costMultiplier(),
            flags(dto.flags()), ints(dto.gateDoorIds()), ints(dto.domainIds()), strings(dto.regionIds()),
            RoadEdgeSource.fromApiName(dto.source()), "Stale".equalsIgnoreCase(dto.status()));
    }

    public static RoadTileGraph mapTileGraph(RoadTileGraphDto dto) {
        if (dto == null) {
            return null;
        }
        return new RoadTileGraph(mapTile(dto.tile()), mapNodes(dto.nodes()), mapEdges(dto.edges()));
    }

    public static List<RoadNode> mapNodes(List<RoadNodeDto> dtos) {
        return dtos == null ? List.of() : dtos.stream().map(RoadMapper::mapNode).toList();
    }

    public static List<RoadEdge> mapEdges(List<RoadEdgeDto> dtos) {
        return dtos == null ? List.of() : dtos.stream().map(RoadMapper::mapEdge).toList();
    }

    public static RoadTileUpsertResult mapUpsertResult(RoadTileUpsertResultDto dto) {
        if (dto == null) {
            return null;
        }
        return new RoadTileUpsertResult(mapTile(dto.tile()), dto.nodesCreated(), dto.nodesUpdated(), dto.nodesDeleted(),
            dto.edgesCreated(), dto.edgesUpdated(), dto.edgesDeleted(), dto.stitchEdges(), dto.labelledEdges(),
            dto.unlabelledEdges(), strings(dto.conflicts()), mapNodes(dto.deletedNodes()), ints(dto.bumpedTileIds()));
    }

    public static ProposedProfile.Material mapMaterial(RoadMaterialDto dto) {
        return new ProposedProfile.Material(dto.material(), RoadMaterialRole.fromApiName(dto.role()), dto.ambiguous(),
            dto.centreShare(), dto.edgeShare(), dto.samples());
    }

    public static RoadProfile mapProfile(RoadProfileDto dto) {
        if (dto == null) {
            return null;
        }
        List<ProposedProfile.Material> materials = dto.materials() == null ? List.of()
            : dto.materials().stream().map(RoadMapper::mapMaterial).toList();
        return new RoadProfile(dto.id(), dto.name(), RoadClass.fromApiName(dto.roadClass()), dto.costMultiplier(),
            materials, dto.widthMin(), dto.widthMax(), dto.sampleCount(), dto.enabled(), ints(dto.scopeTownIds()),
            statsText(dto.stats()), dto.createdAt(), dto.updatedAt());
    }

    public static List<RoadProfile> mapProfiles(List<RoadProfileDto> dtos) {
        return dtos == null ? List.of() : dtos.stream().map(RoadMapper::mapProfile).toList();
    }

    public static RoadBreadcrumbPoint mapBreadcrumbPoint(RoadBreadcrumbPointDto dto) {
        return new RoadBreadcrumbPoint(dto.x(), dto.y(), dto.z(), dto.onRoad());
    }

    public static RoadSurvey mapSurvey(RoadSurveyDto dto) {
        if (dto == null) {
            return null;
        }
        List<RoadBreadcrumbPoint> breadcrumb = dto.breadcrumb() == null ? List.of()
            : dto.breadcrumb().stream().map(RoadMapper::mapBreadcrumbPoint).toList();
        return new RoadSurvey(dto.id(), dto.world(), optional(dto.profileId()), optional(dto.startedByUserId()),
            dto.startedAt(), dto.endedAt(), dto.sampleCount(), breadcrumb, statsText(dto.stats()));
    }

    public static RoadSeed mapSeed(RoadSeedDto dto) {
        return new RoadSeed(dto.id(), dto.world(), dto.x(), dto.y(), dto.z(), RoadSeedSource.fromApiName(dto.source()),
            optional(dto.surveyId()), dto.note(), dto.createdAt());
    }

    public static RoadSeedLocation mapSeedLocation(RoadSeedLocationDto dto) {
        return new RoadSeedLocation(dto.domainId(), dto.domainType(), dto.name(), dto.x(), dto.y(), dto.z());
    }

    public static RoadNetworkSnapshot.Street mapStreet(RoadStreetRefDto dto) {
        return new RoadNetworkSnapshot.Street(dto.id(), dto.name());
    }

    public static RoadComponent mapComponent(RoadComponentDto dto) {
        return new RoadComponent(dto.id(), dto.nodeCount());
    }

    public static RoadNetworkMeta mapMeta(RoadNetworkMetaDto dto) {
        if (dto == null) {
            return null;
        }
        List<RoadNetworkSnapshot.Street> streets = dto.streets() == null ? List.of()
            : dto.streets().stream().map(RoadMapper::mapStreet).toList();
        List<RoadComponent> components = dto.components() == null ? List.of()
            : dto.components().stream().map(RoadMapper::mapComponent).toList();
        return new RoadNetworkMeta(mapProfiles(dto.profiles()), streets, components);
    }

    public static RoadEdgeUpdateResult mapEdgeUpdateResult(RoadEdgeUpdateResultDto dto) {
        if (dto == null) {
            return null;
        }
        return new RoadEdgeUpdateResult(mapEdge(dto.edge()), ints(dto.changedEdgeIds()));
    }

    /**
     * The {@code {error, message}} body of a refused road call, when the exception carries one
     * (any other body, e.g. ASP.NET's model-validation problem details, gives empty).
     */
    public static Optional<RoadApiError> error(ApiException exception) {
        if (exception == null || exception.getResponseBody() == null || exception.getResponseBody().isBlank()) {
            return Optional.empty();
        }
        try {
            RoadErrorDto dto = JSON.readValue(exception.getResponseBody(), RoadErrorDto.class);
            return dto.error() == null ? Optional.empty() : Optional.of(new RoadApiError(dto.error(), dto.message()));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    // ---- write side --------------------------------------------------------------------------

    public static RoadTileGraphUpsertDto toUpsertDto(TileBuildResult build) {
        List<RoadTileGraphNodeDto> nodes = build.nodes().stream().map(RoadMapper::toNodeDto).toList();
        List<RoadTileGraphEdgeDto> edges = build.edges().stream().map(RoadMapper::toEdgeDto).toList();
        return new RoadTileGraphUpsertDto(build.builderVersion(), build.cellCount(), build.levelCount(),
            build.warningTexts(), nodes, edges);
    }

    public static RoadTileGraphNodeDto toNodeDto(TileBuildResult.Node node) {
        return new RoadTileGraphNodeDto(node.key(), boxed(node.existingId()), node.x(), node.y(), node.z(),
            node.kind().apiName());
    }

    public static RoadTileGraphEdgeDto toEdgeDto(TileBuildResult.Edge edge) {
        return new RoadTileGraphEdgeDto(boxed(edge.existingId()), edge.fromKey(), edge.toKey(), geometry(edge.geometry()),
            edge.length(), edge.avgWidth(), boxed(edge.profileId()), edge.gateDoorIds(), edge.domainIds(),
            edge.regionIds());
    }

    public static RoadMaterialDto toMaterialDto(ProposedProfile.Material material) {
        return new RoadMaterialDto(material.material(), material.role().apiName(), material.ambiguous(),
            material.centreShare(), material.edgeShare(), material.samples());
    }

    public static RoadProfileUpsertDto toProfileUpsertDto(RoadProfileUpsert profile) {
        return new RoadProfileUpsertDto(profile.name(), profile.roadClass().apiName(), profile.costMultiplier(),
            profile.materials().stream().map(RoadMapper::toMaterialDto).toList(), profile.widthMin(),
            profile.widthMax(), profile.sampleCount(), profile.enabled(), profile.scopeTownIds(),
            statsTree(profile.statsJson()));
    }

    public static RoadBreadcrumbPointDto toBreadcrumbPointDto(RoadBreadcrumbPoint point) {
        return new RoadBreadcrumbPointDto(point.x(), point.y(), point.z(), point.onRoad());
    }

    public static RoadSurveyCreateDto toSurveyCreateDto(RoadSurveyCreate survey) {
        return new RoadSurveyCreateDto(survey.world(), boxed(survey.profileId()), survey.startedAt(), survey.endedAt(),
            survey.sampleCount(), survey.breadcrumb().stream().map(RoadMapper::toBreadcrumbPointDto).toList(),
            statsTree(survey.statsJson()));
    }

    public static RoadSeedCreateDto toSeedCreateDto(RoadSeedCreate seed) {
        return new RoadSeedCreateDto(seed.world(), seed.x(), seed.y(), seed.z(), seed.source().apiName(),
            boxed(seed.surveyId()), seed.note());
    }

    public static RoadNodeUpdateDto toNodeUpdateDto(RoadNodeUpdate update) {
        return new RoadNodeUpdateDto(update.name(), update.clearName(),
            update.kind() == null ? null : update.kind().apiName(), update.locked());
    }

    public static RoadNodeAnchorDto toAnchorDto(RoadNodeAnchor anchor) {
        return new RoadNodeAnchorDto(anchor.world(), anchor.x(), anchor.y(), anchor.z(), anchor.name());
    }

    public static RoadEdgeUpdateDto toEdgeUpdateDto(RoadEdgeUpdate update) {
        return new RoadEdgeUpdateDto(update.streetId(), update.clearStreet(), update.propagate(), update.profileId(),
            update.clearProfile(), update.costMultiplier(), flagNames(update.flags()));
    }

    public static RoadEdgeRecordDto toEdgeRecordDto(RoadEdgeRecord record) {
        return new RoadEdgeRecordDto(record.world(), geometry(record.geometry()),
            record.length().isPresent() ? record.length().getAsDouble() : null, record.avgWidth(),
            boxed(record.profileId()), boxed(record.streetId()), record.gateDoorIds(), record.domainIds(),
            record.regionIds());
    }

    // ---- knk-core to knk-core (Phase 3 wiring) -----------------------------------------------

    /** The builder's view of a profile (Phase 2c status → 2e); scope and materials carried over. */
    public static ProfileSet.Profile toBuilderProfile(RoadProfile profile) {
        return new ProfileSet.Profile(profile.id(), profile.name(), profile.enabled(), profile.widthMin(),
            profile.widthMax(), new HashSet<>(profile.scopeTownIds()), profile.materials());
    }

    /** The builder's profiles: every profile of the list, enabled or not ({@code ProfileSet} drops disabled ones). */
    public static List<ProfileSet.Profile> toBuilderProfiles(List<RoadProfile> profiles) {
        return profiles.stream().map(RoadMapper::toBuilderProfile).toList();
    }

    /** The router's view of a profile (Phase 2d status → 2e). */
    public static RoadNetworkSnapshot.Profile toSnapshotProfile(RoadProfile profile) {
        return new RoadNetworkSnapshot.Profile(profile.id(), profile.name(), profile.roadClass(),
            profile.costMultiplier());
    }

    /**
     * The previous build of a tile for {@code NodeMatcher} (Phase 2c status → 2e): every downloaded
     * node and edge; Stitch edges may stay (their other node never matches).
     */
    public static NodeMatcher.PreviousGraph toPreviousGraph(RoadTileGraph graph) {
        if (graph == null) {
            return NodeMatcher.PreviousGraph.EMPTY;
        }
        List<NodeMatcher.PreviousNode> nodes = graph.nodes().stream()
            .map(n -> new NodeMatcher.PreviousNode(n.id(), n.x(), n.y(), n.z(), n.kind(), n.locked()))
            .toList();
        List<NodeMatcher.PreviousEdge> edges = graph.edges().stream()
            .map(e -> new NodeMatcher.PreviousEdge(e.id(), e.fromNodeId(), e.toNodeId(), e.geometry()))
            .toList();
        return new NodeMatcher.PreviousGraph(nodes, edges);
    }

    /** The Anchor-kind nodes of a tile download, as the skeleton builder's anchors (Phase 2c status → 2e). */
    public static List<SkeletonGraph.Anchor> toAnchors(RoadTileGraph graph) {
        if (graph == null) {
            return List.of();
        }
        return graph.nodes().stream()
            .filter(n -> n.kind() == RoadNodeKind.ANCHOR)
            .map(n -> new SkeletonGraph.Anchor(n.id(), n.x(), n.y(), n.z()))
            .toList();
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** {@code [[x,y,z],…]} to the knk-core {@code List<int[]>} (points copied). */
    static List<int[]> geometry(int[][] points) {
        if (points == null) {
            return List.of();
        }
        List<int[]> out = new ArrayList<>(points.length);
        for (int[] p : points) {
            out.add(p == null ? null : p.clone());
        }
        return out;
    }

    /** The knk-core {@code List<int[]>} to {@code [[x,y,z],…]} (points copied). */
    static int[][] geometry(List<int[]> points) {
        int[][] out = new int[points.size()][];
        for (int i = 0; i < out.length; i++) {
            out[i] = points.get(i).clone();
        }
        return out;
    }

    static Set<RoadEdgeFlag> flags(List<String> names) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        EnumSet<RoadEdgeFlag> out = EnumSet.noneOf(RoadEdgeFlag.class);
        for (String name : names) {
            if (name != null && !name.isBlank() && !"None".equalsIgnoreCase(name.trim())) {
                out.add(RoadEdgeFlag.fromApiName(name));
            }
        }
        return out;
    }

    static List<String> flagNames(Set<RoadEdgeFlag> flags) {
        if (flags == null) {
            return null;
        }
        return Arrays.stream(RoadEdgeFlag.values()).filter(flags::contains).map(RoadEdgeFlag::apiName).toList();
    }

    /** A JSON tree as text for knk-core ({@code null} tree or JSON {@code null} → {@code null}). */
    static String statsText(JsonNode stats) {
        return stats == null || stats.isNull() ? null : stats.toString();
    }

    /** JSON text from knk-core as a tree for the wire ({@code null} or blank → {@code null} = keep). */
    static JsonNode statsTree(String statsJson) {
        if (statsJson == null || statsJson.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(statsJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("stats is not JSON: " + e.getMessage(), e);
        }
    }

    private static OptionalInt optional(Integer value) {
        return value == null ? OptionalInt.empty() : OptionalInt.of(value);
    }

    private static Integer boxed(OptionalInt value) {
        return value.isPresent() ? value.getAsInt() : null;
    }

    private static List<Integer> ints(List<Integer> values) {
        return values == null ? List.of() : values;
    }

    private static List<String> strings(List<String> values) {
        return values == null ? List.of() : values;
    }
}
