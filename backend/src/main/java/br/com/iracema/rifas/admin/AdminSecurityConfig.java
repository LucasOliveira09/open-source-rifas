package br.com.iracema.rifas.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

@Configuration
public class AdminSecurityConfig {
    @Bean
    PasswordEncoder adminPasswordEncoder() {
        return new AdminPasswordEncoder();
    }

    @Bean
    FilterRegistrationBean<AdminLoginRateLimitFilter> disableAdminRateLimitContainerRegistration(
            AdminLoginRateLimitFilter filter) {
        FilterRegistrationBean<AdminLoginRateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    UserDetailsService adminUserDetailsService(
            @Value("${app.admin.username:}") String username,
            @Value("${app.admin.password-hash:}") String passwordHash) {
        if (username.isBlank() && passwordHash.isBlank()) {
            return new InMemoryUserDetailsManager();
        }
        if (username.isBlank() || !AdminPasswordEncoder.isEncodedPassword(passwordHash)) {
            throw new IllegalArgumentException("Configure ADMIN_USERNAME e um ADMIN_PASSWORD_HASH PBKDF2 válido.");
        }
        return new InMemoryUserDetailsManager(User.withUsername(username).password(passwordHash).roles("ADMIN").build());
    }

    @Bean
    SecurityFilterChain adminSecurityFilterChain(
            HttpSecurity http,
            UserDetailsService adminUserDetailsService,
            PasswordEncoder adminPasswordEncoder,
            AdminLoginRateLimitFilter loginRateLimitFilter,
            @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookies) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(cookie -> cookie.path("/").sameSite("Strict").secure(secureCookies));

        AuthenticationSuccessHandler loginSuccess = (request, response, authentication) -> {
            loginRateLimitFilter.succeeded(request);
            writeJson(response, 200, "{\"authenticated\":true}");
        };
        AuthenticationFailureHandler loginFailure = (request, response, exception) -> {
            loginRateLimitFilter.failed(request);
            writeJson(response, 401, "{\"message\":\"Usuário ou senha inválidos.\"}");
        };

        http
                .userDetailsService(adminUserDetailsService)
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/admin/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/admin/login").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().permitAll())
                .formLogin(form -> form
                        .loginProcessingUrl("/api/admin/login")
                        .successHandler(loginSuccess)
                        .failureHandler(loginFailure)
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/api/admin/logout")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                        (request, response, exception) -> writeJson(response, 401, "{\"message\":\"Acesse sua conta administrativa.\"}")))
                .requestCache(cache -> cache.disable())
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentSecurityPolicy(policy -> policy.policyDirectives(
                                "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; " +
                                "form-action 'self'; img-src 'self' data: https:; font-src 'self' data:; " +
                                "style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'")))
                .addFilterBefore(loginRateLimitFilter, org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void writeJson(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(body);
    }
}
