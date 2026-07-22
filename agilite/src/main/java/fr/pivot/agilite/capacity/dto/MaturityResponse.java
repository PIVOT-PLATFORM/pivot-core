package fr.pivot.agilite.capacity.dto;

import fr.pivot.agilite.capacity.CapacityMaturityLevel;

/**
 * Response payload for a team's effective maturity tier and derived defaults (US11.6.4).
 *
 * @param maturity           the team's configured maturity, or {@code null} if unconfigured
 * @param focusFactorPercent the effective default focus factor for this maturity (or the global
 *                           default if {@code maturity} is {@code null})
 * @param marginPercent      the effective margin
 * @param source             {@code "TEAM_MATURITY"} if {@code maturity} is configured, {@code
 *                           "DEFAULT"} otherwise
 */
public record MaturityResponse(
        CapacityMaturityLevel maturity, int focusFactorPercent, int marginPercent, String source) {
}
