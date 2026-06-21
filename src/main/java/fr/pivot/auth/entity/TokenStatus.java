package fr.pivot.auth.entity;

/**
 * Lifecycle status of an {@link AccessToken}.
 * Persisted as lowercase string via {@link TokenStatusConverter}.
 */
public enum TokenStatus {
    ACTIVE, EXPIRED, REVOKED
}
