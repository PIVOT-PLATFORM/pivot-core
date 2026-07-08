-- Test migration for test_module schema (EN17.4 schema isolation test)
-- Validates that a module can define its own schema and tables without touching public schema.
-- Cross-schema FK toward public.tenants(id) is the only allowed cross-schema reference.

CREATE TABLE IF NOT EXISTS test_module.items (
    id          BIGSERIAL   NOT NULL,
    label       VARCHAR(255) NOT NULL,
    -- FK cross-schéma autorisée uniquement vers public.tenants(id) ou public.teams(id)
    -- Per EN17.4 convention: {schema}.table → public.teams(id) / public.tenants(id)
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_test_module_items PRIMARY KEY (id)
);
