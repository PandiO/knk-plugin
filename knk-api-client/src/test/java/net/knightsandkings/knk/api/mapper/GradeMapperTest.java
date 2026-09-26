package net.knightsandkings.knk.api.mapper;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.dto.GradeDto;
import net.knightsandkings.knk.core.domain.item.KnkGrade;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Linear KNG-6: DropChance and EnchantLevelCapDivisor over the wire. */
class GradeMapperTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void mapsTheWebApiJson() throws Exception {
        GradeDto dto = mapper.readValue(
                "{\"id\":3,\"name\":\"Rare\",\"stars\":3,\"dropChance\":40.0,\"enchantLevelCapDivisor\":3}", GradeDto.class);

        KnkGrade grade = GradeMapper.toCore(dto);

        assertEquals(new KnkGrade(3, "Rare", 3, 40.0, 3), grade);
        assertEquals(grade, ItemBlueprintMapper.toCore(dto));
    }

    @Test
    void uncappedGradeHasNullDivisor() throws Exception {
        KnkGrade grade = GradeMapper.toCore(mapper.readValue(
                "{\"id\":10,\"name\":\"Divine\",\"stars\":10,\"dropChance\":0.05,\"enchantLevelCapDivisor\":null}", GradeDto.class));

        assertEquals(0.05, grade.dropChance());
        assertNull(grade.enchantLevelCapDivisor());
        assertNull(grade.capEnchantLevel(5));
    }

    @Test
    void olderApiWithoutTheFieldsStillMaps() throws Exception {
        KnkGrade grade = GradeMapper.toCore(mapper.readValue("{\"id\":1,\"name\":\"Common\",\"stars\":1}", GradeDto.class));

        assertEquals(new KnkGrade(1, "Common", 1), grade);
    }
}
