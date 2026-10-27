package com.worktrack.config;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

import com.worktrack.repository.UserRepository;
import com.worktrack.entity.AuditOutcome;
import com.worktrack.security.ActiveUserFilter;
import com.worktrack.security.JsonAccessDeniedHandler;
import com.worktrack.security.JsonAuthenticationEntryPoint;
import com.worktrack.security.SpaCsrfTokenRequestHandler;
import com.worktrack.security.WorkTrackUserDetails;
import com.worktrack.service.AuditActions;
import com.worktrack.service.AuditService;

/**
 * The security rules in one place:
 * - session cookie authentication (no tokens or passwords in browser storage)
 * - CSRF protection ON, using the XSRF-TOKEN cookie + X-XSRF-TOKEN header pair
 * - /api/** needs a signed-in user; role rules are added per endpoint with @PreAuthorize
 * - no CORS: the page and the API are served from the same origin
 * - static HTML/CSS/JS is public (it contains no data); every API call is still checked on the server
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self'",
            "img-src 'self' data:",
            "font-src 'self'",
            "connect-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository,
            JsonAuthenticationEntryPoint authenticationEntryPoint,
            JsonAccessDeniedHandler accessDeniedHandler,
            UserRepository userRepository,
            AuditService auditService,
            @Value("${server.servlet.session.cookie.name:JSESSIONID}") String sessionCookieName,
            @Value("${server.servlet.session.cookie.secure:true}") boolean secureCookies) throws Exception {

        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(cookie -> cookie.sameSite("Lax").secure(secureCookies));

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/setup-password").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        // Role rules at URL level too. They run before the request body is parsed and
                        // validated, so a non-admin always gets 403 (never a 400 that hints at the shape of
                        // an admin API). @PreAuthorize on the controllers stays as a second layer.
                        .requestMatchers(HttpMethod.GET, "/api/employees/{id:\\d+}").authenticated()
                        .requestMatchers("/api/employees", "/api/employees/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/leaves").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/leaves/*/approve", "/api/leaves/*/reject").hasRole("ADMIN")
                        .requestMatchers("/api/dashboard/admin", "/api/dashboard/admin/**").hasRole("ADMIN")
                        // Audit history and the e-mail outbox are for administrators only
                        .requestMatchers("/api/audit", "/api/audit/**", "/api/notifications", "/api/notifications/**")
                        .hasRole("ADMIN")
                        // Daily work reports: the all-employee list, team summary and missing-report list are
                        // admin only. "my" endpoints and single-entry calls need a session; ownership is
                        // enforced in WorkLogService (an employee can only read/edit/delete their own entries).
                        .requestMatchers(HttpMethod.GET, "/api/work-logs", "/api/work-logs/summary",
                                "/api/work-logs/missing", "/api/work-logs/export").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies(sessionCookieName)
                        .logoutSuccessHandler((request, response, authentication) -> {
                            // The actor comes from the session that is being ended, not from the request.
                            if (authentication != null && authentication.getPrincipal() instanceof WorkTrackUserDetails user) {
                                auditService.recordAs(user.getId(), user.getRole(), user.getUsername(), AuditActions.LOGOUT,
                                        "User", user.getId(), AuditOutcome.SUCCESS, "signed out");
                            }
                            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                        }))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                        // Spring adds X-Content-Type-Options: nosniff, Cache-Control: no-store and (on HTTPS
                        // requests) Strict-Transport-Security by default. These three are added explicitly:
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
                                "camera=(), microphone=(), geolocation=(), payment=(), usb=()"))
                        .addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Opener-Policy", "same-origin"))
                        .addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Resource-Policy", "same-origin")))
                // The front end and the API share one origin, so no cross-origin access is needed or allowed.
                .cors(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .addFilterBefore(new ActiveUserFilter(userRepository, authenticationEntryPoint),
                        AuthorizationFilter.class);

        return http.build();
    }
}
