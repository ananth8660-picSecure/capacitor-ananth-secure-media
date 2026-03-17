import { registerPlugin } from '@capacitor/core';
import type { AnanthSecureMediaPlugin } from './definitions';

const AnanthSecureMediaPlugin = registerPlugin<AnanthSecureMediaPlugin>(
  'AnanthSecureMedia',
  {
    web: () => import('./web').then(m => new m.AnanthSecureMediaPluginWeb()),
  }
);

export * from './definitions';
export { AnanthSecureMediaPlugin };