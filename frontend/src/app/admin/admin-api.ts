import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface AdminRaffle {
  id: number;
  title: string;
  slug: string;
  status: string;
  unitPriceCents: number | null;
  totalNumbers: number;
  paidNumbers: number;
  reservedNumbers: number;
  drawCount: number;
}

export interface PaidPurchase {
  id: string;
  buyerName: string;
  buyerEmail: string;
  buyerPhone: string;
  numbers: string;
  paidAt: string;
}

export interface DrawResult {
  sequence: number;
  number: number;
  buyerName: string;
  drawnBy: string;
  drawnAt: string;
}

export interface AdminSession {
  username: string;
}

@Injectable({ providedIn: 'root' })
export class AdminApi {
  private readonly http = inject(HttpClient);
  private readonly root = '/api/admin';

  csrf(): Observable<{ ready: boolean }> {
    return this.http.get<{ ready: boolean }>(`${this.root}/csrf`);
  }

  session(): Observable<AdminSession> {
    return this.http.get<AdminSession>(`${this.root}/session`);
  }

  login(username: string, password: string): Observable<unknown> {
    const body = new HttpParams().set('username', username).set('password', password);
    return this.http.post(`${this.root}/login`, body, { responseType: 'text' });
  }

  logout(): Observable<void> {
    return this.http.post<void>(`${this.root}/logout`, {});
  }

  raffles(): Observable<AdminRaffle[]> {
    return this.http.get<AdminRaffle[]>(`${this.root}/raffles`);
  }

  purchases(raffleId: number, number?: number): Observable<PaidPurchase[]> {
    let params = new HttpParams();
    if (number !== undefined) params = params.set('number', number);
    return this.http.get<PaidPurchase[]>(`${this.root}/raffles/${raffleId}/purchases`, { params });
  }

  draws(raffleId: number): Observable<DrawResult[]> {
    return this.http.get<DrawResult[]>(`${this.root}/raffles/${raffleId}/draws`);
  }

  draw(raffleId: number): Observable<DrawResult> {
    return this.http.post<DrawResult>(`${this.root}/raffles/${raffleId}/draws`, {});
  }
}
