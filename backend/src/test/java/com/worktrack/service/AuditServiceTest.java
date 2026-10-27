package com.worktrack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.worktrack.entity.AuditEvent;
import com.worktrack.entity.AuditOutcome;
import com.worktrack.entity.Role;
import com.worktrack.exception.BadRequestException;
import com.worktrack.repository.AuditEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-04T09:00:00Z");

    @Mock
    private AuditEventRepository repository;
    @Mock
    private PlatformTransactionManager transactionManager;

    private AuditService service;

    @BeforeEach
    void setUp() {
        service = new AuditService(repository, Clock.fixed(NOW, ZoneOffset.UTC), transactionManager);
    }

    @Test
    @DisplayName("passwords, tokens and cookies written as name=value are replaced before storage")
    void secretsAreRedacted() {
        String cleaned = AuditService.clean("changed password=Hunter2! and token: abc123 cookie=JSESSIONID");
        assertThat(cleaned).doesNotContain("Hunter2!").doesNotContain("abc123").doesNotContain("JSESSIONID");
        assertThat(cleaned).contains("password=[redacted]").contains("token=[redacted]");
    }

    @Test
    @DisplayName("line breaks are flattened and long summaries are cut at 1000 characters")
    void lineBreaksAndLength() {
        assertThat(AuditService.clean("a\r\nb\nc")).isEqualTo("a b c");
        assertThat(AuditService.clean("x".repeat(5000))).hasSize(AuditService.MAX_SUMMARY);
        assertThat(AuditService.clean(null)).isNull();
    }

    @Test
    @DisplayName("a failed-login label is kept only when it looks like an e-mail, so a mistyped password is not stored")
    void loginLabel() {
        assertThat(AuditService.safeLoginLabel("  priya@example.com ")).isEqualTo("priya@example.com");
        assertThat(AuditService.safeLoginLabel("MyP@ssw0rd and more")).isEqualTo("[not an e-mail address]");
        assertThat(AuditService.safeLoginLabel("justapassword")).isEqualTo("[not an e-mail address]");
        assertThat(AuditService.safeLoginLabel("a@b".repeat(200))).isEqualTo("[not an e-mail address]");
        assertThat(AuditService.safeLoginLabel(null)).isNull();
    }

    @Test
    @DisplayName("recordAs writes one row with the given actor, action, outcome and the fixed clock time")
    void recordAsWritesRow() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        service.recordAs(7L, Role.ADMIN, "ada@example.com", AuditActions.LOGIN_SUCCESS, "User", 7L, AuditOutcome.SUCCESS, "ok");

        ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).saveAndFlush(saved.capture());
        AuditEvent event = saved.getValue();
        assertThat(event.getActorUserId()).isEqualTo(7L);
        assertThat(event.getActorRole()).isEqualTo("ADMIN");
        assertThat(event.getAction()).isEqualTo("LOGIN_SUCCESS");
        assertThat(event.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(event.getOccurredAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("if the audit insert fails, recordAs does not throw (the login or refusal must not become a 500)")
    void recordAsSwallowsFailure() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(repository.saveAndFlush(any())).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> service.recordAs(null, null, null, AuditActions.LOGIN_FAILURE, "User", null,
                AuditOutcome.FAILURE, "x")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("record() lets a repository failure propagate so the business change rolls back with it")
    void recordPropagatesFailure() {
        when(repository.save(any())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.record(AuditActions.LOGOUT, "User", 1L, AuditOutcome.SUCCESS, "x"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("search rejects a reversed date range and an unknown action before touching the database")
    void searchValidation() {
        assertThatThrownBy(() -> service.search(LocalDate.of(2026, 3, 5), LocalDate.of(2026, 3, 1), null, null, null, null,
                null, PageRequest.of(0, 20))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.search(null, null, null, "DROP_TABLE", null, null, null, PageRequest.of(0, 20)))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).findAll(any(org.springframework.data.jpa.domain.Specification.class), any(org.springframework.data.domain.Pageable.class));
    }
}
