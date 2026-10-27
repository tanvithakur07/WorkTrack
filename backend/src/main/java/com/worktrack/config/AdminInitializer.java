package com.worktrack.config;

import java.time.LocalDate;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.worktrack.entity.EmploymentStatus;
import com.worktrack.entity.Role;
import com.worktrack.entity.User;
import com.worktrack.repository.UserRepository;

/**
 * Creates the first administrator on startup, from configuration, only when no ADMIN exists.
 *
 * There are no credentials in the source code: the email and password come from the environment
 * (WORKTRACK_ADMIN_EMAIL / WORKTRACK_ADMIN_PASSWORD). The password is BCrypt-hashed before it is
 * stored and is never logged. Once an admin exists these variables are ignored, so you can remove them.
 */
@Component
public class AdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);
    private static final int MIN_PASSWORD_LENGTH = 12;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminName;
    private final String adminEmail;
    private final String adminPassword;

    public AdminInitializer(UserRepository userRepository,
                            PasswordEncoder passwordEncoder,
                            @Value("${worktrack.admin.name:System Administrator}") String adminName,
                            @Value("${worktrack.admin.email:}") String adminEmail,
                            @Value("${worktrack.admin.password:}") String adminPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminName = adminName;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.existsByRole(Role.ADMIN)) {
            log.info("An administrator account already exists; WORKTRACK_ADMIN_* settings are ignored.");
            return;
        }
        if (!StringUtils.hasText(adminEmail) || !StringUtils.hasText(adminPassword)) {
            log.warn("No administrator exists yet. Set WORKTRACK_ADMIN_EMAIL and WORKTRACK_ADMIN_PASSWORD "
                    + "and restart to create the first one (see README).");
            return;
        }
        if (adminPassword.length() < MIN_PASSWORD_LENGTH || adminPassword.toLowerCase(Locale.ROOT).startsWith("change-me")) {
            throw new IllegalStateException("WORKTRACK_ADMIN_PASSWORD must be at least " + MIN_PASSWORD_LENGTH
                    + " characters and must not be the placeholder value from .env.example");
        }

        User admin = new User();
        admin.setFullName(adminName.trim());
        admin.setEmail(adminEmail.trim().toLowerCase(Locale.ROOT));
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        admin.setDepartment("Administration");
        admin.setJobTitle("Administrator");
        admin.setJoiningDate(LocalDate.now());
        admin.setRole(Role.ADMIN);
        admin.setStatus(EmploymentStatus.ACTIVE);
        userRepository.save(admin);
        log.info("Initial administrator created for {}", admin.getEmail());
    }
}
