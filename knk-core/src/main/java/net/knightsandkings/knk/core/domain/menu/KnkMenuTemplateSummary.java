package net.knightsandkings.knk.core.domain.menu;

/**
 * Lightweight listing shape for a MenuTemplate (mirrors knk-web-api's
 * MenuTemplateListDto) - no nested sections/items, for browsing templates
 * without pulling the full composite tree.
 */
public record KnkMenuTemplateSummary(
        Integer id,
        String key,
        String name,
        String description,
        Integer sectionCount
) {}
