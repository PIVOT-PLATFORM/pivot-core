package fr.pivot.plan.api;

import jakarta.validation.Valid;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller de gestion des plans commerciaux/tarifaires — {@code POST /api/superadmin/plans}
 * et {@code GET /api/superadmin/plans}/{@code /{planId}} (AC-gap self-clarification, voir la
 * description de la PR — l'AC littérale de US03.3.1 ne couvre que la gestion de la liste des
 * modules d'un plan existant, sans jamais créer/lister de {@code Plan} : sans cet ajout,
 * {@code planId} ne pourrait jamais exister), et {@code PUT}/{@code POST}/{@code GET}
 * {@code /api/superadmin/plans/{planId}/modules[/{moduleId}]} (US03.3.1 « SUPER_ADMIN définit
 * modules disponibles par plan »).
 *
 * <p>Mapping {@code /superadmin/plans} (sans préfixe {@code /api}, ajouté par {@code
 * server.servlet.context-path=/api}).
 *
 * <p>Aucune logique métier ici — délégation intégrale à {@link PlanService}, qui porte le
 * {@code @PreAuthorize("hasRole('SUPER_ADMIN')")} (RBAC porté par le service, même motif que
 * {@code SuperAdminTenantController}/{@code SuperAdminTenantService}).
 *
 * <p><strong>Isolation :</strong> {@code ROLE_SUPER_ADMIN} est un rôle plateforme, cross-tenant
 * par conception (voir CLAUDE.md, tableau des rôles) — ce contrôleur n'extrait ni n'applique
 * aucun {@code TenantContext}. {@code planId}/{@code moduleId} proviennent exclusivement des
 * {@code @PathVariable} — jamais du corps de requête (prévention IDOR, même discipline que
 * {@code SuperAdminTenantController#updateStatus}).
 */
@RestController
@RequestMapping("/superadmin/plans")
public class SuperAdminPlanController {

    private static final String KEY_ERROR = "error";
    private static final String KEY_MESSAGE = "message";

    private final PlanService planService;

    /**
     * Construit le contrôleur avec son collaborateur.
     *
     * @param planService service de gestion des plans (création, consultation, gestion des modules)
     */
    public SuperAdminPlanController(final PlanService planService) {
        this.planService = planService;
    }

    /**
     * Crée un nouveau plan, sans module assigné.
     *
     * @param request payload validé (nom du plan)
     * @return {@code 201} avec le plan créé (liste de modules vide) · {@code 400} si le nom est
     *     vide ou trop long · {@code 403} si l'appelant n'a pas {@code ROLE_SUPER_ADMIN} ·
     *     {@code 409} si le nom est déjà utilisé par un autre plan
     */
    @PostMapping
    public ResponseEntity<PlanDto> create(@Valid @RequestBody final CreatePlanRequest request) {
        final PlanDto created = planService.createPlan(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Liste tous les plans de la plateforme.
     *
     * @return {@code 200} avec la liste complète des plans (pas de pagination — liste de
     *     configuration de petite taille) · {@code 403} si l'appelant n'a pas {@code ROLE_SUPER_ADMIN}
     */
    @GetMapping
    public ResponseEntity<List<PlanDto>> list() {
        return ResponseEntity.ok(planService.listPlans());
    }

    /**
     * Récupère un plan avec sa liste de modules.
     *
     * @param planId identifiant du plan ciblé
     * @return {@code 200} avec le plan · {@code 403} si l'appelant n'a pas {@code ROLE_SUPER_ADMIN} ·
     *     {@code 404} si {@code planId} n'existe pas
     */
    @GetMapping("/{planId}")
    public ResponseEntity<PlanDto> get(@PathVariable("planId") final Long planId) {
        return ResponseEntity.ok(planService.getPlan(planId));
    }

    /**
     * Remplace intégralement la liste des modules d'un plan.
     *
     * @param planId  identifiant du plan ciblé (path variable — jamais le corps)
     * @param request corps de requête — {@code { "moduleIds": [...] } }, une liste vide est
     *                acceptée (retire tous les modules du plan)
     * @return {@code 200} avec la liste de modules à jour · {@code 400} si {@code moduleIds} est
     *     absent ou contient un identifiant non enregistré dans le registre · {@code 403} si
     *     l'appelant n'a pas {@code ROLE_SUPER_ADMIN} · {@code 404} si {@code planId} n'existe pas
     */
    @PutMapping("/{planId}/modules")
    public ResponseEntity<PlanModulesResponse> replaceModules(
            @PathVariable("planId") final Long planId,
            @Valid @RequestBody final ReplacePlanModulesRequest request) {
        return ResponseEntity.ok(planService.replaceModules(planId, request.moduleIds()));
    }

    /**
     * Ajoute un module unique à un plan — idempotent : un module déjà présent n'est ni dupliqué
     * ni signalé en erreur (voir {@link PlanService} pour la justification de ce choix).
     *
     * @param planId   identifiant du plan ciblé (path variable)
     * @param moduleId identifiant du module à ajouter (path variable — jamais le corps)
     * @return {@code 200} avec la liste de modules à jour · {@code 400} si {@code moduleId} n'est
     *     pas enregistré dans le registre · {@code 403} si l'appelant n'a pas {@code ROLE_SUPER_ADMIN} ·
     *     {@code 404} si {@code planId} n'existe pas
     */
    @PostMapping("/{planId}/modules/{moduleId}")
    public ResponseEntity<PlanModulesResponse> addModule(
            @PathVariable("planId") final Long planId,
            @PathVariable("moduleId") final String moduleId) {
        return ResponseEntity.ok(planService.addModule(planId, moduleId));
    }

    /**
     * Retourne la liste courante des modules d'un plan.
     *
     * @param planId identifiant du plan ciblé
     * @return {@code 200} avec la liste de modules · {@code 403} si l'appelant n'a pas
     *     {@code ROLE_SUPER_ADMIN} · {@code 404} si {@code planId} n'existe pas
     */
    @GetMapping("/{planId}/modules")
    public ResponseEntity<PlanModulesResponse> getModules(@PathVariable("planId") final Long planId) {
        return ResponseEntity.ok(planService.getModules(planId));
    }

    // ----------------------------------------------------------------
    // Exception handling — local à ce contrôleur (pas de handler global)
    // ----------------------------------------------------------------

    /**
     * Traduit un {@code planId} inexistant en {@code 404 Not Found}.
     *
     * @return corps d'erreur {@code 404}
     */
    @ExceptionHandler(PlanNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handlePlanNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                KEY_ERROR, "PLAN_NOT_FOUND",
                KEY_MESSAGE, "Ce plan n'existe pas"));
    }

    /**
     * Traduit un identifiant de module non enregistré dans le registre en {@code 400 Bad Request}.
     *
     * @return corps d'erreur {@code 400}
     */
    @ExceptionHandler(UnknownModuleIdException.class)
    public ResponseEntity<Map<String, Object>> handleUnknownModuleId() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                KEY_ERROR, "UNKNOWN_MODULE_ID",
                KEY_MESSAGE, "Cet identifiant de module n'est pas enregistré dans le registre"));
    }

    /**
     * Traduit un nom de plan déjà utilisé en {@code 409 Conflict}.
     *
     * @return corps d'erreur {@code 409}
     */
    @ExceptionHandler(PlanNameAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handlePlanNameAlreadyExists() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                KEY_ERROR, "PLAN_NAME_ALREADY_EXISTS",
                KEY_MESSAGE, "Ce nom de plan est déjà utilisé"));
    }
}
