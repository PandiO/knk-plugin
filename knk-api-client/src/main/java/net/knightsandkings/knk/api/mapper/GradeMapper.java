package net.knightsandkings.knk.api.mapper;

import net.knightsandkings.knk.api.dto.GradeDto;
import net.knightsandkings.knk.api.dto.PagedResultDto;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.item.KnkGrade;

import java.util.Collections;
import java.util.List;

public final class GradeMapper {
    private GradeMapper() {}

    public static KnkGrade toCore(GradeDto dto) {
        if (dto == null) return null;
        return new KnkGrade(dto.id(), dto.name(), dto.stars(), dto.dropChance(), dto.enchantLevelCapDivisor());
    }

    public static Page<KnkGrade> mapPagedList(PagedResultDto<GradeDto> result) {
        if (result == null) {
            return new Page<>(Collections.emptyList(), 0, 1, 10);
        }

        List<KnkGrade> items = result.items() != null
                ? result.items().stream().map(GradeMapper::toCore).toList()
                : Collections.emptyList();

        int totalCount = result.totalCount() != null ? result.totalCount() : items.size();
        int pageNumber = result.pageNumber() != null ? result.pageNumber() : 1;
        int pageSize = result.pageSize() != null ? result.pageSize() : Math.max(items.size(), 1);

        return new Page<>(items, totalCount, pageNumber, pageSize);
    }
}
