import { CurrencyPipe, DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { EMPTY, catchError, interval, startWith, switchMap, takeWhile } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { PurchaseResponse, RaffleApi, RaffleDetails, RaffleNumber, RaffleNumberStatus } from './raffle-api';

@Component({
  selector: 'app-root',
  imports: [CurrencyPipe, DatePipe, ReactiveFormsModule],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App implements OnInit {
  private readonly raffleApi = inject(RaffleApi);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly raffle = signal<RaffleDetails | null>(null);
  protected readonly numbers = signal<RaffleNumber[]>([]);
  protected readonly selectedNumbers = signal<Set<number>>(new Set());
  protected readonly isLoading = signal(true);
  protected readonly loadError = signal('');
  protected readonly purchase = signal<PurchaseResponse | null>(null);
  protected readonly purchaseError = signal('');
  protected readonly isSubmitting = signal(false);
  protected readonly selectedNumbersList = computed(() => [...this.selectedNumbers()].sort((a, b) => a - b));
  protected readonly totalCents = computed(() => {
    const priceCents = this.raffle()?.unitPriceCents;
    return priceCents === null || priceCents === undefined ? null : priceCents * this.selectedNumbers().size;
  });
  protected readonly buyerForm = new FormGroup({
    name: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(160)] }),
    phone: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.pattern(/^[+()0-9 .-]{8,32}$/)] }),
  });

  ngOnInit(): void {
    this.loadRaffle();
  }

  protected toggleNumber(number: RaffleNumber): void {
    if (number.status !== 'AVAILABLE') {
      return;
    }

    this.selectedNumbers.update((selected) => {
      const next = new Set(selected);
      next.has(number.number) ? next.delete(number.number) : next.add(number.number);
      return next;
    });
  }

  protected isSelected(number: number): boolean {
    return this.selectedNumbers().has(number);
  }

  protected statusLabel(status: RaffleNumberStatus): string {
    switch (status) {
      case 'AVAILABLE':
        return 'Disponível';
      case 'RESERVED':
        return 'Reservado';
      case 'PAID':
        return 'Vendido';
    }
  }

  protected clearSelection(): void {
    this.selectedNumbers.set(new Set());
  }

  protected continueShopping(): void {
    this.purchase.set(null);
    this.purchaseError.set('');
    this.clearSelection();
    this.reloadNumbers();
  }

  protected startPurchase(): void {
    const raffle = this.raffle();
    if (!raffle?.purchaseEnabled || this.selectedNumbersList().length === 0 || this.buyerForm.invalid || this.isSubmitting()) {
      this.buyerForm.markAllAsTouched();
      return;
    }

    this.isSubmitting.set(true);
    this.purchaseError.set('');
    const buyer = this.buyerForm.getRawValue();
    this.raffleApi.createPurchase({ ...buyer, numbers: this.selectedNumbersList() }).subscribe({
      next: (purchase) => {
        this.purchase.set(purchase);
        this.isSubmitting.set(false);
        this.reloadNumbers();
        this.watchPurchase(purchase.id);
      },
      error: (error: HttpErrorResponse) => {
        this.purchaseError.set(error.error?.message ?? 'Não foi possível iniciar o pagamento. Tente novamente.');
        this.isSubmitting.set(false);
        this.reloadNumbers();
      },
    });
  }

  protected copyPixCode(): void {
    const code = this.purchase()?.pix?.copyPaste;
    if (code) {
      navigator.clipboard.writeText(code).catch(() => this.purchaseError.set('Não foi possível copiar o código. Selecione e copie o texto.'));
    }
  }

  private watchPurchase(id: string): void {
    interval(5_000).pipe(
      startWith(0),
      switchMap(() => this.raffleApi.getPurchase(id).pipe(
        catchError(() => {
          this.purchaseError.set('Não foi possível atualizar o pagamento agora. A consulta será tentada novamente.');
          return EMPTY;
        }),
      )),
      takeWhile((purchase) => purchase.status === 'PENDING_PAYMENT', true),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe({
      next: (purchase) => {
        this.purchaseError.set('');
        this.purchase.set(purchase);
        if (purchase.status !== 'PENDING_PAYMENT') {
          this.reloadNumbers();
        }
      },
      error: () => this.purchaseError.set('Não foi possível atualizar o pagamento. O Pix continua válido até o vencimento.'),
    });
  }

  private reloadNumbers(): void {
    this.raffleApi.loadRaffle().subscribe({
      next: ({ raffle, numbers }) => {
        this.raffle.set(raffle);
        this.numbers.set(numbers);
      },
    });
  }

  protected loadRaffle(): void {
    this.isLoading.set(true);
    this.loadError.set('');

    this.raffleApi.loadRaffle().subscribe({
      next: ({ raffle, numbers }) => {
        this.raffle.set(raffle);
        this.numbers.set(numbers);
        this.isLoading.set(false);
      },
      error: (error: HttpErrorResponse) => {
        this.loadError.set(
          error.status === 0
            ? 'Não foi possível conectar à API. Inicie o banco e o backend para carregar a rifa.'
            : 'Não foi possível carregar os dados da rifa. Tente novamente.',
        );
        this.isLoading.set(false);
      },
    });
  }
}
