package fr.pivot.collaboratif.meeting.report;

import fr.pivot.collaboratif.meeting.report.MeetingReportDto.ActionReportDto;
import fr.pivot.collaboratif.meeting.report.MeetingReportDto.AgendaItemReportDto;
import fr.pivot.collaboratif.meeting.report.MeetingReportDto.DecisionReportDto;
import fr.pivot.collaboratif.meeting.report.MeetingReportDto.ParticipantReportDto;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MeetingMarkdownRenderer} (US12.3.1 AC nominal) — a pure, stateless
 * transformation, so every assertion here is a plain string-shape check, no Spring context
 * needed.
 */
class MeetingMarkdownRendererTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-08-01T10:30:00Z");

    private final MeetingMarkdownRenderer renderer = new MeetingMarkdownRenderer();

    @Test
    void render_includesTitleAndAllFourSectionHeadingsInOrder() {
        MeetingReportDto report = fullReport(false);

        String markdown = renderer.render(report);

        assertThat(markdown).startsWith("# Sprint Review\n");
        int participantsIdx = markdown.indexOf("## Participants");
        int agendaIdx = markdown.indexOf("## Agenda");
        int decisionsIdx = markdown.indexOf("## Décisions");
        int actionsIdx = markdown.indexOf("## Actions");
        assertThat(participantsIdx).isPositive();
        assertThat(agendaIdx).isGreaterThan(participantsIdx);
        assertThat(decisionsIdx).isGreaterThan(agendaIdx);
        assertThat(actionsIdx).isGreaterThan(decisionsIdx);
    }

    @Test
    void render_frozenReport_doesNotMentionBrouillon() {
        String markdown = renderer.render(fullReport(false));

        assertThat(markdown).doesNotContain("brouillon");
    }

    @Test
    void render_draftReport_flagsStatusAsBrouillon() {
        String markdown = renderer.render(fullReport(true));

        assertThat(markdown).contains("(brouillon)");
    }

    @Test
    void render_participantsSection_marksOrganizerAndListsMembers() {
        String markdown = renderer.render(fullReport(false));

        assertThat(markdown).contains("Utilisateur #1 (organisateur)");
        assertThat(markdown).contains("Utilisateur #2\n");
        assertThat(markdown).doesNotContain("Utilisateur #2 (organisateur)");
    }

    @Test
    void render_agendaSection_showsPlannedActualAndOvertimeColumns() {
        String markdown = renderer.render(fullReport(false));

        assertThat(markdown).contains("| Point A | 5 min | 6 min 0 s | Oui |");
        assertThat(markdown).contains("| Point B | 10 min | — | Non |");
    }

    @Test
    void render_decisionsSection_listsEachDecision() {
        String markdown = renderer.render(fullReport(false));

        assertThat(markdown).contains("- Adopter le nouveau format de daily");
    }

    @Test
    void render_actionsSection_showsOwnerAndDueDateOrPlaceholder() {
        String markdown = renderer.render(fullReport(false));

        assertThat(markdown).contains("| Follow up with legal | Utilisateur #7 | 2026-08-10 |");
        assertThat(markdown).contains("| Unassigned follow-up | — | — |");
    }

    @Test
    void render_emptyCollections_usePlaceholderText() {
        UUID meetingId = UUID.randomUUID();
        MeetingReportDto empty = new MeetingReportDto(
                meetingId, "Empty Meeting", "ENDED", false, List.of(), List.of(), List.of(), List.of(), null,
                GENERATED_AT);

        String markdown = renderer.render(empty);

        assertThat(markdown).contains("_Aucun participant._");
        assertThat(markdown).contains("_Aucun point à l'ordre du jour._");
        assertThat(markdown).contains("_Aucune décision enregistrée._");
        assertThat(markdown).contains("_Aucune action capturée._");
    }

    private MeetingReportDto fullReport(final boolean draft) {
        UUID meetingId = UUID.randomUUID();
        List<ParticipantReportDto> participants = List.of(
                new ParticipantReportDto(1L, true),
                new ParticipantReportDto(2L, false));
        List<AgendaItemReportDto> agendaItems = List.of(
                new AgendaItemReportDto(UUID.randomUUID(), "Point A", 5, 360, true),
                new AgendaItemReportDto(UUID.randomUUID(), "Point B", 10, null, false));
        List<DecisionReportDto> decisions = List.of(
                new DecisionReportDto(UUID.randomUUID(), "Adopter le nouveau format de daily", GENERATED_AT));
        List<ActionReportDto> actions = List.of(
                new ActionReportDto(UUID.randomUUID(), "Follow up with legal", 7L, LocalDate.of(2026, 8, 10)),
                new ActionReportDto(UUID.randomUUID(), "Unassigned follow-up", null, null));
        return new MeetingReportDto(
                meetingId, "Sprint Review", "ENDED", draft, participants, agendaItems, decisions, actions, 720,
                GENERATED_AT);
    }
}
