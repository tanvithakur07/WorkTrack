package com.worktrack.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import com.worktrack.entity.LeaveRequest;
import com.worktrack.entity.LeaveStatus;
import com.worktrack.entity.LeaveType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Real concurrency against the real database: several threads send requests at the same instant
 * and the database must end up in a state the rules allow. These tests are not transactional on
 * purpose; a wrapping test transaction would hide exactly the behaviour being tested.
 */
class LeaveConcurrencyIntegrationTest extends IntegrationTestBase {

    private static final int THREADS = 4;

    @Test
    @DisplayName("simultaneous overlapping submissions: exactly one is created, the rest get 409")
    void concurrentOverlappingSubmissionsCreateOnlyOne() throws Exception {
        LocalDate monday = futureMonday();
        Map<String, Object> body = Map.of("leaveType", "CASUAL", "startDate", monday.toString(),
                "endDate", monday.plusDays(2).toString(), "reason", "Concurrent request");
        List<MockHttpSession> sessions = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            sessions.add(login(alice));
        }

        List<Integer> statuses = runTogether(sessions.stream()
                .<Callable<Integer>>map(session -> () -> mockMvc.perform(
                                post("/api/leaves").session(session)
                                        .with(csrfToken())
                                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                        .content(json(body)))
                        .andReturn().getResponse().getStatus())
                .toList());

        assertThat(statuses.stream().filter(code -> code == 201).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(code -> code == 409).count()).isEqualTo(THREADS - 1);
        assertThat(leaveRepository.findAll().stream()
                .filter(l -> l.getEmployee().getId().equals(alice.getId())).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("two admins approving two overlapping pending requests: only one can be approved")
    void concurrentApprovalsOfOverlappingRequestsApproveOnlyOne() throws Exception {
        LocalDate monday = futureMonday();
        // Written straight to the database so both overlapping requests exist as PENDING.
        LeaveRequest first = saveLeave(alice, LeaveType.CASUAL, monday, monday.plusDays(2));
        LeaveRequest second = saveLeave(alice, LeaveType.EARNED, monday.plusDays(1), monday.plusDays(3));
        MockHttpSession sessionOne = login(admin);
        MockHttpSession sessionTwo = login(admin);

        List<Integer> statuses = runTogether(List.of(
                () -> approve(first.getId(), sessionOne),
                () -> approve(second.getId(), sessionTwo)));

        assertThat(statuses.stream().filter(code -> code == 200).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(code -> code == 409).count()).isEqualTo(1);
        assertThat(leaveRepository.findAll().stream()
                .filter(l -> l.getStatus() == LeaveStatus.APPROVED).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("two admins deciding the same request at once: one approves, the other gets 409, no double review")
    void concurrentDecisionsOnTheSameRequest() throws Exception {
        LocalDate monday = futureMonday();
        LeaveRequest leave = saveLeave(alice, LeaveType.CASUAL, monday, monday.plusDays(2));
        MockHttpSession sessionOne = login(admin);
        MockHttpSession sessionTwo = login(admin);

        List<Integer> statuses = runTogether(List.of(
                () -> approve(leave.getId(), sessionOne),
                () -> approve(leave.getId(), sessionTwo)));

        assertThat(statuses.stream().filter(code -> code == 200).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(code -> code == 409).count()).isEqualTo(1);
        assertThat(leaveRepository.findById(leave.getId()).orElseThrow().getStatus()).isEqualTo(LeaveStatus.APPROVED);
    }

    private int approve(Long id, MockHttpSession session) throws Exception {
        return mockMvc.perform(withCsrf(patch("/api/leaves/" + id + "/approve")).session(session))
                .andReturn().getResponse().getStatus();
    }

    /** Starts every task at the same moment (a latch) and returns the results in submission order. */
    private static List<Integer> runTogether(List<Callable<Integer>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
