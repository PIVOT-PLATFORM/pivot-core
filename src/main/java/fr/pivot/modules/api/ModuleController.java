package fr.pivot.modules.api;

import fr.pivot.auth.entity.User;
import fr.pivot.modules.registry.ModuleDto;
import fr.pivot.modules.registry.ModuleRegistryService;
import fr.pivot.modules.registry.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

/**
 * REST controller exposant le registre des modules PIVOT.
 *
 * <p>Responsabilité unique : résolution du contexte tenant depuis le
 * {@link SecurityContextHolder} et délégation au {@link ModuleRegistryService}.
 * Aucune logique métier dans ce contrôleur.
 *
 * <p>Le filtre {@link fr.pivot.config.TokenAuthenticationFilter} peuple le contexte de
 * sécurité avec un {@code UsernamePasswordAuthenticationToken} dont les détails
 * ({@code getDetails()}) contiennent l'entité {@link User} résolue depuis la BDD.
 */
@RestController
@RequestMapping("/api/modules")
public class ModuleController {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleController.class);

    private final ModuleRegistryService moduleRegistryService;

    /**
     * Construit le contrôleur avec son collaborateur de service.
     *
     * @param moduleRegistryService service de résolution des modules par tenant
     */
    public ModuleController(final ModuleRegistryService moduleRegistryService) {
        this.moduleRegistryService = moduleRegistryService;
    }

    /**
     * Retourne la liste des modules PIVOT avec leur statut pour l'utilisateur authentifié.
     *
     * <p>Le contexte tenant est construit depuis le {@link User} stocké dans les détails
     * de l'authentication courante (posé par {@link fr.pivot.config.TokenAuthenticationFilter}).
     * L'identifiant tenant (Long BDD) est converti en UUID déterministe par encodage big-endian.
     *
     * @return 200 OK avec la liste des {@link ModuleDto}
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ModuleDto>> getModules() {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        final User user = (User) auth.getDetails();

        final TenantContext ctx = buildTenantContext(user);

        LOG.info("event=GET_MODULES userId={} tenantId={} role={}",
                user.getId(), ctx.tenantId(), user.getRole());

        final List<ModuleDto> modules = moduleRegistryService.getModulesForTenant(ctx);
        return ResponseEntity.ok(modules);
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    /**
     * Construit un {@link TenantContext} depuis l'entité {@link User} authentifiée.
     *
     * <p>L'identifiant tenant stocké en BDD est un {@code Long}. Il est converti en
     * {@link UUID} déterministe (big-endian 64 bits, high-bits à zéro) pour satisfaire
     * le contrat {@link TenantContext} sans perte d'information.
     *
     * @param user l'utilisateur authentifié résolu par le filtre de sécurité
     * @return contexte tenant prêt pour l'évaluation des modules
     */
    private static TenantContext buildTenantContext(final User user) {
        final Long tenantLongId = user.getTenant().getId();
        final UUID tenantUuid = longToUuid(tenantLongId);
        final String userId = user.getId() != null ? user.getId().toString() : "unknown";
        return new TenantContext(tenantUuid, userId, user.getRole());
    }

    /**
     * Convertit un identifiant {@code Long} en {@link UUID} déterministe.
     *
     * <p>Encodage : {@code mostSignificantBits = 0L}, {@code leastSignificantBits = longId}.
     * Garantit une bijection Long → UUID pour les valeurs {@code ≥ 0}.
     *
     * @param id identifiant Long à convertir
     * @return UUID correspondant, jamais {@code null}
     */
    private static UUID longToUuid(final Long id) {
        final long safeId = id != null ? id : 0L;
        final ByteBuffer bb = ByteBuffer.allocate(Long.BYTES * 2);
        bb.putLong(0L);
        bb.putLong(safeId);
        bb.rewind();
        return new UUID(bb.getLong(), bb.getLong());
    }
}
