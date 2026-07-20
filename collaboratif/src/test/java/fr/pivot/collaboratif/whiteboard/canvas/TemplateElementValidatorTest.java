package fr.pivot.collaboratif.whiteboard.canvas;

import fr.pivot.collaboratif.exception.InvalidCanvasElementException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TemplateElementValidator} (EN08.x re-platform), covering every schema
 * branch per {@link TemplateElementType}: required/optional fields, bounded numeric ranges,
 * closed enums (card type, connection shape/lineStyle/caps, field type), the SHAPE-content
 * markup-check bypass, FIELD {@code options} validation, and the shared helpers (malformed
 * JSON, non-object payload, missing/non-typed/blank/oversized fields, unknown fields).
 */
class TemplateElementValidatorTest {

    private final TemplateElementValidator validator = new TemplateElementValidator(new ObjectMapper());

    // -------------------------------------------------------------------------
    // Top-level validate() — parse() and non-object payload
    // -------------------------------------------------------------------------

    @Test
    void validate_malformedJson_throwsInvalidCanvasElementException() {
        assertThatThrownBy(() -> validator.validate(TemplateElementType.FRAME, "{not valid json"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("Malformed JSON");
    }

    @Test
    void validate_nonObjectPayload_throwsInvalidCanvasElementException() {
        assertThatThrownBy(() -> validator.validate(TemplateElementType.FRAME, "[1,2,3]"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("must be a JSON object");
    }

    // -------------------------------------------------------------------------
    // FRAME
    // -------------------------------------------------------------------------

    @Test
    void validate_frame_minimalValidPayload_doesNotThrow() {
        assertThatCode(() -> validator.validate(TemplateElementType.FRAME,
                "{\"title\":\"Idées\",\"posX\":0,\"posY\":0,\"width\":300,\"height\":200}"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_frame_withOptionalColorAndLayer_doesNotThrow() {
        assertThatCode(() -> validator.validate(TemplateElementType.FRAME,
                "{\"title\":\"Idées\",\"posX\":0,\"posY\":0,\"width\":300,\"height\":200,"
                        + "\"color\":\"#94A3B8\",\"layer\":0}"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_frame_blankTitle_doesNotThrow() {
        assertThatCode(() -> validator.validate(TemplateElementType.FRAME,
                "{\"title\":\"\",\"posX\":0,\"posY\":0,\"width\":300,\"height\":200}"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_frame_missingTitle_throws() {
        assertThatThrownBy(() -> validator.validate(TemplateElementType.FRAME,
                "{\"posX\":0,\"posY\":0,\"width\":300,\"height\":200}"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("title");
    }

    @Test
    void validate_frame_negativePosX_throws() {
        assertThatThrownBy(() -> validator.validate(TemplateElementType.FRAME,
                "{\"title\":\"X\",\"posX\":-1,\"posY\":0,\"width\":300,\"height\":200}"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("posX");
    }

    @Test
    void validate_frame_widthBelowMinimum_throws() {
        assertThatThrownBy(() -> validator.validate(TemplateElementType.FRAME,
                "{\"title\":\"X\",\"posX\":0,\"posY\":0,\"width\":0,\"height\":200}"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("width");
    }

    @Test
    void validate_frame_invalidColor_throws() {
        assertThatThrownBy(() -> validator.validate(TemplateElementType.FRAME,
                "{\"title\":\"X\",\"posX\":0,\"posY\":0,\"width\":300,\"height\":200,\"color\":\"blue\"}"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("Invalid color format");
    }

    @Test
    void validate_frame_layerOutOfRange_throws() {
        assertThatThrownBy(() -> validator.validate(TemplateElementType.FRAME,
                "{\"title\":\"X\",\"posX\":0,\"posY\":0,\"width\":300,\"height\":200,\"layer\":5000}"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("layer");
    }

    @Test
    void validate_frame_unknownField_throws() {
        assertThatThrownBy(() -> validator.validate(TemplateElementType.FRAME,
                "{\"title\":\"X\",\"posX\":0,\"posY\":0,\"width\":300,\"height\":200,\"bogus\":1}"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("Unknown field");
    }

    // -------------------------------------------------------------------------
    // CARD
    // -------------------------------------------------------------------------

    @Test
    void validate_card_minimalValidTextPayload_doesNotThrow() {
        assertThatCode(() -> validator.validate(TemplateElementType.CARD,
                "{\"type\":\"TEXT\",\"content\":\"Idée 1\",\"posX\":0,\"posY\":0,"
                        + "\"width\":180,\"height\":120}"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_card_withAllOptionalFields_doesNotThrow() {
        assertThatCode(() -> validator.validate(TemplateElementType.CARD,
                "{\"type\":\"LABEL\",\"content\":\"Titre\",\"posX\":0,\"posY\":0,"
                        + "\"width\":180,\"height\":120,\"color\":\"#FEF08A\","
                        + "\"groupKey\":\"g1\",\"layer\":2}"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_card_unknownType_throws() {
        assertThatThrownBy(() -> validator.validate(TemplateElementType.CARD,
                "{\"type\":\"IMAGE\",\"content\":\"x\",\"posX\":0,\"posY\":0,\"width\":10,\"height\":10}"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("Unknown template CARD type");
    }

    @Test
    void validate_card_textContentWithMarkup_throws() {
        assertThatThrownBy(() -> validator.validate(TemplateElementType.CARD,
                "{\"type\":\"TEXT\",\"content\":\"<script>\",\"posX\":0,\"posY\":0,"
                        + "\"width\":10,\"height\":10}"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("markup");
    }

    @Test
    void validate_card_shapeContentContainingAngleBrackets_bypassesMarkupCheck() {
        assertThatCode(() -> validator.validate(TemplateElementType.CARD,
                "{\"type\":\"SHAPE\",\"content\":\"rect|<stroke>|#EEF2FF|1|0|tlbr\",\"posX\":0,"
                        + "\"posY\":0,\"width\":100,\"height\":100}"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_card_contentTooLong_throws() {
        String tooLong = "a".repeat(4001);
        assertThatThrownBy(() -> validator.validate(TemplateElementType.CARD,
                "{\"type\":\"TEXT\",\"content\":\"" + tooLong + "\",\"posX\":0,\"posY\":0,"
                        + "\"width\":10,\"height\":10}"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("exceeds max length");
    }

    @Test
    void validate_card_unknownField_throws() {
        assertThatThrownBy(() -> validator.validate(TemplateElementType.CARD,
                "{\"type\":\"TEXT\",\"content\":\"x\",\"posX\":0,\"posY\":0,\"width\":10,"
                        + "\"height\":10,\"fontSize\":16}"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("Unknown field");
    }

    // -------------------------------------------------------------------------
    // CONNECTION
    // -------------------------------------------------------------------------

    @Test
    void validate_connection_minimalValidPayload_doesNotThrow() {
        assertThatCode(() -> validator.validate(TemplateElementType.CONNECTION,
                "{\"fromKey\":\"a\",\"toKey\":\"b\",\"shape\":\"curved\",\"lineStyle\":\"solid\","
                        + "\"startCap\":\"none\",\"endCap\":\"arrow\"}"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_connection_withLabelColorAndWidth_doesNotThrow() {
        assertThatCode(() -> validator.validate(TemplateElementType.CONNECTION,
                "{\"fromKey\":\"a\",\"toKey\":\"b\",\"label\":\"relie à\",\"color\":\"#000000\","
                        + "\"shape\":\"straight\",\"lineStyle\":\"dashed\",\"startCap\":\"circle\","
                        + "\"endCap\":\"diamond\",\"width\":4}"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_connection_unknownShape_throws() {
        assertThatThrownBy(() -> validator.validate(TemplateElementType.CONNECTION,
                "{\"fromKey\":\"a\",\"toKey\":\"b\",\"shape\":\"zigzag\",\"lineStyle\":\"solid\","
                        + "\"startCap\":\"none\",\"endCap\":\"none\"}"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("connection shape");
    }

    @Test
    void validate_connection_unknownLineStyle_throws() {
        assertThatThrownBy(() -> validator.validate(TemplateElementType.CONNECTION,
                "{\"fromKey\":\"a\",\"toKey\":\"b\",\"shape\":\"curved\",\"lineStyle\":\"double\","
                        + "\"startCap\":\"none\",\"endCap\":\"none\"}"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("connection lineStyle");
    }

    @Test
    void validate_connection_unknownStartCap_throws() {
        assertThatThrownBy(() -> validator.validate(TemplateElementType.CONNECTION,
                "{\"fromKey\":\"a\",\"toKey\":\"b\",\"shape\":\"curved\",\"lineStyle\":\"solid\","
                        + "\"startCap\":\"star\",\"endCap\":\"none\"}"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("connection startCap");
    }

    @Test
    void validate_connection_unknownEndCap_throws() {
        assertThatThrownBy(() -> validator.validate(TemplateElementType.CONNECTION,
                "{\"fromKey\":\"a\",\"toKey\":\"b\",\"shape\":\"curved\",\"lineStyle\":\"solid\","
                        + "\"startCap\":\"none\",\"endCap\":\"star\"}"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("connection endCap");
    }

    @Test
    void validate_connection_widthOutOfRange_throws() {
        assertThatThrownBy(() -> validator.validate(TemplateElementType.CONNECTION,
                "{\"fromKey\":\"a\",\"toKey\":\"b\",\"shape\":\"curved\",\"lineStyle\":\"solid\","
                        + "\"startCap\":\"none\",\"endCap\":\"none\",\"width\":21}"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("width");
    }

    @Test
    void validate_connection_unknownField_throws() {
        assertThatThrownBy(() -> validator.validate(TemplateElementType.CONNECTION,
                "{\"fromKey\":\"a\",\"toKey\":\"b\",\"shape\":\"curved\",\"lineStyle\":\"solid\","
                        + "\"startCap\":\"none\",\"endCap\":\"none\",\"arrow\":\"end\"}"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("Unknown field");
    }

    // -------------------------------------------------------------------------
    // FIELD
    // -------------------------------------------------------------------------

    @Test
    void validate_field_minimalValidPayload_doesNotThrow() {
        assertThatCode(() -> validator.validate(TemplateElementType.FIELD,
                "{\"name\":\"Probabilité\",\"type\":\"NUMBER\",\"order\":0}"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_field_withEmoji_doesNotThrow() {
        assertThatCode(() -> validator.validate(TemplateElementType.FIELD,
                "{\"name\":\"Échéance\",\"emoji\":\"📅\",\"type\":\"DATE\",\"order\":1}"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_field_selectWithValidOptions_doesNotThrow() {
        assertThatCode(() -> validator.validate(TemplateElementType.FIELD,
                "{\"name\":\"Statut\",\"type\":\"SELECT\",\"options\":[\"À faire\",\"En cours\"],"
                        + "\"order\":0}"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_field_unknownType_throws() {
        assertThatThrownBy(() -> validator.validate(TemplateElementType.FIELD,
                "{\"name\":\"X\",\"type\":\"BOOLEAN\",\"order\":0}"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("Unknown template FIELD type");
    }

    @Test
    void validate_field_optionsNotArray_throws() {
        assertThatThrownBy(() -> validator.validate(TemplateElementType.FIELD,
                "{\"name\":\"X\",\"type\":\"SELECT\",\"options\":\"a,b\",\"order\":0}"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("must be a JSON array");
    }

    @Test
    void validate_field_tooManyOptions_throws() {
        StringBuilder options = new StringBuilder("[");
        for (int i = 0; i < 51; i++) {
            if (i > 0) {
                options.append(',');
            }
            options.append("\"opt").append(i).append('"');
        }
        options.append(']');
        assertThatThrownBy(() -> validator.validate(TemplateElementType.FIELD,
                "{\"name\":\"X\",\"type\":\"SELECT\",\"options\":" + options + ",\"order\":0}"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("exceeds max size");
    }

    @Test
    void validate_field_blankOption_throws() {
        assertThatThrownBy(() -> validator.validate(TemplateElementType.FIELD,
                "{\"name\":\"X\",\"type\":\"SELECT\",\"options\":[\"\"],\"order\":0}"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("Invalid FIELD option value");
    }

    @Test
    void validate_field_nonStringOption_throws() {
        assertThatThrownBy(() -> validator.validate(TemplateElementType.FIELD,
                "{\"name\":\"X\",\"type\":\"SELECT\",\"options\":[1],\"order\":0}"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("Invalid FIELD option value");
    }

    @Test
    void validate_field_orderOutOfRange_throws() {
        assertThatThrownBy(() -> validator.validate(TemplateElementType.FIELD,
                "{\"name\":\"X\",\"type\":\"TEXT\",\"order\":-1}"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("order");
    }

    @Test
    void validate_field_unknownField_throws() {
        assertThatThrownBy(() -> validator.validate(TemplateElementType.FIELD,
                "{\"name\":\"X\",\"type\":\"TEXT\",\"order\":0,\"required\":true}"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("Unknown field");
    }

    // -------------------------------------------------------------------------
    // FIELD_VALUE
    // -------------------------------------------------------------------------

    @Test
    void validate_fieldValue_validPayload_doesNotThrow() {
        assertThatCode(() -> validator.validate(TemplateElementType.FIELD_VALUE,
                "{\"cardKey\":\"riskCard\",\"fieldKey\":\"probField\",\"value\":\"3\"}"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_fieldValue_blankValue_doesNotThrow() {
        assertThatCode(() -> validator.validate(TemplateElementType.FIELD_VALUE,
                "{\"cardKey\":\"a\",\"fieldKey\":\"b\",\"value\":\"\"}"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_fieldValue_missingCardKey_throws() {
        assertThatThrownBy(() -> validator.validate(TemplateElementType.FIELD_VALUE,
                "{\"fieldKey\":\"b\",\"value\":\"x\"}"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("cardKey");
    }

    @Test
    void validate_fieldValue_unknownField_throws() {
        assertThatThrownBy(() -> validator.validate(TemplateElementType.FIELD_VALUE,
                "{\"cardKey\":\"a\",\"fieldKey\":\"b\",\"value\":\"x\",\"extra\":1}"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("Unknown field");
    }

    // -------------------------------------------------------------------------
    // Shared helper edge cases (via FRAME, which requires the most primitive-typed fields)
    // -------------------------------------------------------------------------

    @Test
    void validate_frame_nonNumericPosX_throws() {
        assertThatThrownBy(() -> validator.validate(TemplateElementType.FRAME,
                "{\"title\":\"X\",\"posX\":\"zero\",\"posY\":0,\"width\":10,\"height\":10}"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("Missing or non-numeric field: posX");
    }

    @Test
    void validate_frame_nonTextualTitle_throws() {
        assertThatThrownBy(() -> validator.validate(TemplateElementType.FRAME,
                "{\"title\":42,\"posX\":0,\"posY\":0,\"width\":10,\"height\":10}"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("Missing or non-textual field: title");
    }

    @Test
    void validate_card_blankRequiredType_throwsMustNotBeBlank() {
        assertThatThrownBy(() -> validator.validate(TemplateElementType.CARD,
                "{\"type\":\"\",\"content\":\"x\",\"posX\":0,\"posY\":0,\"width\":10,\"height\":10}"))
                .isInstanceOf(InvalidCanvasElementException.class)
                .hasMessageContaining("must not be blank");
    }
}
