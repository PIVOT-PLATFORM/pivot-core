package fr.pivot.collaboratif.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionJoinCodeGeneratorTest {

    @Mock
    private SessionRepository sessionRepository;

    @Test
    void generatesA6CharacterCodeFromTheExpectedAlphabet() {
        when(sessionRepository.existsByTenantIdAndJoinCodeAndStatusNot(anyLong(), anyString(), any()))
                .thenReturn(false);
        SessionJoinCodeGenerator generator = new SessionJoinCodeGenerator(sessionRepository);

        String code = generator.generate(1L);

        assertThat(code).hasSize(6);
        assertThat(code).matches("[A-HJ-NP-Z2-9]{6}");
    }

    @Test
    void retriesOnCollisionUntilAUniqueCodeIsFound() {
        when(sessionRepository.existsByTenantIdAndJoinCodeAndStatusNot(anyLong(), anyString(), any()))
                .thenReturn(true, true, false);
        SessionJoinCodeGenerator generator = new SessionJoinCodeGenerator(sessionRepository);

        String code = generator.generate(1L);

        assertThat(code).hasSize(6);
    }

    @Test
    void throwsAfterTenConsecutiveCollisions() {
        when(sessionRepository.existsByTenantIdAndJoinCodeAndStatusNot(anyLong(), anyString(), any()))
                .thenReturn(true);
        SessionJoinCodeGenerator generator = new SessionJoinCodeGenerator(sessionRepository);

        assertThatThrownBy(() -> generator.generate(1L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void scopesTheUniquenessCheckToTheGivenTenantAndExcludesCompletedSessions() {
        when(sessionRepository.existsByTenantIdAndJoinCodeAndStatusNot(
                org.mockito.ArgumentMatchers.eq(42L), anyString(),
                org.mockito.ArgumentMatchers.eq(SessionStatus.COMPLETED)))
                .thenReturn(false);
        SessionJoinCodeGenerator generator = new SessionJoinCodeGenerator(sessionRepository);

        generator.generate(42L);

        org.mockito.Mockito.verify(sessionRepository).existsByTenantIdAndJoinCodeAndStatusNot(
                org.mockito.ArgumentMatchers.eq(42L), anyString(), org.mockito.ArgumentMatchers.eq(SessionStatus.COMPLETED));
    }
}
