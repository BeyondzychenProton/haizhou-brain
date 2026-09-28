package com.haizhuo.brain.security.identity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.security.identity.PlatformUserManagementService.UserDirectoryEntry;
import com.haizhuo.brain.security.identity.PlatformUserManagementService.UserDirectoryPage;
import com.haizhuo.brain.security.identity.port.PlatformIdentityRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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

    @Test
    void directoryEntryMasksMobileAndSortsRoles() {
        PlatformUser user = new PlatformUser(new UserId(7), "+8613800000001", "hashed-secret",
                PlatformUserStatus.ACTIVE, 3, false);
        when(repository.searchUsers("1380", PlatformUserStatus.ACTIVE, 20, 40)).thenReturn(List.of(user));
        when(repository.findRoles(List.of(user.id())))
                .thenReturn(Map.of(user.id(), Set.of(PlatformRole.USER, PlatformRole.PLATFORM_ADMIN)));
        when(repository.countUsers("1380", PlatformUserStatus.ACTIVE)).thenReturn(1L);

        UserDirectoryPage page = service.list("1380", PlatformUserStatus.ACTIVE, 20, 40);

        assertEquals(1, page.total());
        UserDirectoryEntry entry = page.content().get(0);
        assertEquals(7, entry.userId());
        assertEquals(PlatformUserStatus.ACTIVE, entry.status());
        assertEquals(List.of("PLATFORM_ADMIN", "USER"), entry.roles());
        // 名单必须继续脱敏：这里守住「完整手机号与密码散列都不得出现在管理端响应里」。
        assertEquals("+86****0001", entry.mobileMasked());
        assertFalse(entry.mobileMasked().contains("13800000001"));
    }

    @Test
    void negativeOffsetIsClampedInsteadOfReachingTheSqlDriver() {
        when(repository.searchUsers(null, null, 20, 0)).thenReturn(List.of());
        when(repository.findRoles(List.of())).thenReturn(Map.of());
        when(repository.countUsers(null, null)).thenReturn(0L);

        assertTrue(service.list(null, null, 20, -5).content().isEmpty());
        verify(repository).searchUsers(null, null, 20, 0);
    }

    private PlatformUser activeTarget() {
        return new PlatformUser(target, "+8613912345678", encoder.encode("A-safe-password-123"),
                PlatformUserStatus.ACTIVE, 7, false);
    }
}
