package fr.pivot.collaboratif.meeting.report;

import fr.pivot.collaboratif.meeting.report.MeetingReportDto.ActionReportDto;
import fr.pivot.collaboratif.meeting.report.MeetingReportDto.AgendaItemReportDto;
import fr.pivot.collaboratif.meeting.report.MeetingReportDto.DecisionReportDto;
import fr.pivot.collaboratif.meeting.report.MeetingReportDto.ParticipantReportDto;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;

/**
 * Pure {@link MeetingReportDto} {@code ->} Markdown transformation (US12.3.1 AC nominal,
 * {@code GET .../report/export?format=markdown}) — no state, no I/O, every method deterministic
 * given its input, so it is trivially unit-testable without a Spring context.
 *
 * <p>Section order and headings are fixed by the AC: {@code ## Participants}, {@code ## Agenda},
 * {@code ## Décisions}, {@code ## Actions}, each preceded by a {@code # <title>} document title
 * and a short metadata line.
 */
@Component
public class MeetingMarkdownRenderer {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Locale.FRENCH).withZone(ZoneOffset.UTC);

    /**
     * Renders a full compte-rendu as Markdown.
     *
     * @param report the report to render — draft or frozen, either shape is valid input
     * @return the Markdown document
     */
    public String render(final MeetingReportDto report) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(report.title()).append('\n').append('\n');
        sb.append("**Statut :** ").append(report.status());
        if (report.draft()) {
            sb.append(" (brouillon)");
        }
        sb.append('\n');
        if (report.actualDurationSeconds() != null) {
            sb.append("**Durée réelle :** ").append(formatDuration(report.actualDurationSeconds())).append('\n');
        }
        sb.append("**Généré le :** ").append(TIMESTAMP_FORMAT.format(report.generatedAt())).append('\n');
        sb.append('\n');

        sb.append(renderParticipants(report.participants()));
        sb.append(renderAgenda(report.agendaItems()));
        sb.append(renderDecisions(report.decisions()));
        sb.append(renderActions(report.actions()));

        return sb.toString();
    }

    private String renderParticipants(final List<ParticipantReportDto> participants) {
        StringBuilder sb = new StringBuilder("## Participants\n\n");
        if (participants.isEmpty()) {
            sb.append("_Aucun participant._\n\n");
            return sb.toString();
        }
        for (ParticipantReportDto p : participants) {
            sb.append("- Utilisateur #").append(p.userId());
            if (p.organizer()) {
                sb.append(" (organisateur)");
            }
            sb.append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }

    private String renderAgenda(final List<AgendaItemReportDto> items) {
        StringBuilder sb = new StringBuilder("## Agenda\n\n");
        if (items.isEmpty()) {
            sb.append("_Aucun point à l'ordre du jour._\n\n");
            return sb.toString();
        }
        sb.append("| Point | Durée planifiée | Durée réelle | Dépassement |\n");
        sb.append("|---|---|---|---|\n");
        for (AgendaItemReportDto item : items) {
            sb.append("| ").append(item.title())
                    .append(" | ").append(item.plannedDurationMinutes()).append(" min")
                    .append(" | ").append(item.actualDurationSeconds() == null
                            ? "—" : formatDuration(item.actualDurationSeconds()))
                    .append(" | ").append(item.overtime() ? "Oui" : "Non")
                    .append(" |\n");
        }
        sb.append('\n');
        return sb.toString();
    }

    private String renderDecisions(final List<DecisionReportDto> decisions) {
        StringBuilder sb = new StringBuilder("## Décisions\n\n");
        if (decisions.isEmpty()) {
            sb.append("_Aucune décision enregistrée._\n\n");
            return sb.toString();
        }
        for (DecisionReportDto decision : decisions) {
            sb.append("- ").append(decision.label())
                    .append(" (").append(TIMESTAMP_FORMAT.format(decision.decidedAt())).append(")\n");
        }
        sb.append('\n');
        return sb.toString();
    }

    private String renderActions(final List<ActionReportDto> actions) {
        StringBuilder sb = new StringBuilder("## Actions\n\n");
        if (actions.isEmpty()) {
            sb.append("_Aucune action capturée._\n\n");
            return sb.toString();
        }
        sb.append("| Action | Responsable | Échéance |\n");
        sb.append("|---|---|---|\n");
        for (ActionReportDto action : actions) {
            sb.append("| ").append(action.label())
                    .append(" | ").append(action.ownerUserId() == null
                            ? "—" : "Utilisateur #" + action.ownerUserId())
                    .append(" | ").append(action.dueDate() == null ? "—" : action.dueDate())
                    .append(" |\n");
        }
        sb.append('\n');
        return sb.toString();
    }

    private String formatDuration(final int totalSeconds) {
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return minutes + " min " + seconds + " s";
    }
}
