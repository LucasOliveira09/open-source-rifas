import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { App } from './app';
import { appConfig } from './app.config';
import { PurchaseResponse, RaffleDetails, RaffleNumber } from './raffle-api';

describe('Compra de rifas', () => {
  let fixture: ComponentFixture<App>;
  let http: HttpTestingController;
  const raffle: RaffleDetails = {
    id: 1, title: 'Rifa da Escola Iracema', description: '', unitPriceCents: 500,
    currency: 'BRL', totalNumbers: 100, availableNumbers: 98, purchaseEnabled: true, prizes: [],
  };
  const numbers: RaffleNumber[] = Array.from({ length: 100 }, (_, index) => ({
    number: index + 1, status: index === 1 ? 'RESERVED' : index === 2 ? 'PAID' : 'AVAILABLE',
  }));
  const purchase: PurchaseResponse = {
    id: '38e73d9c-85b7-4e82-9dca-05a83d558c6a', status: 'PENDING_PAYMENT',
    numbers: [1], totalCents: 500, expiresAt: '2030-01-01T12:00:00Z',
    pix: { copyPaste: 'pix-de-teste', qrCodeBase64: 'cXItY29kZQ==', paymentUrl: null },
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App], providers: [...appConfig.providers, provideHttpClientTesting()],
    }).compileComponents();
    fixture = TestBed.createComponent(App);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  function flushRaffle(purchaseEnabled = true) {
    http.expectOne('/api/raffle').flush({ ...raffle, purchaseEnabled });
    http.expectOne('/api/raffle/numbers').flush(numbers);
    fixture.detectChanges();
  }

  function chooseAndFill() {
    (fixture.nativeElement.querySelector('.number-tile') as HTMLButtonElement).click();
    for (const [id, value] of Object.entries({
      'buyer-name': 'Comprador de teste', 'buyer-email': 'teste@example.com', 'buyer-phone': '11999999999',
    })) {
      const input = fixture.nativeElement.querySelector('#' + id) as HTMLInputElement;
      input.value = value;
      input.dispatchEvent(new Event('input', { bubbles: true }));
    }
    fixture.detectChanges();
  }

  function submit() {
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
  }

  it('mostra 100 números e o preço de R$ 5,00', () => {
    flushRaffle();
    expect(fixture.nativeElement.querySelectorAll('.number-tile').length).toBe(100);
    expect(fixture.nativeElement.querySelector('.prize-price').textContent).toContain('5,00');
    expect(fixture.nativeElement.querySelector('h1').textContent).toContain('Nossa rifa.');
  });

  it('bloqueia números reservados e vendidos e calcula o total da seleção', () => {
    flushRaffle();
    const buttons = fixture.nativeElement.querySelectorAll('.number-tile') as NodeListOf<HTMLButtonElement>;
    expect(buttons[1].disabled).toBe(true);
    expect(buttons[2].disabled).toBe(true);
    buttons[0].click();
    buttons[3].click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.selection-total').textContent).toContain('10,00');
  });

  it('não cria cobranças com dados inválidos', () => {
    flushRaffle();
    submit();
    http.expectNone('/api/purchases');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.field-error').textContent).toContain('Informe seu nome');
  });

  it('mantém o checkout bloqueado quando o Pix está sem configuração', () => {
    flushRaffle(false);
    chooseAndFill();
    expect(fixture.nativeElement.querySelector('.checkout-button').disabled).toBe(true);
    submit();
    http.expectNone('/api/purchases');
  });

  it('envia comprador e números e apresenta QR Code e Copia e Cola', () => {
    flushRaffle();
    chooseAndFill();
    submit();
    const request = http.expectOne('/api/purchases');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ name: 'Comprador de teste', email: 'teste@example.com', phone: '11999999999', numbers: [1] });
    request.flush(purchase);
    flushRaffle();
    http.expectOne('/api/purchases/' + purchase.id).flush(purchase);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#pix-copy').value).toBe('pix-de-teste');
    expect(fixture.nativeElement.querySelector('.pix-qr').getAttribute('src')).toContain('cXItY29kZQ==');
    expect(fixture.nativeElement.querySelector('.number-tile').disabled).toBe(true);
  });

  it('só apresenta aprovação após a API confirmar o pagamento', () => {
    flushRaffle();
    chooseAndFill();
    submit();
    http.expectOne('/api/purchases').flush(purchase);
    flushRaffle();
    http.expectOne('/api/purchases/' + purchase.id).flush({ ...purchase, status: 'PAID', pix: null });
    flushRaffle();
    expect(fixture.nativeElement.querySelector('#selection-title').textContent).toBe('Pagamento aprovado');
    expect(fixture.nativeElement.querySelector('.pix-qr')).toBeNull();
  });

  it('informa conflito de reserva e atualiza os números', () => {
    flushRaffle();
    chooseAndFill();
    submit();
    http.expectOne('/api/purchases').flush({ message: 'Número já reservado.' }, { status: 409, statusText: 'Conflict' });
    flushRaffle();
    expect(fixture.nativeElement.querySelector('.purchase-error').textContent).toContain('Número já reservado.');
  });
});
