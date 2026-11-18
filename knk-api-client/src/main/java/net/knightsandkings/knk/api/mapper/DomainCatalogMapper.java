package net.knightsandkings.knk.api.mapper;

import net.knightsandkings.knk.api.dto.DomainSummaryDto;
import net.knightsandkings.knk.api.dto.PagedResultDto;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.domains.KnkDomainSummary;
import net.knightsandkings.knk.core.domain.location.KnkLocation;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.List;

public final class DomainCatalogMapper {
    private DomainCatalogMapper() {}

    public static KnkDomainSummary toCore(DomainSummaryDto dto) {
        if (dto == null) return null;
        JsonNode location = dto.location();
        boolean sent = location != null && !location.isMissingNode();
        return new KnkDomainSummary(dto.id(), dto.name(), dto.domainType(), dto.navigationDefault(), dto.roadAccess(),
            dto.wgRegionId(), dto.worldName(), sent ? toLocation(location) : null, sent);
    }

    /** The search's {@code location} object (destination engine E12); null for JSON null or anything not an object. */
    static KnkLocation toLocation(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        return new KnkLocation(intOrNull(node.get("id")), textOrNull(node.get("name")), doubleOrNull(node.get("x")),
            doubleOrNull(node.get("y")), doubleOrNull(node.get("z")), floatOrNull(node.get("yaw")),
            floatOrNull(node.get("pitch")), textOrNull(node.get("world")));
    }

    private static Integer intOrNull(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isNumber()) return node.intValue();
        try {
            return Integer.valueOf(node.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double doubleOrNull(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isNumber()) return node.doubleValue();
        try {
            return Double.valueOf(node.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Float floatOrNull(JsonNode node) {
        Double value = doubleOrNull(node);
        return value == null ? null : value.floatValue();
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    public static Page<KnkDomainSummary> mapPagedList(PagedResultDto<DomainSummaryDto> result) {
        if (result == null) {
            return new Page<>(Collections.emptyList(), 0, 1, 10);
        }

        List<KnkDomainSummary> items = result.items() != null
                ? result.items().stream().map(DomainCatalogMapper::toCore).toList()
                : Collections.emptyList();

        int totalCount = result.totalCount() != null ? result.totalCount() : items.size();
        int pageNumber = result.pageNumber() != null ? result.pageNumber() : 1;
        int pageSize = result.pageSize() != null ? result.pageSize() : Math.max(items.size(), 1);

        return new Page<>(items, totalCount, pageNumber, pageSize);
    }
}
