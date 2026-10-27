package com.worktrack.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Says plainly how e-mail is configured: once in the log at startup, and as a small read-only summary for the
 * administrators' Email queue page. It never contains the SMTP password, the user name, or any other secret;
 * only whether they are set.
 *
 * The point is that "log" mode (nothing is sent) can never be mistaken for working e-mail.
 */
@Component
public class MailStatus implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MailStatus.class);

    /** What the admin page shows. {@code mode} is "log" or "smtp"; realDelivery is true only for smtp. */
    public record Snapshot(String mode, boolean realDelivery, String host, int port, boolean usernameSet,
                           boolean passwordSet, String from, String baseUrl, boolean workerEnabled, List<String> warnings) {
    }

    private final String mode;
    private final String from;
    private final String host;
    private final int port;
    private final String username;
    private final boolean passwordSet;
    private final String baseUrl;
    private final boolean workerEnabled;

    public MailStatus(@Value("${worktrack.mail.mode:log}") String mode,
                      @Value("${worktrack.mail.from:}") String from,
                      @Value("${spring.mail.host:}") String host,
                      @Value("${spring.mail.port:587}") int port,
                      @Value("${spring.mail.username:}") String username,
                      @Value("${spring.mail.password:}") String password,
                      @Value("${worktrack.base-url:http://localhost:8080}") String baseUrl,
                      @Value("${worktrack.notifications.worker.enabled:true}") boolean workerEnabled) {
        this.mode = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        this.from = MailConfig.effectiveFrom(from, username);
        this.host = host == null ? "" : host.trim();
        this.port = port;
        this.username = username == null ? "" : username.trim();
        this.passwordSet = password != null && !password.isBlank();
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.workerEnabled = workerEnabled;
    }

    public Snapshot snapshot() {
        boolean smtp = mode.equals("smtp");
        List<String> warnings = new ArrayList<>();
        if (!smtp) {
            warnings.add("Mail mode is LOG: no e-mail is sent to anyone. Messages below marked 'log only' were NOT delivered. "
                    + "Set WORKTRACK_MAIL_MODE=smtp (and the MAIL_* settings) and restart to send real e-mail.");
        } else {
            if (username.isEmpty() || !passwordSet) {
                warnings.add("SMTP is on but MAIL_USERNAME or MAIL_PASSWORD is empty. Most providers (Gmail included) will refuse the login.");
            }
            if (baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1")) {
                warnings.add("WORKTRACK_BASE_URL still points to this computer, so links in e-mails only work on this computer.");
            }
            if (host.toLowerCase(Locale.ROOT).endsWith("gmail.com") && !username.isEmpty() && !from.equalsIgnoreCase(username)) {
                warnings.add("Gmail only sends as the signed-in account: WORKTRACK_MAIL_FROM should equal MAIL_USERNAME.");
            }
        }
        if (!workerEnabled) {
            warnings.add("The delivery worker is switched off (NOTIFICATIONS_WORKER_ENABLED=false): queued e-mails stay PENDING.");
        }
        return new Snapshot(smtp ? "smtp" : "log", smtp, host, port, !username.isEmpty(), passwordSet, from, baseUrl,
                workerEnabled, List.copyOf(warnings));
    }

    @Override
    public void run(ApplicationArguments args) {
        Snapshot s = snapshot();
        if (s.realDelivery()) {
            log.info("E-mail: SMTP mode. Server {}:{}, sender '{}', login {}, base URL {}. "
                            + "(Queued messages are sent by the background worker; 'sent' means the server accepted them.)",
                    s.host(), s.port(), s.from(), s.usernameSet() && s.passwordSet() ? "configured" : "NOT fully configured", s.baseUrl());
        } else {
            log.warn("E-MAIL IS IN LOG MODE: nothing is sent to anyone. To send real e-mail set WORKTRACK_MAIL_MODE=smtp "
                    + "and MAIL_HOST, MAIL_USERNAME, MAIL_PASSWORD, WORKTRACK_MAIL_FROM (see README), then restart.");
        }
        s.warnings().stream().filter(w -> s.realDelivery() || w.startsWith("The delivery worker"))
                .forEach(w -> log.warn("E-mail setup: {}", w));
    }
}
