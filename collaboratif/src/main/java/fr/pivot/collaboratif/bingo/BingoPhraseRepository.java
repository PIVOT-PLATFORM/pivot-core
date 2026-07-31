package fr.pivot.collaboratif.bingo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data repository for {@link BingoPhrase} (US47.1.1).
 */
public interface BingoPhraseRepository extends JpaRepository<BingoPhrase, UUID> {

    /**
     * Draws {@code count} distinct random phrases from the bank in a single round trip, using
     * PostgreSQL's {@code ORDER BY random()} — acceptable at this table's size (a few dozen
     * rows), avoids fetching the whole bank into the JVM just to shuffle it there.
     *
     * @param count how many distinct phrases to draw (25 for a Bingo grid)
     * @return up to {@code count} randomly ordered phrases
     */
    @Query(value = "SELECT * FROM collaboratif.bingo_phrases ORDER BY random() LIMIT :count", nativeQuery = true)
    List<BingoPhrase> drawRandom(int count);
}
