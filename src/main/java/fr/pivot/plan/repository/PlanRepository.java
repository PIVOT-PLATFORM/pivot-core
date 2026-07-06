package fr.pivot.plan.repository;

import fr.pivot.plan.entity.Plan;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Accès BDD aux plans commerciaux/tarifaires ({@code public.plans}) — US03.3.1 « SUPER_ADMIN
 * définit modules disponibles par plan ».
 */
public interface PlanRepository extends JpaRepository<Plan, Long> {

    /**
     * Recherche un plan par son nom exact — utilisé pour la vérification d'unicité à la
     * création ({@code uq_plans_name}).
     *
     * @param name nom exact du plan
     * @return le plan s'il existe, {@link Optional#empty()} sinon
     */
    Optional<Plan> findByName(String name);
}
