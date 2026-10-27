package com.worktrack.mysql;

import static org.assertj.core.api.Assertions.assertThat;

import com.worktrack.entity.Notification;
import com.worktrack.entity.NotificationType;
import com.worktrack.repository.NotificationRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The whole application on real MySQL: Flyway builds the schema, Hibernate then VALIDATES it against the
 * entities (so a migration that disagrees with an entity fails here), and the outbox claim is raced by
 * several threads. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class MySqlApplicationIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36").withDatabaseName("worktrack");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private NotificationRepository notifications;

    @Test
    @DisplayName("the application starts on MySQL: migrations applied and every entity matches its table (ddl-auto=validate)")
    void contextStarts() {
        assertThat(notifications.count()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("when many workers claim the same notification at once, exactly one wins")
    void concurrentClaimHasOneWinner() throws Exception {
        Notification saved = notifications.save(new Notification("a@example.com", NotificationType.LEAVE_APPROVED, 1L,
                "LEAVE_APPROVED:race-" + System.nanoTime(), "S", "<p>x</p>", "x", Instant.now().minusSeconds(5)));
        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    Instant now = Instant.now();
                    return notifications.claim(saved.getId(), now, now.plusSeconds(120));
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Integer> result : results) {
                winners += result.get(30, TimeUnit.SECONDS);
            }
            assertThat(winners).isEqualTo(1);
            assertThat(notifications.findById(saved.getId()).orElseThrow().getAttempts()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
