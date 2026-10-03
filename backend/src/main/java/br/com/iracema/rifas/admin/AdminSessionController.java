package br.com.iracema.rifas.admin;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminSessionController {
    @GetMapping("/csrf")
    public ResponseEntity<Map<String, Boolean>> csrf(CsrfToken token) {
        token.getToken();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("ready", true));
    }

    @GetMapping("/session")
    public ResponseEntity<AdminSession> session(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new AdminSession(authentication.getName()));
    }

    public record AdminSession(String username) {}
}
