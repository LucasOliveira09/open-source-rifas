import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', loadComponent: () => import('./app').then((module) => module.App) },
  { path: 'admin', loadComponent: () => import('./admin/admin-page').then((module) => module.AdminPage) },
  { path: '**', redirectTo: '' },
];
