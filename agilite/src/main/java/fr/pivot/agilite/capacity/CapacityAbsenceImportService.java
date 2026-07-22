package fr.pivot.agilite.capacity;

import fr.pivot.agilite.auth.entity.PlatformTeamMember;
import fr.pivot.agilite.auth.entity.PlatformUser;
import fr.pivot.agilite.auth.repository.PlatformTeamMemberReadRepository;
import fr.pivot.agilite.auth.repository.PlatformUserReadRepository;
import fr.pivot.agilite.capacity.dto.AbsenceImportResponse;
import fr.pivot.agilite.exception.CapacityValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for the generic CSV bulk absence import (US11.7.1).
 *
 * <p><strong>Import CSV générique, pas de connecteur SI RH nommé</strong> (US11.7.1 §Architecture,
 * explicit maintainer decision, 2026-07-22): named live connectors to SAP/Workday/Lucca are
 * unbuildable here (no real credentials/sandbox access) — a generic CSV export/import delivers the
 * same real value (bulk import instead of one-by-one manual entry) without a fake, unverifiable
 * "integration." Reuses {@link CapacityAbsenceService#create}'s exact validation per row (RGPD
 * minimisation included — see that class's Javadoc) rather than duplicating it.
 */
@Service
@Transactional
public class CapacityAbsenceImportService {

    private static final int MAX_LINES = 500;
    private static final int EXPECTED_COLUMNS = 3;
    private static final String CODE_INVALID_DATE_RANGE = "INVALID_DATE_RANGE";

    private final CapacityEventService eventService;
    private final CapacityEventMemberRepository memberRepository;
    private final CapacityAbsenceRepository absenceRepository;
    private final PlatformTeamMemberReadRepository teamMemberRepository;
    private final PlatformUserReadRepository userRepository;

    /**
     * Creates the service with its required dependencies.
     *
     * <p>Deliberately does <strong>not</strong> call {@link CapacityAbsenceService#create}
     * per row: that method is itself {@code @Transactional} (default {@code REQUIRED}
     * propagation) — catching its thrown {@link CapacityValidationException} here would still
     * leave the shared transaction marked rollback-only by Spring's proxy interceptor around
     * that nested call, causing an {@code UnexpectedRollbackException} at commit time despite
     * this method returning normally. Same validation rules ({@code dateDebut}/{@code dateFin}
     * overlap with the event period, RGPD-minimal columns) are reproduced inline instead,
     * persisting directly via {@link #absenceRepository}.
     *
     * @param eventService          shared event access resolution
     * @param memberRepository      repository for roster member persistence
     * @param absenceRepository     repository for absence persistence
     * @param teamMemberRepository  read-only access to {@code public.team_members} (email resolution)
     * @param userRepository        read-only access to {@code public.users} (email resolution)
     */
    public CapacityAbsenceImportService(
            final CapacityEventService eventService,
            final CapacityEventMemberRepository memberRepository,
            final CapacityAbsenceRepository absenceRepository,
            final PlatformTeamMemberReadRepository teamMemberRepository,
            final PlatformUserReadRepository userRepository) {
        this.eventService = eventService;
        this.memberRepository = memberRepository;
        this.absenceRepository = absenceRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.userRepository = userRepository;
    }

    /**
     * Imports absences in bulk from a CSV file — {@code teamMemberIdOrEmail,dateDebut,dateFin}
     * columns only, any other column is silently ignored (US11.7.1's explicit RGPD guard: a
     * "motif"/"reason" column from a source HR export is never read, never persisted).
     *
     * @param eventId      the event UUID
     * @param file         the uploaded CSV file
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the per-row import result
     */
    public AbsenceImportResponse importCsv(
            final UUID eventId, final MultipartFile file, final Long callerUserId, final Long tenantId) {
        CapacityEvent event = eventService.resolveForCaller(eventId, callerUserId, tenantId);
        List<String> lines = readLines(file);
        if (lines.isEmpty()) {
            throw new CapacityValidationException("INVALID_IMPORT_FILE", "File is empty");
        }
        // Header line excluded from the MAX_LINES data-row count.
        List<String> dataLines = lines.subList(1, lines.size());
        if (dataLines.size() > MAX_LINES) {
            throw new CapacityValidationException("INVALID_IMPORT_FILE", "File exceeds " + MAX_LINES + " rows");
        }

        Map<Long, CapacityEventMember> membersByTeamMemberId = new HashMap<>();
        Map<String, CapacityEventMember> membersByEmail = new HashMap<>();
        for (CapacityEventMember member : memberRepository.findAllByEventIdOrderByNameAsc(eventId)) {
            membersByTeamMemberId.put(member.getTeamMemberId(), member);
            resolveEmail(member.getTeamMemberId(), event.getTeamId())
                    .ifPresent(email -> membersByEmail.put(email.toLowerCase(Locale.ROOT), member));
        }

        int imported = 0;
        List<AbsenceImportResponse.RowError> errors = new ArrayList<>();
        for (int i = 0; i < dataLines.size(); i++) {
            int lineNumber = i + 1;
            String errorCode = processRow(dataLines.get(i), event, membersByTeamMemberId, membersByEmail);
            if (errorCode == null) {
                imported++;
            } else {
                errors.add(new AbsenceImportResponse.RowError(lineNumber, errorCode));
            }
        }

        return new AbsenceImportResponse(imported, errors);
    }

    /**
     * Processes a single CSV data row: resolves the member, validates the dates, and persists the
     * absence (or detects an exact duplicate and no-ops) — a single-exit helper so the caller's
     * loop never needs more than one branch per row.
     *
     * @param row                    the raw CSV data line (no header)
     * @param event                  the target event, already access-checked
     * @param membersByTeamMemberId  the event's roster, keyed by {@code teamMemberId}
     * @param membersByEmail         the event's roster, keyed by lowercase resolved email
     * @return {@code null} if the row was imported (including a silently-deduped exact
     *     duplicate), or the machine-readable error code otherwise
     */
    private String processRow(
            final String row,
            final CapacityEvent event,
            final Map<Long, CapacityEventMember> membersByTeamMemberId,
            final Map<String, CapacityEventMember> membersByEmail) {
        String[] columns = row.split(",", -1);
        if (columns.length < EXPECTED_COLUMNS) {
            return CODE_INVALID_DATE_RANGE;
        }
        CapacityEventMember member = resolveMember(columns[0].trim(), membersByTeamMemberId, membersByEmail);
        if (member == null) {
            return "UNKNOWN_MEMBER";
        }

        LocalDate dateDebut;
        LocalDate dateFin;
        try {
            dateDebut = LocalDate.parse(columns[1].trim());
            dateFin = LocalDate.parse(columns[2].trim());
        } catch (DateTimeParseException _) {
            return CODE_INVALID_DATE_RANGE;
        }
        if (dateDebut.isAfter(dateFin)) {
            return CODE_INVALID_DATE_RANGE;
        }
        boolean fullyOutside = dateFin.isBefore(event.getStartDate()) || dateDebut.isAfter(event.getEndDate());
        if (fullyOutside) {
            return "ABSENCE_OUTSIDE_EVENT";
        }

        if (!isExactDuplicate(member.getId(), dateDebut, dateFin)) {
            absenceRepository.save(new CapacityAbsence(member.getId(), dateDebut, dateFin));
        }
        return null;
    }

    private CapacityEventMember resolveMember(
            final String ref, final Map<Long, CapacityEventMember> byTeamMemberId, final Map<String, CapacityEventMember> byEmail) {
        try {
            return byTeamMemberId.get(Long.parseLong(ref));
        } catch (NumberFormatException _) {
            return byEmail.get(ref.toLowerCase(Locale.ROOT));
        }
    }

    private boolean isExactDuplicate(final UUID eventMemberId, final LocalDate dateDebut, final LocalDate dateFin) {
        return absenceRepository.findAllByEventMemberIdOrderByDateDebutAsc(eventMemberId).stream()
                .anyMatch(absence -> absence.getDateDebut().equals(dateDebut) && absence.getDateFin().equals(dateFin));
    }

    private Optional<String> resolveEmail(final Long teamMemberId, final Long teamId) {
        return teamMemberRepository.findByIdAndTeamId(teamMemberId, teamId)
                .map(PlatformTeamMember::getUserId)
                .flatMap(userRepository::findById)
                .map(PlatformUser::getEmail);
    }

    private List<String> readLines(final MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new CapacityValidationException("INVALID_IMPORT_FILE", "File is empty");
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
            return lines;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
