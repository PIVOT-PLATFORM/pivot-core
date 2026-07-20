package fr.pivot.collaboratif.whiteboard.canvas;

import fr.pivot.collaboratif.exception.InvalidCanvasElementException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates whiteboard template element payloads against a strict JSON schema whitelist,
 * one closed schema per {@link TemplateElementType} (EN08.x re-platform).
 *
 * <p>Applied to global template seed data (Flyway) at board-initialization time
 * ({@code WhiteboardTemplateService#initializeBoard}) — a validation failure indicates the
 * seed data itself has drifted from this schema, an internal invariant violation rather than a
 * caller input error, mirroring the retired {@code CanvasElementValidator}'s threat model
 * exactly (the caller only ever supplies {@code templateId}, never element content).
 *
 * <p>{@code CARD} content is restricted to {@code TEXT}/{@code LABEL}/{@code SHAPE} — the three
 * kinds every current template composes with. {@code SHAPE} content is not itself deep-validated
 * here: it is always re-normalized through {@link ShapeStyleSanitizer} at materialization time
 * (the same hardening every user-drawn SHAPE card content goes through), so this validator only
 * bounds its length.
 */
@Component
public class TemplateElementValidator {

    private static final String FIELD_POS_X = "posX";
    private static final String FIELD_POS_Y = "posY";
    private static final String FIELD_WIDTH = "width";
    private static final String FIELD_HEIGHT = "height";
    private static final String FIELD_COLOR = "color";
    private static final String FIELD_LAYER = "layer";

    private static final double MIN_COORDINATE = 0d;
    private static final double MAX_COORDINATE = 100_000d;
    private static final double MIN_DIMENSION = 1d;
    private static final double MAX_DIMENSION = 100_000d;
    private static final int MIN_LAYER = -1_000;
    private static final int MAX_LAYER = 1_000;
    private static final int MIN_CONNECTION_WIDTH = 1;
    private static final int MAX_CONNECTION_WIDTH = 20;

    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_CARD_CONTENT_LENGTH = 4000;
    private static final int MAX_LABEL_LENGTH = 200;
    private static final int MAX_KEY_LENGTH = 64;
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_EMOJI_LENGTH = 32;
    private static final int MAX_VALUE_LENGTH = 500;
    private static final int MAX_OPTIONS = 50;
    private static final int MAX_OPTION_LENGTH = 100;

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private static final Set<String> CARD_TYPES = Set.of("TEXT", "LABEL", "SHAPE");
    private static final Set<String> CONNECTION_SHAPES = Set.of("straight", "curved", "orthogonal");
    private static final Set<String> LINE_STYLES = Set.of("solid", "dashed", "dotted");
    private static final Set<String> CONNECTION_CAPS =
            Set.of("none", "arrow", "triangle", "circle", "diamond");
    private static final Set<String> FIELD_TYPES = Set.of("TEXT", "NUMBER", "DATE", "SELECT");

    private static final Set<String> FRAME_FIELDS =
            Set.of("title", FIELD_POS_X, FIELD_POS_Y, FIELD_WIDTH, FIELD_HEIGHT, FIELD_COLOR, FIELD_LAYER);
    private static final Set<String> CARD_FIELDS = Set.of(
            "type", "content", FIELD_POS_X, FIELD_POS_Y, FIELD_WIDTH, FIELD_HEIGHT,
            FIELD_COLOR, "groupKey", FIELD_LAYER);
    private static final Set<String> CONNECTION_FIELDS = Set.of(
            "fromKey", "toKey", "label", FIELD_COLOR, "shape", "lineStyle", "startCap", "endCap", FIELD_WIDTH);
    private static final Set<String> FIELD_FIELDS = Set.of("name", "emoji", "type", "options", "order");
    private static final Set<String> FIELD_VALUE_FIELDS = Set.of("cardKey", "fieldKey", "value");

    private final ObjectMapper objectMapper;

    /**
     * Creates the validator.
     *
     * @param objectMapper the Jackson mapper used to parse the JSON payload string
     */
    public TemplateElementValidator(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Validates a template element payload against the schema for its declared type.
     *
     * @param type        the element kind
     * @param payloadJson the JSON payload string to validate
     * @throws InvalidCanvasElementException if the payload is not valid JSON, is not a JSON
     *                                        object, contains a field outside the whitelist for
     *                                        its type, or fails a type-specific constraint
     */
    public void validate(final TemplateElementType type, final String payloadJson) {
        JsonNode node = parse(payloadJson, type);
        if (!node.isObject()) {
            throw new InvalidCanvasElementException(
                    "Template element payload must be a JSON object, type=" + type);
        }
        switch (type) {
            case FRAME -> validateFrame(node);
            case CARD -> validateCard(node);
            case CONNECTION -> validateConnection(node);
            case FIELD -> validateField(node);
            case FIELD_VALUE -> validateFieldValue(node);
        }
    }

    private JsonNode parse(final String payloadJson, final TemplateElementType type) {
        try {
            return objectMapper.readTree(payloadJson);
        } catch (RuntimeException e) {
            throw new InvalidCanvasElementException(
                    "Malformed JSON template element payload: type=" + type + ", " + e.getMessage());
        }
    }

    private void validateFrame(final JsonNode node) {
        requireText(node, "title", MAX_TITLE_LENGTH, true);
        requireNumberInRange(node, FIELD_POS_X, MIN_COORDINATE, MAX_COORDINATE);
        requireNumberInRange(node, FIELD_POS_Y, MIN_COORDINATE, MAX_COORDINATE);
        requireNumberInRange(node, FIELD_WIDTH, MIN_DIMENSION, MAX_DIMENSION);
        requireNumberInRange(node, FIELD_HEIGHT, MIN_DIMENSION, MAX_DIMENSION);
        optionalColor(node);
        optionalIntInRange(node, FIELD_LAYER, MIN_LAYER, MAX_LAYER);
        rejectUnknownFields(node, FRAME_FIELDS, TemplateElementType.FRAME);
    }

    private void validateCard(final JsonNode node) {
        String cardType = requireText(node, "type", 20, false);
        if (!CARD_TYPES.contains(cardType)) {
            throw new InvalidCanvasElementException("Unknown template CARD type: " + cardType);
        }
        String content = requireText(node, "content", MAX_CARD_CONTENT_LENGTH, true);
        if (!"SHAPE".equals(cardType) && (content.contains("<") || content.contains(">"))) {
            throw new InvalidCanvasElementException("Card content must not contain markup characters");
        }
        requireNumberInRange(node, FIELD_POS_X, MIN_COORDINATE, MAX_COORDINATE);
        requireNumberInRange(node, FIELD_POS_Y, MIN_COORDINATE, MAX_COORDINATE);
        requireNumberInRange(node, FIELD_WIDTH, MIN_DIMENSION, MAX_DIMENSION);
        requireNumberInRange(node, FIELD_HEIGHT, MIN_DIMENSION, MAX_DIMENSION);
        optionalColor(node);
        optionalText(node, "groupKey", MAX_KEY_LENGTH);
        optionalIntInRange(node, FIELD_LAYER, MIN_LAYER, MAX_LAYER);
        rejectUnknownFields(node, CARD_FIELDS, TemplateElementType.CARD);
    }

    private void validateConnection(final JsonNode node) {
        requireText(node, "fromKey", MAX_KEY_LENGTH, false);
        requireText(node, "toKey", MAX_KEY_LENGTH, false);
        optionalText(node, "label", MAX_LABEL_LENGTH);
        optionalColor(node);
        String shape = requireText(node, "shape", 20, false);
        if (!CONNECTION_SHAPES.contains(shape)) {
            throw new InvalidCanvasElementException("Unknown connection shape: " + shape);
        }
        String lineStyle = requireText(node, "lineStyle", 20, false);
        if (!LINE_STYLES.contains(lineStyle)) {
            throw new InvalidCanvasElementException("Unknown connection lineStyle: " + lineStyle);
        }
        String startCap = requireText(node, "startCap", 20, false);
        if (!CONNECTION_CAPS.contains(startCap)) {
            throw new InvalidCanvasElementException("Unknown connection startCap: " + startCap);
        }
        String endCap = requireText(node, "endCap", 20, false);
        if (!CONNECTION_CAPS.contains(endCap)) {
            throw new InvalidCanvasElementException("Unknown connection endCap: " + endCap);
        }
        optionalIntInRange(node, FIELD_WIDTH, MIN_CONNECTION_WIDTH, MAX_CONNECTION_WIDTH);
        rejectUnknownFields(node, CONNECTION_FIELDS, TemplateElementType.CONNECTION);
    }

    private void validateField(final JsonNode node) {
        requireText(node, "name", MAX_NAME_LENGTH, false);
        optionalText(node, "emoji", MAX_EMOJI_LENGTH);
        String fieldType = requireText(node, "type", 20, false);
        if (!FIELD_TYPES.contains(fieldType)) {
            throw new InvalidCanvasElementException("Unknown template FIELD type: " + fieldType);
        }
        if (node.has("options")) {
            validateOptions(node.get("options"));
        }
        requireNumberInRange(node, "order", 0, 1_000);
        rejectUnknownFields(node, FIELD_FIELDS, TemplateElementType.FIELD);
    }

    private void validateOptions(final JsonNode options) {
        if (!options.isArray()) {
            throw new InvalidCanvasElementException("FIELD options must be a JSON array");
        }
        if (options.size() > MAX_OPTIONS) {
            throw new InvalidCanvasElementException("FIELD options exceeds max size: " + options.size());
        }
        for (JsonNode option : options) {
            if (!option.isString() || option.asString().isBlank()
                    || option.asString().length() > MAX_OPTION_LENGTH) {
                throw new InvalidCanvasElementException("Invalid FIELD option value");
            }
        }
    }

    private void validateFieldValue(final JsonNode node) {
        requireText(node, "cardKey", MAX_KEY_LENGTH, false);
        requireText(node, "fieldKey", MAX_KEY_LENGTH, false);
        requireText(node, "value", MAX_VALUE_LENGTH, true);
        rejectUnknownFields(node, FIELD_VALUE_FIELDS, TemplateElementType.FIELD_VALUE);
    }

    private void optionalColor(final JsonNode node) {
        if (node.has(FIELD_COLOR)) {
            String color = requireText(node, FIELD_COLOR, 20, true);
            if (!HEX_COLOR.matcher(color).matches()) {
                throw new InvalidCanvasElementException("Invalid color format: " + color);
            }
        }
    }

    private void optionalText(final JsonNode node, final String field, final int maxLength) {
        if (node.has(field)) {
            requireText(node, field, maxLength, true);
        }
    }

    private void optionalIntInRange(final JsonNode node, final String field, final int min, final int max) {
        if (node.has(field)) {
            requireNumberInRange(node, field, min, max);
        }
    }

    private void requireNumberInRange(
            final JsonNode node, final String field, final double min, final double max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new InvalidCanvasElementException("Missing or non-numeric field: " + field);
        }
        double numericValue = value.asDouble();
        if (numericValue < min || numericValue > max) {
            throw new InvalidCanvasElementException("Field out of range: " + field);
        }
    }

    private String requireText(
            final JsonNode node, final String field, final int maxLength, final boolean allowBlank) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            throw new InvalidCanvasElementException("Missing or non-textual field: " + field);
        }
        String text = value.asString();
        if (!allowBlank && text.isBlank()) {
            throw new InvalidCanvasElementException("Field must not be blank: " + field);
        }
        if (text.length() > maxLength) {
            throw new InvalidCanvasElementException("Field exceeds max length: " + field);
        }
        return text;
    }

    private void rejectUnknownFields(
            final JsonNode node, final Set<String> allowedFields, final TemplateElementType type) {
        for (String name : node.propertyNames()) {
            if (!allowedFields.contains(name)) {
                throw new InvalidCanvasElementException(
                        "Unknown field '" + name + "' for template element type " + type);
            }
        }
    }
}
