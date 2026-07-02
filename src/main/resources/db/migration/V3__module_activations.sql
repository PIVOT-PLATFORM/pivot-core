-- EN03.1 — état d'activation des modules PIVOT par tenant (schéma public)
-- Une ligne par couple (tenant, module). Absence de ligne = module désactivé.

CREATE TABLE IF NOT EXISTS module_activations (
    id          BIGSERIAL    NOT NULL,
    tenant_id   BIGINT       NOT NULL,
    module_id   VARCHAR(100) NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_module_activations PRIMARY KEY (id),
    -- Un seul état par couple (tenant, module) — sert aussi d'index de lookup
    -- (préfixe tenant_id) pour findAllByTenantId / findByTenantIdAndModuleId.
    CONSTRAINT uq_ma_tenant_module UNIQUE (tenant_id, module_id),
    -- CASCADE justifié : l'état d'activation n'a aucun sens sans son tenant.
    CONSTRAINT fk_ma_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE
);
