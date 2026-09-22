package net.knightsandkings.knk.api.mapper;

import net.knightsandkings.knk.api.dto.DomainSummaryDto;
import net.knightsandkings.knk.api.dto.PagedResultDto;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.domains.KnkDomainSummary;

import java.util.Collections;
import java.util.List;

public final class DomainCatalogMapper {
    private DomainCatalogMapper() {}

    public static KnkDomainSummary toCore(DomainSummaryDto dto) {
        if (dto == null) return null;
        return new KnkDomainSummary(dto.id(), dto.name(), dto.domainType());
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
