package fr.pivot.agilite.capacity.dto;

import java.util.List;

/**
 * Pure input value for one event member, consumed by {@code
 * fr.pivot.agilite.capacity.calc.CapacityCalculator} (E11 — capacity planning).
 *
 * @param id          a caller-chosen identifier correlating this input to its {@link
 *                    MemberCapacityResult}, or to {@code
 *                    fr.pivot.agilite.capacity.CapacityEventMember#getId()} once wired by a later
 *                    wave — opaque to the calculator itself
 * @param quotite     the full-time-equivalent quotity, {@code 1} = full-time
 * @param focusFactor the per-member focus factor override, in {@code [0, 1]} — takes precedence
 *                    over {@link CapacityEventInput#roleFocusFactors()}/{@link
 *                    CapacityEventInput#focusFactor()}, or {@code null} if unset
 * @param role        the member's role, used to resolve {@link
 *                    CapacityEventInput#roleFocusFactors()} when {@code focusFactor} is {@code
 *                    null}, or {@code null} if none
 * @param excluded    whether this member is excluded from the event's aggregate totals (still
 *                    computed individually — see {@code CapacityCalculator#computeEventCapacity})
 * @param position    display order, used only to sort the returned member results
 * @param absences    this member's absences over the event's period
 */
public record CapacityMemberInput(
        String id,
        double quotite,
        Double focusFactor,
        String role,
        boolean excluded,
        int position,
        List<CapacityAbsenceInput> absences) {
}
