package net.knightsandkings.knk.api.mapper;

import net.knightsandkings.knk.api.dto.PagedResultDto;
import net.knightsandkings.knk.api.dto.UserDto;
import net.knightsandkings.knk.api.dto.UserListDto;
import net.knightsandkings.knk.api.dto.UserSummaryDto;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.domain.users.UserDetail;
import net.knightsandkings.knk.core.domain.users.UserListItem;
import net.knightsandkings.knk.core.domain.users.UserSummary;

public class UsersMapper {
    public static UserSummary mapUserSummary(UserSummaryDto dto) {
        return new UserSummary(
            dto.id(),
            dto.username(),
            dto.uuid(),
            dto.email(),
            dto.coins(),
            dto.gems(),
            dto.experiencePoints(),
            dto.isFullAccount(),
            false,
            GatePassThroughMethod.fromWireValue(dto.gatePassThroughMethodDefault()),
            ActiveMode.fromWireValue(dto.activeMode()),
            dto.titleBracketId(),
            dto.titleName(),
            dto.prestigeExperience(),
            dto.premiumTierGroupId(),
            dto.premiumTierName(),
            dto.premiumTierExpiresAt()
        );
    }

    public static UserSummaryDto mapUserSummary(UserSummary domain) {
        return new UserSummaryDto(
            domain.id(),
            domain.username(),
            domain.uuid(),
            domain.email(),
            domain.coins(),
            domain.gems(),
            domain.experiencePoints(),
            domain.isFullAccount(),
            domain.gatePassThroughMethodDefault().toWireValue(),
            domain.activeMode().toWireValue(),
            domain.titleBracketId(),
            domain.titleName(),
            domain.prestigeExperience(),
            domain.premiumTierGroupId(),
            domain.premiumTierName(),
            domain.premiumTierExpiresAt()
        );
    }

    public static UserDetail mapUserDetail(UserDto dto) {
        return new UserDetail(
            dto.id(),
            dto.username(),
            dto.uuid(),
            dto.email(),
            dto.coins(),
            dto.createdAt()
        );
    }

    public static UserDto mapUserDetail(UserDetail domain) {
        return new UserDto(
            domain.id(),
            domain.username(),
            domain.uuid(),
            domain.email(),
            domain.coins(),
            domain.createdAt()
        );
    }

    public static UserListItem mapUserListItem(UserListDto dto) {
        return new UserListItem(
            dto.id(),
            dto.username(),
            dto.uuid(),
            dto.email(),
            dto.coins()
        );
    }

    public static Page<UserListItem> mapUserListItemPage(PagedResultDto<UserListDto> dtoPage) {
        return new Page<>(
            dtoPage.items().stream().map(UsersMapper::mapUserListItem).toList(),
            dtoPage.totalCount(),
            dtoPage.pageNumber(),
            dtoPage.pageSize()
        );
    }
}
