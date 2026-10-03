package br.com.iracema.rifas.admin;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

@org.springframework.stereotype.Component
final class AdminLoginRateLimitFilter extends OncePerRequestFilter {
    private static final int MAX_FAILURES = 5;
    private static final long WINDOW_MILLIS = Duration.ofMinutes(15).toMillis();
    private final ConcurrentMap<String, FailureWindow> failures = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!"POST".equals(request.getMethod()) || !"/api/admin/login".equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String address = trustedClientAddress(request);
        long now = System.currentTimeMillis();
        FailureWindow current = failures.get(address);
        if (current != null && current.isBlocked(now)) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Muitas tentativas. Aguarde 15 minutos para tentar novamente.\"}");
            return;
        }
        request.setAttribute(AdminLoginRateLimitFilter.class.getName(), address);
        chain.doFilter(request, response);
    }

    void failed(HttpServletRequest request) {
        String address = (String) request.getAttribute(AdminLoginRateLimitFilter.class.getName());
        if (address != null) {
            long now = System.currentTimeMillis();
            failures.compute(address, (key, previous) -> previous == null || now - previous.startedAt() >= WINDOW_MILLIS
                    ? new FailureWindow(now, 1) : new FailureWindow(previous.startedAt(), previous.attempts() + 1));
            if (failures.size() > 4_096) {
                failures.entrySet().removeIf(entry -> now - entry.getValue().startedAt() >= WINDOW_MILLIS);
            }
        }
    }

    void succeeded(HttpServletRequest request) {
        String address = (String) request.getAttribute(AdminLoginRateLimitFilter.class.getName());
        if (address != null) {
            failures.remove(address);
        }
    }

    private static String trustedClientAddress(HttpServletRequest request) {
        String proxyAddress = request.getHeader("X-Real-IP");
        return proxyAddress == null || proxyAddress.isBlank() ? request.getRemoteAddr() : proxyAddress;
    }

    private record FailureWindow(long startedAt, int attempts) {
        boolean isBlocked(long now) {
            return now - startedAt < WINDOW_MILLIS && attempts >= MAX_FAILURES;
        }
    }
}
