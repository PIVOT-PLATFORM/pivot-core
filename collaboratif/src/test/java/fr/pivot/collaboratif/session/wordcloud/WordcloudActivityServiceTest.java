package fr.pivot.collaboratif.session.wordcloud;

import fr.pivot.collaboratif.exception.InvalidSessionStatusException;
import fr.pivot.collaboratif.exception.SessionConflictException;
import fr.pivot.collaboratif.exception.SessionValidationException;
import fr.pivot.collaboratif.session.Session;
import fr.pivot.collaboratif.session.SessionStatus;
import fr.pivot.collaboratif.session.SessionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WordcloudActivityServiceTest {

    @Mock
    private SessionWordcloudEntryRepository entryRepository;
    @Mock
    private SessionWordcloudSubmissionRepository submissionRepository;
    @Mock
    private TenantWordBlocklistRepository blocklistRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private WordcloudActivityService service;

    @BeforeEach
    void setUp() {
        service = new WordcloudActivityService(
                entryRepository, submissionRepository, blocklistRepository, messagingTemplate, new ObjectMapper());
    }

    private Session liveSession(final String config) {
        Session session = new Session(1L, null, "T", SessionType.WORDCLOUD, "ABCDEF", config, 10L, Instant.now());
        session.setStatus(SessionStatus.LIVE);
        return session;
    }

    @Test
    void submitWordRejectsWhenSessionIsNotLive() {
        Session session = new Session(1L, null, "T", SessionType.WORDCLOUD, "ABCDEF", "{}", 10L, Instant.now());

        assertThatThrownBy(() -> service.submitWord(session, UUID.randomUUID(), "hello"))
                .isInstanceOf(InvalidSessionStatusException.class);
    }

    @Test
    void submitWordRejectsAnEmptyWord() {
        Session session = liveSession("{}");

        assertThatThrownBy(() -> service.submitWord(session, UUID.randomUUID(), "   "))
                .isInstanceOf(SessionValidationException.class);
    }

    @Test
    void submitWordRejectsABlocklistedWord() {
        Session session = liveSession("{}");
        when(blocklistRepository.existsByTenantIdAndWord(1L, "spam")).thenReturn(true);

        assertThatThrownBy(() -> service.submitWord(session, UUID.randomUUID(), "spam"))
                .isInstanceOf(SessionConflictException.class)
                .satisfies(ex -> assertThat(((SessionConflictException) ex).getCode()).isEqualTo("WORD_BLOCKED"));
        verify(submissionRepository, never()).save(any());
    }

    @Test
    void submitWordRejectsWhenTheParticipantQuotaIsExhausted() {
        Session session = liveSession("{\"maxWordsPerParticipant\":2}");
        UUID participantId = UUID.randomUUID();
        when(blocklistRepository.existsByTenantIdAndWord(1L, "word")).thenReturn(false);
        when(submissionRepository.countBySessionIdAndParticipantId(session.getId(), participantId)).thenReturn(2L);

        assertThatThrownBy(() -> service.submitWord(session, participantId, "word"))
                .isInstanceOf(SessionConflictException.class)
                .satisfies(ex -> assertThat(((SessionConflictException) ex).getCode()).isEqualTo("WORD_LIMIT_REACHED"));
    }

    @Test
    void submitWordNormalizesAndAggregatesFrequency() {
        Session session = liveSession("{}");
        UUID participantId = UUID.randomUUID();
        when(blocklistRepository.existsByTenantIdAndWord(1L, "hello")).thenReturn(false);
        when(submissionRepository.countBySessionIdAndParticipantId(session.getId(), participantId)).thenReturn(0L);
        SessionWordcloudEntry existing = new SessionWordcloudEntry(session.getId(), "hello");
        when(entryRepository.findBySessionIdAndWord(session.getId(), "hello")).thenReturn(Optional.of(existing));
        when(entryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.submitWord(session, participantId, "  HELLO  ");

        assertThat(existing.getFrequency()).isEqualTo(2);
        verify(submissionRepository).save(any());
        verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void removeWordDeletesTheEntryAndSubmissionsAndBroadcasts() {
        UUID sessionId = UUID.randomUUID();

        service.removeWord(sessionId, "Spam");

        verify(entryRepository).deleteBySessionIdAndWord(sessionId, "spam");
        verify(submissionRepository).deleteBySessionIdAndWord(sessionId, "spam");
        verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void getWordsReturnsAggregatedEntriesMostFrequentFirst() {
        UUID sessionId = UUID.randomUUID();
        SessionWordcloudEntry entry = new SessionWordcloudEntry(sessionId, "hello");
        when(entryRepository.findAllBySessionIdOrderByFrequencyDesc(sessionId)).thenReturn(List.of(entry));

        assertThat(service.getWords(sessionId)).hasSize(1);
    }
}
