package br.com.iracema.rifas.purchase;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/purchases")
public class PurchaseController {

	private final PurchaseService purchaseService;

	public PurchaseController(PurchaseService purchaseService) {
		this.purchaseService = purchaseService;
	}

	@PostMapping
	public ResponseEntity<PurchaseResponse> create(@Valid @RequestBody PurchaseRequest request) {
		PurchaseResponse purchase = purchaseService.create(request);
		return ResponseEntity.created(URI.create("/api/purchases/" + purchase.id())).body(purchase);
	}

	@GetMapping("/{purchaseId}")
	public PurchaseResponse get(@PathVariable UUID purchaseId) {
		return purchaseService.get(purchaseId);
	}
}
