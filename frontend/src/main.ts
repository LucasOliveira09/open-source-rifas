import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { SiteShell } from './app/site-shell';

bootstrapApplication(SiteShell, appConfig)
  .catch((err) => console.error(err));
