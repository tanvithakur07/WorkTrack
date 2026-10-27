package com.worktrack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.worktrack.entity.AccountToken;
import com.worktrack.entity.AuditOutcome;
import com.worktrack.entity.EmploymentStatus;
import com.worktrack.entity.Role;
import com.worktrack.entity.User;
import com.worktrack.exception.BadRequestException;
import com.worktrack.mail.EmailDeliveryException;
import com.worktrack.repository.AccountTokenRepository;
import com.worktrack.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-04T09:00:00Z");

    @Mock
    private AccountTokenRepository tokens;
    @Mock
    private UserRepository users;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuditService auditService;

    private AccountTokenService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new AccountTokenService(tokens, users, passwordEncoder, auditService,
                NotificationProperties.of(6, 60, 360, 120, 20), Clock.fixed(NOW, ZoneOffset.UTC), 72);
        user = new User();
        ReflectionTestUtils.setField(user, "id", 5L);
        user.setFullName("New Hire");
        user.setEmail("new@example.com");
        user.setPasswordHash("old-hash");
        user.setDepartment("Sales");
        user.setJobTitle("Rep");
        user.setJoiningDate(LocalDate.of(2026, 1, 5));
        user.setRole(Role.EMPLOYEE);
        user.setStatus(EmploymentStatus.ACTIVE);
        when(users.findById(5L)).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("an invitation link carries a long random secret after '#', and only its hash is stored")
    void issueStoresOnlyTheHash() {
        String link = service.issueInvitationLink(5L);

        assertThat(link).startsWith("https://worktrack.example.test/setup-password.html#token=");
        String secret = link.substring(link.indexOf("#token=") + "#token=".length());
        assertThat(secret).hasSizeGreaterThanOrEqualTo(40).matches("[A-Za-z0-9_-]+");
        ArgumentCaptor<AccountToken> saved = ArgumentCaptor.forClass(AccountToken.class);
        verify(tokens).save(saved.capture());
        assertThat(saved.getValue().getTokenHash()).isEqualTo(AccountTokenService.sha256(secret)).doesNotContain(secret);
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plusSeconds(72 * 3600L));
        assertThat(saved.getValue().getPurpose()).isEqualTo("INVITATION");
        verify(tokens).invalidateOpen(5L, "INVITATION", NOW); // older links stop working
    }

    @Test
    @DisplayName("two links for the same person are different")
    void linksAreUnique() {
        assertThat(service.issueInvitationLink(5L)).isNotEqualTo(service.issueInvitationLink(5L));
    }

    @Test
    @DisplayName("no link is made for an inactive or missing account; the failure is permanent so it is not retried")
    void noLinkForUnavailableAccount() {
        user.setStatus(EmploymentStatus.INACTIVE);
        assertThatThrownBy(() -> service.issueInvitationLink(5L))
                .isInstanceOfSatisfying(EmailDeliveryException.class, e -> assertThat(e.isPermanent()).isTrue());
        assertThatThrownBy(() -> service.issueInvitationLink(404L)).isInstanceOf(EmailDeliveryException.class);
        verify(tokens, never()).save(any());
    }

    @Test
    @DisplayName("a valid link sets the new password (hashed), and the audit record never contains it")
    void completeSetupSetsPassword() {
        String hash = AccountTokenService.sha256("the-secret");
        when(tokens.consume(hash, NOW)).thenReturn(1);
        when(tokens.findByTokenHash(hash)).thenReturn(Optional.of(new AccountToken(5L, hash, "INVITATION", NOW, NOW.plusSeconds(3600))));
        when(passwordEncoder.encode("Chosen-pass1")).thenReturn("new-hash");

        service.completeSetup("the-secret", "Chosen-pass1");

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        verify(users).saveAndFlush(user);
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq(AuditActions.PASSWORD_SETUP_COMPLETED), eq("User"), eq(5L), eq(AuditOutcome.SUCCESS), summary.capture());
        assertThat(summary.getValue()).doesNotContain("Chosen-pass1").doesNotContain("the-secret").doesNotContain("new-hash");
    }

    @Test
    @DisplayName("an unknown, used or expired link changes nothing and gives one generic message")
    void invalidLink() {
        when(tokens.consume(anyString(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.completeSetup("wrong", "Chosen-pass1"))
                .isInstanceOf(BadRequestException.class).hasMessage(AccountTokenService.INVALID);
        assertThatThrownBy(() -> service.completeSetup("  ", "Chosen-pass1")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.completeSetup(null, "Chosen-pass1")).isInstanceOf(BadRequestException.class);

        assertThat(user.getPasswordHash()).isEqualTo("old-hash");
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a link for an account that has since been deactivated is refused")
    void inactiveAccountCannotSetPassword() {
        String hash = AccountTokenService.sha256("the-secret");
        when(tokens.consume(hash, NOW)).thenReturn(1);
        when(tokens.findByTokenHash(hash)).thenReturn(Optional.of(new AccountToken(5L, hash, "INVITATION", NOW, NOW.plusSeconds(3600))));
        user.setStatus(EmploymentStatus.INACTIVE);

        assertThatThrownBy(() -> service.completeSetup("the-secret", "Chosen-pass1")).isInstanceOf(BadRequestException.class);
        assertThat(user.getPasswordHash()).isEqualTo("old-hash");
    }
}
