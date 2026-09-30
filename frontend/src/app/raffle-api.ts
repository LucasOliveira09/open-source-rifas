import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { forkJoin } from 'rxjs';

export type RaffleNumberStatus = 'AVAILABLE' | 'RESERVED' | 'PAID';

export interface RaffleNumber {
  number: number;
  status: RaffleNumberStatus;
}

export interface RafflePrize {
  id: number;
  title: string;
  description: string;
  imageUrl: string | null;
}

export interface RaffleDetails {
  id: number;
  title: string;
  description: string;
  unitPriceCents: number | null;
  currency: string;
  totalNumbers: number;
  availableNumbers: number;
  purchaseEnabled: boolean;
  prizes: RafflePrize[];
}

export interface PurchaseResponse {
  id: string;
  status: 'PENDING_PAYMENT' | 'PAID' | 'FAILED' | 'EXPIRED';
  numbers: number[];
  totalCents: number;
  expiresAt: string;
  pix: { copyPaste: string; qrCodeBase64: string | null; paymentUrl: string | null } | null;
}

@Injectable({ providedIn: 'root' })
export class RaffleApi {
  private readonly http = inject(HttpClient);

  loadRaffle() {
    return forkJoin({
      raffle: this.http.get<RaffleDetails>('/api/raffle'),
      numbers: this.http.get<RaffleNumber[]>('/api/raffle/numbers'),
    });
  }

  createPurchase(input: { name: string; email: string; phone: string; numbers: number[] }) {
    return this.http.post<PurchaseResponse>('/api/purchases', input);
  }

  getPurchase(id: string) {
    return this.http.get<PurchaseResponse>(`/api/purchases/${id}`);
  }
}
