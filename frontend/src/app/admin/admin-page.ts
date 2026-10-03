import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AdminApi, AdminRaffle, DrawResult, PaidPurchase } from './admin-api';

@Component({
  selector: 'app-admin-page',
  imports: [CurrencyPipe, DatePipe, DecimalPipe, ReactiveFormsModule, RouterLink],
  templateUrl: './admin-page.html',
  styleUrl: './admin-page.scss',
})
export class AdminPage implements OnInit {
  private readonly api = inject(AdminApi);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly authenticated = signal(false);
  protected readonly username = signal('');
  protected readonly raffles = signal<AdminRaffle[]>([]);
  protected readonly selectedRaffle = signal<AdminRaffle | null>(null);
  protected readonly purchases = signal<PaidPurchase[]>([]);
  protected readonly draws = signal<DrawResult[]>([]);
  protected readonly latestDraw = signal<DrawResult | null>(null);
  protected readonly loading = signal(true);
  protected readonly submitting = signal(false);
  protected readonly error = signal('');
  protected readonly notice = signal('');
  protected readonly loginForm = new FormGroup({
    username: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    password: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
  });
  protected readonly searchForm = new FormGroup({
    number: new FormControl('', { nonNullable: true, validators: [Validators.min(1), Validators.max(100), Validators.pattern(/^\d*$/)] }),
  });

  ngOnInit(): void {
    this.api.csrf().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.api.session().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: (session) => {
          this.authenticated.set(true);
          this.username.set(session.username);
          this.loadRaffles();
        },
        error: () => this.loading.set(false),
      }),
      error: () => {
        this.loading.set(false);
        this.error.set('Não foi possível preparar a sessão segura. Atualize a página para tentar novamente.');
      },
    });
  }

  protected signIn(): void {
    this.error.set('');
    this.notice.set('');
    if (this.loginForm.invalid) {
      this.loginForm.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    const { username, password } = this.loginForm.getRawValue();
    this.api.login(username.trim(), password).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.api.csrf().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: () => this.api.session().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
          next: (session) => {
            this.authenticated.set(true);
            this.username.set(session.username);
            this.loginForm.controls.password.reset('');
            this.loadRaffles();
          },
          error: () => this.failLogin('A sessão administrativa não pôde ser iniciada.'),
        }),
        error: () => this.failLogin('A sessão segura não pôde ser atualizada.'),
      }),
      error: (response: HttpErrorResponse) => this.failLogin(response.status === 429
        ? 'Muitas tentativas. Aguarde alguns minutos antes de tentar de novo.'
        : response.status === 401 ? 'Usuário ou senha inválidos.' : 'Não foi possível entrar. Tente novamente.'),
    });
  }

  protected signOut(): void {
    this.api.logout().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.authenticated.set(false);
        this.raffles.set([]);
        this.selectedRaffle.set(null);
        this.purchases.set([]);
        this.draws.set([]);
        this.latestDraw.set(null);
        this.loading.set(false);
        this.api.csrf().pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
      },
      error: () => this.error.set('Não foi possível encerrar a sessão. Tente novamente.'),
    });
  }

  protected chooseRaffle(raffle: AdminRaffle): void {
    this.selectedRaffle.set(raffle);
    this.latestDraw.set(null);
    this.searchForm.reset({ number: '' });
    this.notice.set('');
    this.loadRaffleData(raffle.id);
  }

  protected searchNumber(): void {
    const raffle = this.selectedRaffle();
    if (!raffle) return;
    if (this.searchForm.invalid) {
      this.searchForm.markAllAsTouched();
      return;
    }
    const raw = this.searchForm.controls.number.value.trim();
    this.api.purchases(raffle.id, raw ? Number(raw) : undefined).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (purchases) => {
        this.purchases.set(purchases);
        this.error.set('');
      },
      error: () => this.error.set('Não foi possível pesquisar os compradores desta rifa.'),
    });
  }

  protected drawNext(): void {
    const raffle = this.selectedRaffle();
    if (!raffle || this.submitting()) return;
    this.error.set('');
    this.notice.set('');
    this.submitting.set(true);
    this.api.draw(raffle.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (result) => {
        this.latestDraw.set(result);
        this.notice.set(`Número ${this.formatNumber(result.number)} sorteado para ${result.buyerName}.`);
        this.submitting.set(false);
        this.loadRaffles();
        this.loadRaffleData(raffle.id);
      },
      error: (response: HttpErrorResponse) => {
        this.submitting.set(false);
        this.error.set(response.status === 409
          ? 'Não há números pagos e ainda não sorteados para esta rifa.'
          : 'Não foi possível realizar o sorteio. Atualize os dados e tente novamente.');
      },
    });
  }

  protected formatNumber(number: number): string {
    return String(number).padStart(2, '0');
  }

  protected raffleStatus(status: string): string {
    return status === 'ACTIVE' ? 'Ativa' : 'Encerrada';
  }

  private failLogin(message: string): void {
    this.error.set(message);
    this.submitting.set(false);
  }

  private loadRaffles(): void {
    this.loading.set(true);
    this.api.raffles().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (raffles) => {
        this.raffles.set(raffles);
        const selected = raffles.find((raffle) => raffle.id === this.selectedRaffle()?.id) ?? raffles[0] ?? null;
        this.selectedRaffle.set(selected);
        this.loading.set(false);
        if (selected) this.loadRaffleData(selected.id);
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Não foi possível carregar as rifas. Verifique se o servidor está disponível.');
      },
    });
  }

  private loadRaffleData(raffleId: number): void {
    this.api.purchases(raffleId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (purchases) => this.purchases.set(purchases),
      error: () => this.error.set('Não foi possível carregar os compradores desta rifa.'),
    });
    this.api.draws(raffleId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (draws) => this.draws.set(draws),
      error: () => this.error.set('Não foi possível carregar o histórico de sorteios.'),
    });
  }
}
