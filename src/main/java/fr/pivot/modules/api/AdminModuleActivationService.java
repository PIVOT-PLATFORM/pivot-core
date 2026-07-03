package fr.pivot.modules.api;

import fr.pivot.core.modules.ModuleActivation;
import fr.pivot.core.modules.ModuleActivationService;
import fr.pivot.core.modules.UnknownModuleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Façade d'administration au-dessus de {@link ModuleActivationService} — expose
 * l'activation/désactivation de module aux seuls administrateurs de tenant (US03.1.1,
 * US03.1.2).
 *
 * <p>Ne modifie pas {@link ModuleActivationService} (partagé, contrat inter-repos) :
 * ce service ajoute la vérification de rôle ({@code @PreAuthorize}) et la sémantique
 * HTTP admin (409 sur activation redondante, 403 sur module hors registre) par-dessus
 * la logique idempotente déjà fournie.
 *
 * <p><strong>Sécurité — RBAC porté par le service, pas seulement le contrôleur :</strong>
 * {@code @PreAuthorize("hasRole('ADMIN')")} est évalué par le proxy Spring Method Security
 * ({@code @EnableMethodSecurity}, activé dans {@code SecurityConfig}) à chaque appel, y
 * compris si un futur appelant interne oublie la vérification côté contrôleur.
 *
 * <p><strong>Simplification plan/entitlement :</strong> voir {@link ModuleNotInPlanException}.
 */
@Service
public class AdminModuleActivationService {

    private static final Logger LOG = LoggerFactory.getLogger(AdminModuleActivationService.class);

    private final ModuleActivationService moduleActivationService;

    /**
     * Construit le service avec son collaborateur.
     *
     * @param moduleActivationService service partagé de gestion d'état d'activation
     */
    public AdminModuleActivationService(final ModuleActivationService moduleActivationService) {
        this.moduleActivationService = moduleActivationService;
    }

    /**
     * Active un module pour le tenant de l'administrateur courant.
     *
     * <p>Note de conception : la vérification {@code isEnabled} puis l'appel à
     * {@code activate} ne sont pas atomiques entre eux (deux requêtes concurrentes du même
     * tenant pourraient toutes deux passer la vérification). Accepté pour ce périmètre :
     * action d'administration à faible concurrence, sans conséquence de sécurité — au pire
     * un événement {@code ModuleActivatedEvent} dupliqué n'est de toute façon jamais publié
     * par {@link ModuleActivationService} (idempotence déjà garantie côté état persisté).
     *
     * @param tenantId identifiant du tenant, résolu exclusivement depuis le token porteur
     * @param moduleId identifiant technique du module à activer
     * @return l'état d'activation persisté
     * @throws ModuleAlreadyActiveException si le module est déjà activé pour ce tenant
     * @throws ModuleNotInPlanException     si le module n'est pas enregistré dans le registre
     */
    @PreAuthorize("hasRole('ADMIN')")
    public ModuleActivation activate(final Long tenantId, final String moduleId) {
        if (moduleActivationService.isEnabled(tenantId, moduleId)) {
            LOG.info("event=ADMIN_MODULE_ACTIVATE_CONFLICT tenantId={} moduleId={}",
                    tenantId, sanitizeForLog(moduleId));
            throw new ModuleAlreadyActiveException(moduleId);
        }
        try {
            return moduleActivationService.activate(tenantId, moduleId);
        } catch (final UnknownModuleException ex) {
            throw new ModuleNotInPlanException(moduleId, ex);
        }
    }

    /**
     * Neutralise les caractères de contrôle CR/LF d'une valeur avant de la loguer.
     *
     * <p>{@code moduleId} provient in fine d'un {@code @PathVariable} — donnée utilisateur
     * non fiable. Sans neutralisation, un identifiant contenant {@code \r} ou {@code \n}
     * permettrait d'injecter de fausses lignes de log (CWE-117 / log forging) dans un
     * fichier de log en texte brut. La valeur n'est jamais utilisée ailleurs que dans un
     * message de log — la logique métier continue d'utiliser l'identifiant d'origine.
     *
     * @param value valeur potentiellement non fiable à journaliser
     * @return valeur sans retour chariot ni saut de ligne, sûre pour un message de log
     */
    private static String sanitizeForLog(final String value) {
        return value == null ? "null" : value.replaceAll("[\r\n]", "_");
    }

    /**
     * Désactive un module pour le tenant de l'administrateur courant.
     *
     * <p>Idempotent : aucune erreur si le module était déjà inactif (ou jamais activé) —
     * pas d'AC exigeant de signaler ce cas distinctement (contrairement à {@link #activate}).
     *
     * @param tenantId identifiant du tenant, résolu exclusivement depuis le token porteur
     * @param moduleId identifiant technique du module à désactiver
     * @return l'état d'activation persisté
     * @throws ModuleNotInPlanException si le module n'est pas enregistré dans le registre
     */
    @PreAuthorize("hasRole('ADMIN')")
    public ModuleActivation deactivate(final Long tenantId, final String moduleId) {
        try {
            return moduleActivationService.deactivate(tenantId, moduleId);
        } catch (final UnknownModuleException ex) {
            throw new ModuleNotInPlanException(moduleId, ex);
        }
    }
}
