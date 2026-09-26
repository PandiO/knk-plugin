package net.knightsandkings.knk.api.mapper;

import net.knightsandkings.knk.api.dto.PagedResultDto;
import net.knightsandkings.knk.api.dto.SalaryPayoutResultDto;
import net.knightsandkings.knk.api.dto.UserDto;
import net.knightsandkings.knk.api.dto.UserListDto;
import net.knightsandkings.knk.api.dto.UserSummaryDto;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.domain.users.SalaryPayoutResult;
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
            dto.premiumTierExpiresAt(),
            dto.isFrozen(),
            dto.frozenReason(),
            dto.gender(),
            dto.chatPrimaryColor(),
            dto.chatSecondaryColor(),
            dto.nameColor()
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
            domain.premiumTierExpiresAt(),
            domain.isFrozen(),
            domain.frozenReason(),
            domain.gender(),
            domain.chatPrimaryColor(),
            domain.chatSecondaryColor(),
            domain.nameColor()
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

    public static SalaryPayoutResult mapSalaryPayoutResult(SalaryPayoutResultDto dto) {
        return new SalaryPayoutResult(
            dto.paid(),
            dto.amountPaid(),
            dto.hoursCovered(),
            dto.globalMultiplier(),
            dto.personalMultiplier(),
            dto.rankMultiplier(),
            dto.newCoinsBalance(),
            dto.lastSalaryPayoutAt(),
            dto.nextEligibleAt(),
            dto.titleBracketId(),
            dto.titleSalary(),
            dto.paidHours(),
            dto.baseAmount(),
            mapRewardMultipliers(dto.multipliers())
        );
    }

    /** Null-safe: an API that predates KNG-16's reward breakdown sends no list. */
    public static java.util.List<net.knightsandkings.knk.core.domain.users.RewardMultiplier> mapRewardMultipliers(
        java.util.List<net.knightsandkings.knk.api.dto.RewardMultiplierDto> dtos
    ) {
        if (dtos == null) {
            return java.util.List.of();
        }
        return dtos.stream()
            .map(m -> new net.knightsandkings.knk.core.domain.users.RewardMultiplier(
                m.source(), m.value(), m.permissionGroupId(), m.name(), m.isPremiumTier(),
                m.chatPrimaryColor(), m.chatSecondaryColor()))
            .toList();
    }

    public static Page<UserListItem> mapUserListItemPage(PagedResultDto<UserListDto> dtoPage) {
        return new Page<>(
            dtoPage.items().stream().map(UsersMapper::mapUserListItem).toList(),
            dtoPage.totalCount(),
            dtoPage.pageNumber(),
            dtoPage.pageSize()
        );
    }

    public static net.knightsandkings.knk.core.domain.users.BalanceAdjustmentResult mapBalanceAdjustmentResult(
        net.knightsandkings.knk.api.dto.BalanceAdjustmentResultDto dto
    ) {
        return new net.knightsandkings.knk.core.domain.users.BalanceAdjustmentResult(
            dto.newCoins(), dto.newGems(), dto.newExperiencePoints(), mapTitleChange(dto.titleChange())
        );
    }

    public static net.knightsandkings.knk.core.domain.users.PlayerNotification mapPlayerNotification(
        net.knightsandkings.knk.api.dto.PlayerNotificationDto dto
    ) {
        return new net.knightsandkings.knk.core.domain.users.PlayerNotification(
            dto.id(), dto.userId(), dto.uuid(), dto.username(), dto.type(), mapTitleChange(dto.titleChange())
        );
    }

    public static net.knightsandkings.knk.core.domain.users.TitleChangeResult mapTitleChange(
        net.knightsandkings.knk.api.dto.TitleChangeResultDto tc
    ) {
        if (tc == null) {
            return null;
        }
        return new net.knightsandkings.knk.core.domain.users.TitleChangeResult(
            tc.direction(),
            tc.fromTitleBracketId(),
            tc.fromTitleName(),
            tc.toTitleBracketId(),
            tc.toTitleName(),
            tc.crossedTitles() == null ? java.util.List.of() : tc.crossedTitles().stream()
                .map(c -> new net.knightsandkings.knk.core.domain.users.TitleCrossing(c.titleBracketId(), c.titleName()))
                .toList(),
            tc.coinBonusGranted(),
            tc.gemBonusGranted(),
            tc.expBonusGranted(),
            tc.coinBonusBase(),
            tc.gemBonusBase(),
            tc.expBonusBase(),
            mapRewardMultipliers(tc.coinBonusMultipliers()),
            mapRewardMultipliers(tc.gemBonusMultipliers()),
            mapRewardMultipliers(tc.expBonusMultipliers())
        );
    }
}
