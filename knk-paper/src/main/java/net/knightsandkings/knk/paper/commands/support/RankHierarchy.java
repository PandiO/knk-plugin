package net.knightsandkings.knk.paper.commands.support;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.users.GroupMembershipSummary;
import net.knightsandkings.knk.core.ports.api.UsersQueryApi;

/**
 * "Can this actor act on that target?" for the rank/permission/moderation commands
 * (/knk user group|perm, /freeze, /knk tp - developer-confirmed requirement: staff can only
 * manage players ranked below themselves in the PermissionGroup weight hierarchy). Compares
 * each user's highest active group weight; a user with no memberships is weight 0 (bare
 * Default), matching PermissionGroup's own seeded convention.
 */
public final class RankHierarchy {
    private final UsersQueryApi usersQueryApi;

    public RankHierarchy(UsersQueryApi usersQueryApi) {
        this.usersQueryApi = usersQueryApi;
    }

    /**
     * @return a future resolving true iff actorUserId's highest active group weight is strictly
     * greater than targetUserId's. Acting on yourself (actorUserId == targetUserId) always
     * resolves false - equal weight never outranks, including your own.
     */
    public CompletableFuture<Boolean> actorOutranks(int actorUserId, int targetUserId) {
        if (actorUserId == targetUserId) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Integer> actorWeight = usersQueryApi.getGroupMemberships(actorUserId).thenApply(RankHierarchy::maxWeight);
        CompletableFuture<Integer> targetWeight = usersQueryApi.getGroupMemberships(targetUserId).thenApply(RankHierarchy::maxWeight);
        return actorWeight.thenCombine(targetWeight, (a, t) -> a > t);
    }

    private static int maxWeight(List<GroupMembershipSummary> memberships) {
        return memberships.stream()
            .filter(GroupMembershipSummary::isActive)
            .mapToInt(GroupMembershipSummary::weight)
            .max()
            .orElse(0);
    }
}
