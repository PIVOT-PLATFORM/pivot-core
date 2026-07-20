-- Self-heal test fixture (EN53) — schema-agnostic single migration.
-- The table name is intentionally UNQUALIFIED so the script lands in whatever schema the
-- ModuleFlywayConfigurer sets as default (Flyway sets the connection search_path to it),
-- letting each test target its own isolated schema against the shared Testcontainer.
CREATE TABLE IF NOT EXISTS widget (
    id    BIGSERIAL    NOT NULL,
    label VARCHAR(255) NOT NULL,
    CONSTRAINT pk_selfheal_widget PRIMARY KEY (id)
);
