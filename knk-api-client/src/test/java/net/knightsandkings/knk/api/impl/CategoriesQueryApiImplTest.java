package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.dto.CategoryListItemDto;
import net.knightsandkings.knk.core.domain.item.KnkItemCategory;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Menu follow-up 2026-09-26: GET /Categories → the catalogue's category filter values. */
class CategoriesQueryApiImplTest {

    @Test
    void mapsTheApiListAndDropsNamelessEntries() throws Exception {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        List<CategoryListItemDto> dtos = mapper.readValue("""
                [{"id":1,"name":"Weapons","parentCategoryId":null,"itemtypeCount":3},
                 {"id":2,"name":" Swords ","parentCategoryId":1},
                 {"id":3,"name":"  "},
                 {"id":null,"name":"Ghost"}]""", new TypeReference<>() { });

        assertEquals(List.of(new KnkItemCategory(1, "Weapons", null), new KnkItemCategory(2, "Swords", 1)),
                CategoriesQueryApiImpl.toCore(dtos));
        assertEquals(List.of(), CategoriesQueryApiImpl.toCore(null));
        assertEquals(List.of(), CategoriesQueryApiImpl.toCore(Arrays.asList((CategoryListItemDto) null)));
    }
}
