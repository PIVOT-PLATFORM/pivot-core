package fr.pivot.collaboratif.bingo.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.Principal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BingoGuestPrincipalResolver} (US47.1.1, AC-47.1.1-03).
 */
@ExtendWith(MockitoExtension.class)
class BingoGuestPrincipalResolverTest {

    private static final UUID ROOM_ID = UUID.randomUUID();

    @Mock
    private BingoRoomAccessGrantService grantService;

    private BingoGuestPrincipalResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new BingoGuestPrincipalResolver(grantService);
    }

    @Test
    void resolveGuest_validRoomIdAndActiveGrant_returnsAnAnonymousPrincipal() {
        when(grantService.hasAccess(ROOM_ID, "abc")).thenReturn(true);

        Optional<Principal> result = resolver.resolveGuest(ROOM_ID + ":abc");

        assertThat(result).isPresent();
        assertThat(result.get().getName()).startsWith("bingo-guest:");
    }

    @Test
    void resolveGuest_validRoomIdButNoGrant_returnsEmpty() {
        when(grantService.hasAccess(ROOM_ID, "abc")).thenReturn(false);

        assertThat(resolver.resolveGuest(ROOM_ID + ":abc")).isEmpty();
    }

    @Test
    void resolveGuest_missingSeparator_returnsEmpty() {
        assertThat(resolver.resolveGuest("not-a-composite-token")).isEmpty();
    }

    @Test
    void resolveGuest_nonUuidRoomId_returnsEmpty() {
        assertThat(resolver.resolveGuest("not-a-uuid:abc")).isEmpty();
    }

    @Test
    void resolveGuest_blankAccessTokenPart_returnsEmpty() {
        assertThat(resolver.resolveGuest(ROOM_ID + ":")).isEmpty();
    }

    @Test
    void resolveGuest_null_returnsEmpty() {
        assertThat(resolver.resolveGuest(null)).isEmpty();
    }

    @Test
    void resolveGuest_accessTokenContainingAColon_isPreservedInFull() {
        when(grantService.hasAccess(ROOM_ID, "a:b:c")).thenReturn(true);

        Optional<Principal> result = resolver.resolveGuest(ROOM_ID + ":a:b:c");

        assertThat(result).isPresent();
    }
}
