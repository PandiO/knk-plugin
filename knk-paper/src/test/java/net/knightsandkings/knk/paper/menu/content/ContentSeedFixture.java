package net.knightsandkings.knk.paper.menu.content;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import net.knightsandkings.knk.api.dto.MenuTemplateDto;
import net.knightsandkings.knk.api.mapper.MenuTemplateMapper;
import net.knightsandkings.knk.core.domain.menu.KnkMenuTemplate;
import net.knightsandkings.knk.core.menu.MenuDefinitionValidator;
import net.knightsandkings.knk.core.menu.MenuTemplateAssembler;
import net.knightsandkings.knk.core.menu.RuntimeMenu;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The content-port seed templates exactly as knk-web-api's API serves them:
 * {@code src/test/resources/menu/content-seeds.json} is written by knk-web-api's
 * {@code MenuTemplateContentSeedTests.ContentSeeds_ExportAsApiJson} (regenerate it whenever a
 * content seed in {@code MenuTemplateSeed.Content.cs} changes). Loading it through the real
 * api-client DTOs + mapper and assembling/validating it against the registered features is the
 * seed ↔ plugin contract test of CONTENT_PORT_PLAN.md §2 - stronger than copying expressions by
 * hand, because nothing in the template can drift out of the check.
 */
final class ContentSeedFixture {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ContentSeedFixture() {
    }

    static Map<String, KnkMenuTemplate> templates() {
        try (InputStream in = ContentSeedFixture.class.getResourceAsStream("/menu/content-seeds.json")) {
            if (in == null) {
                throw new IllegalStateException("menu/content-seeds.json test resource is missing");
            }
            List<MenuTemplateDto> dtos = MAPPER.readValue(in, new TypeReference<List<MenuTemplateDto>>() { });
            Map<String, KnkMenuTemplate> byKey = new LinkedHashMap<>();
            for (MenuTemplateDto dto : dtos) {
                byKey.put(dto.key(), MenuTemplateMapper.toCore(dto));
            }
            return byKey;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static RuntimeMenu assemble(String key) {
        KnkMenuTemplate template = templates().get(key);
        if (template == null) {
            throw new IllegalArgumentException("No content seed with key " + key);
        }
        return MenuTemplateAssembler.assemble(template);
    }

    /** Runs every startup validation step {@code MenuDefinitionValidationRunner} runs; throws on failure. */
    static void validate(RuntimeMenu menu, MenuFeatureRegistries registries) {
        MenuDefinitionValidator.validate(menu, registries.variables().declaredTypes(), registries.contentSources().rowTypes());
        MenuDefinitionValidator.validateActionsAndConditions(menu, registries.actions().registeredIds(),
                registries.conditions().registeredIds());
        MenuDefinitionValidator.validateContentSources(menu, registries.contentSources().registeredIds());
    }
}
