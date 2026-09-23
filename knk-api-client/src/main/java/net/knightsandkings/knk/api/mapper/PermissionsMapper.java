package net.knightsandkings.knk.api.mapper;

import java.util.List;
import java.util.stream.Collectors;

import net.knightsandkings.knk.api.dto.EffectivePermissionEntryDto;
import net.knightsandkings.knk.api.dto.PermissionCheckResponseDto;
import net.knightsandkings.knk.api.dto.PermissionEffectiveResponseDto;
import net.knightsandkings.knk.core.domain.permissions.EffectivePermission;
import net.knightsandkings.knk.core.domain.permissions.EffectivePermissionSet;
import net.knightsandkings.knk.core.domain.permissions.PermissionCheckResult;
import net.knightsandkings.knk.core.domain.permissions.PermissionHolder;
import net.knightsandkings.knk.core.domain.permissions.PermissionResolution;

public class PermissionsMapper {

    public static PermissionCheckResult mapCheckResult(PermissionCheckResponseDto dto) {
        PermissionHolder source = dto.sourceHolderId() != null
            ? new PermissionHolder(dto.sourceHolderId(), dto.sourceHolderType(), null)
            : null;

        return new PermissionCheckResult(
            dto.node(),
            parseResolution(dto.result()),
            source,
            dto.matchedNode()
        );
    }

    public static EffectivePermissionSet mapEffectiveSet(PermissionEffectiveResponseDto dto) {
        List<EffectivePermission> permissions = dto.permissions() == null
            ? List.of()
            : dto.permissions().stream().map(PermissionsMapper::mapEffectiveEntry).collect(Collectors.toList());

        return new EffectivePermissionSet(dto.userId(), permissions);
    }

    private static EffectivePermission mapEffectiveEntry(EffectivePermissionEntryDto dto) {
        PermissionHolder source = new PermissionHolder(dto.sourceHolderId(), dto.sourceHolderType(), dto.sourceHolderName());
        return new EffectivePermission(dto.node(), dto.value(), source);
    }

    private static PermissionResolution parseResolution(String wireValue) {
        if (wireValue == null) return PermissionResolution.UNDECLARED;
        try {
            return PermissionResolution.valueOf(wireValue.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return PermissionResolution.UNDECLARED;
        }
    }

    private PermissionsMapper() {
    }
}
