package fr.pivot.agilite.capacity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data repository for {@link CapacityEventMember} (E11 — capacity planning), schema
 * {@code agilite}.
 *
 * <p>Not directly tenant-scoped (this table carries no {@code tenant_id} of its own — see {@link
 * CapacityEventMember}'s Javadoc): the (future) service layer resolves and checks the owning
 * {@link CapacityEvent}'s tenant first, same pattern already used by {@code
 * fr.pivot.agilite.poker.vote.PokerVoteRepository} for {@code agilite.poker_votes}.
 */
public interface CapacityEventMemberRepository extends JpaRepository<CapacityEventMember, UUID> {

    /**
     * Finds every member of an event, in display order.
     *
     * @param eventId the owning event's identifier
     * @return the event's members, ordered by {@link CapacityEventMember#getPosition()}
     */
    List<CapacityEventMember> findByEventIdOrderByPositionAsc(UUID eventId);
}
