package net.knightsandkings.knk.api.mapper;

import net.knightsandkings.knk.api.dto.PagedResultDto;
import net.knightsandkings.knk.api.dto.TagDto;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.item.KnkTag;

import java.util.Collections;
import java.util.List;

public final class TagMapper {
    private TagMapper() {}

    public static KnkTag toCore(TagDto dto) {
        if (dto == null) return null;
        return new KnkTag(dto.id(), dto.name());
    }

    public static Page<KnkTag> mapPagedList(PagedResultDto<TagDto> result) {
        if (result == null) {
            return new Page<>(Collections.emptyList(), 0, 1, 10);
        }

        List<KnkTag> items = result.items() != null
                ? result.items().stream().map(TagMapper::toCore).toList()
                : Collections.emptyList();

        int totalCount = result.totalCount() != null ? result.totalCount() : items.size();
        int pageNumber = result.pageNumber() != null ? result.pageNumber() : 1;
        int pageSize = result.pageSize() != null ? result.pageSize() : Math.max(items.size(), 1);

        return new Page<>(items, totalCount, pageNumber, pageSize);
    }
}
