package net.knightsandkings.knk.api.mapper;

import java.util.List;

import net.knightsandkings.knk.api.dto.LeaderboardDtos;
import net.knightsandkings.knk.core.domain.leaderboards.LeaderboardBoard;
import net.knightsandkings.knk.core.domain.leaderboards.LeaderboardView;

/** Leaderboard DTOs → domain (KNG-34). */
public final class LeaderboardMapper {
    private LeaderboardMapper() {}

    public static List<LeaderboardBoard> fromDto(List<LeaderboardDtos.Board> dtos) {
        if (dtos == null) {
            return List.of();
        }
        return dtos.stream()
                .filter(b -> b != null && b.boardKey() != null)
                .map(b -> new LeaderboardBoard(b.boardKey(), b.metric(), b.context(), b.label() == null ? b.boardKey() : b.label(),
                        b.unit(), b.periods(), Boolean.TRUE.equals(b.alwaysPublic())))
                .toList();
    }

    public static LeaderboardView fromDto(LeaderboardDtos.View dto) {
        if (dto == null) {
            return null;
        }
        List<LeaderboardView.Entry> entries = dto.entries() == null ? List.of() : dto.entries().stream()
                .filter(e -> e != null && e.rank() != null && e.userId() != null)
                .map(e -> new LeaderboardView.Entry(e.rank(), e.userId(), e.username() == null ? "#" + e.userId() : e.username(),
                        e.value() == null ? 0d : e.value()))
                .toList();
        LeaderboardView.ViewerEntry viewer = dto.viewer() == null || dto.viewer().rank() == null ? null
                : new LeaderboardView.ViewerEntry(dto.viewer().rank(), dto.viewer().value() == null ? 0d : dto.viewer().value());
        return new LeaderboardView(dto.boardKey(), dto.label(), dto.unit(), dto.period(), StatisticsMapper.date(dto.periodStart()),
                StatisticsMapper.instant(dto.generatedAt()), dto.totalRanked() == null ? entries.size() : dto.totalRanked(),
                entries, viewer);
    }
}
