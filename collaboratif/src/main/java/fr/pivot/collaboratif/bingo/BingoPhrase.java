package fr.pivot.collaboratif.bingo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * JPA entity backing a single phrase of the shared default Bingo phrase bank (US47.1.1), table
 * {@code collaboratif.bingo_phrases} — seeded by {@code V17__bingo.sql}, never written to by
 * application code in this US (custom/editable banks are explicitly out of scope).
 */
@Entity
@Table(name = "bingo_phrases", schema = "collaboratif")
public class BingoPhrase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "phrase", nullable = false, length = 200)
    private String phrase;

    /** No-argument constructor required by JPA. */
    protected BingoPhrase() {
    }

    /** @return database primary key */
    public UUID getId() {
        return id;
    }

    /** @return the phrase text */
    public String getPhrase() {
        return phrase;
    }
}
