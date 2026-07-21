package fr.pivot.collaboratif.whiteboard.template;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link WhiteboardTemplateElement} entities.
 */
public interface WhiteboardTemplateElementRepository
        extends JpaRepository<WhiteboardTemplateElement, UUID> {

    /**
     * Returns all elements of a template, ordered for deterministic replay onto a new board.
     *
     * @param templateId the template UUID
     * @return the ordered list of elements; empty if the template has none
     */
    List<WhiteboardTemplateElement> findAllByTemplateIdOrderByDisplayOrderAsc(UUID templateId);

    /**
     * Removes every element of a template (US08.13.2).
     *
     * <p>Used by {@code WhiteboardTemplateService#captureBoardInto} to replace a template's
     * content wholesale. A wipe-then-insert rather than a diff: template elements have no stable
     * identity across a capture — a card the user deleted from the draft must disappear, and
     * matching survivors would mean inventing a correspondence the model does not carry.
     *
     * @param templateId the template whose elements are removed
     * @return the number of elements deleted
     */
    long deleteAllByTemplateId(UUID templateId);
}
