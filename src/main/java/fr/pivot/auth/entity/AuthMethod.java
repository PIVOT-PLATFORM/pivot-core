package fr.pivot.auth.entity;

/**
 * Authentication method used to create a session.
 * Persisted as lowercase string via {@link AuthMethodConverter}.
 */
public enum AuthMethod {
    PASSWORD, GOOGLE, OIDC
}
