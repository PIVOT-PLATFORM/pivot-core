package fr.pivot.collaboratif.session.poll.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * A single option's live tally, part of {@link PollUpdatedEvent}/the poll results read model
 * (US19.3.2).
 *
 * <p>{@code @JsonInclude(NON_NULL)}: when results are hidden, {@code count}/{@code percent} are
 * {@code null} here and therefore <strong>absent</strong> from the serialized payload — the
 * AC's security requirement is that hidden counts are never present in the response body at all,
 * not merely {@code null}/unused by the UI.
 *
 * @param optionId the option's id
 * @param label    the option's display label
 * @param count    number of votes for this option, or {@code null} (omitted) if results are hidden
 * @param percent  percentage of total votes for this option (0-100), or {@code null} (omitted) if
 *                 results are hidden
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PollOptionResult(UUID optionId, String label, Integer count, Double percent) {
}
