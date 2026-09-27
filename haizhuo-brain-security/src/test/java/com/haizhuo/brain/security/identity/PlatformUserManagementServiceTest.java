package com.haizhuo.brain.security.identity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class PlatformUserManagementServiceTest {
    @Mock
    private PlatformIdentityRepository repository;

    private PlatformUserManagementService service;
    private PasswordEncoder encoder;
    private final UserId administrator = new UserId(1);
    private final UserId target = new UserId(2);

    @BeforeEach
    void setUp() {
        encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        service = new PlatformUserManagementService(repository, encoder,
                Clock.fixed(Instant.parse("2026-09-27T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void refusesToDisableOrRevokeTheLastActiveAdministrator() {
        PlatformUser user = activeTarget();
        when(repository.findByIdForUpdate(target)).thenReturn(Optional.of(user));
        when(repository.findRoles(target)).thenReturn(Set.of(PlatformRole.PLATFORM_ADMIN, PlatformRole.USER));
        when(repository.countActiveAdministrators()).thenReturn(1L);

        assertThrows(IllegalStateException.class,
                () -> service.changeStatus(administrator, target, PlatformUserStatus.DISABLED, "离职停用"));
        assertThrows(IllegalStateException.class,
                () -> service.revokePlatformAdmin(administrator, target, "角色调整"));
        verify(repository, never()).revokeRole(target, PlatformRole.PLATFORM_ADMIN);
        verify(repository, never()).updateStatus(eq(target), eq(7L), any(), any());
    }

    @Test
    void activationConsumesOnlyMatchingUnusedCredential() {
        String secret = "activation-secret";
        ActivationToken token = new ActivationToken(10, target, "selector", encoder.encode(secret),
                Instant.parse("2026-09-28T00:00:00Z"), null, null);
        when(repository.findActivationBySelectorForUpdate("selector")).thenReturn(Optional.of(token));
        when(repository.consumeActivationToken(eq(10L), eq(target), any(), any())).thenReturn(true);

        assertDoesNotThrow(() -> service.activate("selector." + secret, "A-new-safe-password-456"));
        verify(repository).appendAudit(any(PlatformUserAudit.class));
    }

    @Test
    void rejectsRepeatedOfflineInitialization() {
        when(repository.countAdministratorRoles()).thenReturn(1L);

        assertThrows(IllegalStateException.class,
                () -> service.initializeFirstAdmin("13812345678", "A-safe-password-123"));
        verify(repository, never()).createUser(any(), any(), any(), anyBoolean(), any(), any());
    }

    private PlatformUser activeTarget() {
        return new PlatformUser(target, "+8613912345678", encoder.encode("A-safe-password-123"),
                PlatformUserStatus.ACTIVE, 7, false);
    }
}
