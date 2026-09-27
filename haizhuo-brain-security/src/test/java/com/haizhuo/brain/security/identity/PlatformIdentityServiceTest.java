package com.haizhuo.brain.security.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.security.identity.port.PlatformIdentityRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class PlatformIdentityServiceTest {
    @Mock
    private PlatformIdentityRepository repository;

    private PasswordEncoder encoder;
    private PlatformIdentityService service;
    private final UserId userId = new UserId(8);

    @BeforeEach
    void setUp() {
        encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        service = new PlatformIdentityService(repository, encoder,
                Clock.fixed(Instant.parse("2026-09-27T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void authenticatesUsingNormalizedMobileAndDatabaseRoles() {
        PlatformUser user = activeUser(4, false);
        when(repository.findByMobile("+8613812345678")).thenReturn(Optional.of(user));
        when(repository.findRoles(userId)).thenReturn(Set.of(PlatformRole.USER));

        AuthenticatedUser result = service.authenticate("138 1234 5678", "A-safe-password-123");

        assertEquals(userId, result.userId());
        assertTrue(result.hasRole(PlatformRole.USER));
        verify(repository).findByMobile("+8613812345678");
    }

    @Test
    void rejectsStaleSessionOrDisabledAccount() {
        when(repository.findById(userId)).thenReturn(Optional.of(activeUser(5, false)));
        assertThrows(AuthenticationRejectedException.class, () -> service.refresh(userId, 4));

        when(repository.findById(userId)).thenReturn(Optional.of(new PlatformUser(userId, "+8613812345678",
                encoder.encode("A-safe-password-123"), PlatformUserStatus.DISABLED, 5, false)));
        assertThrows(AuthenticationRejectedException.class, () -> service.refresh(userId, 5));
    }

    @Test
    void passwordChangeIncrementsVersionAndDoesNotAuditPasswordMaterial() {
        PlatformUser user = activeUser(4, true);
        when(repository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(repository.updatePassword(eq(userId), eq(4L), any(), eq(PlatformUserStatus.ACTIVE), eq(false), any())).thenReturn(true);
        when(repository.findRoles(userId)).thenReturn(Set.of(PlatformRole.USER));

        AuthenticatedUser result = service.changePassword(userId, "A-safe-password-123", "A-new-safe-password-456");

        assertEquals(5, result.authVersion());
        assertFalse(result.mustChangePassword());
        ArgumentCaptor<PlatformUserAudit> audit = ArgumentCaptor.forClass(PlatformUserAudit.class);
        verify(repository).appendAudit(audit.capture());
        assertFalse(audit.getValue().previousState().contains("password"));
        assertFalse(audit.getValue().newState().contains("A-new-safe-password-456"));
    }

    private PlatformUser activeUser(long authVersion, boolean mustChangePassword) {
        return new PlatformUser(userId, "+8613812345678", encoder.encode("A-safe-password-123"),
                PlatformUserStatus.ACTIVE, authVersion, mustChangePassword);
    }
}
