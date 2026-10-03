package br.com.iracema.rifas.admin;

import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/raffles")
public class AdminRaffleController {
    private final AdminRaffleStore store;

    public AdminRaffleController(AdminRaffleStore store) {
        this.store = store;
    }

    @GetMapping
    public ResponseEntity<List<AdminRaffleStore.RaffleSummary>> raffles() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.findRaffles());
    }

    @GetMapping("/{raffleId}/purchases")
    public ResponseEntity<List<AdminRaffleStore.PaidPurchase>> purchases(
            @PathVariable long raffleId, @RequestParam(required = false) Integer number) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.findPaidPurchases(raffleId, number));
    }

    @GetMapping("/{raffleId}/draws")
    public ResponseEntity<List<AdminRaffleStore.DrawResult>> draws(@PathVariable long raffleId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.findDraws(raffleId));
    }

    @PostMapping("/{raffleId}/draws")
    public ResponseEntity<AdminRaffleStore.DrawResult> draw(
            @PathVariable long raffleId, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(store.drawNext(raffleId, authentication.getName()));
    }
}
