package br.com.iracema.rifas.purchase;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PurchaseRequest(
		@NotBlank @Size(max = 160) String name,
		@NotBlank @Pattern(regexp = "[+()0-9 .-]{8,32}") String phone,
		@NotEmpty @Size(max = 100) List<@NotNull @Min(1) @Max(100) Integer> numbers) {
}
