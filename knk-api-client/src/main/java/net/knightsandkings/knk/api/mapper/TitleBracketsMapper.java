package net.knightsandkings.knk.api.mapper;

import net.knightsandkings.knk.api.dto.TitleBracketDto;
import net.knightsandkings.knk.core.domain.users.TitleBracket;

import java.util.List;
import java.util.Objects;

public final class TitleBracketsMapper {

    private TitleBracketsMapper() {
    }

    public static TitleBracket toCore(TitleBracketDto dto) {
        return new TitleBracket(dto.id(), dto.maleName(), dto.femaleName(), dto.minExperience(), dto.salary(),
                dto.coinBonus(), dto.gemBonus(), dto.expBonus());
    }

    public static List<TitleBracket> toCore(List<TitleBracketDto> dtos) {
        return dtos == null ? List.of() : dtos.stream().filter(Objects::nonNull).map(TitleBracketsMapper::toCore).toList();
    }
}
