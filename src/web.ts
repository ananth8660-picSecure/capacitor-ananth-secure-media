import { WebPlugin } from '@capacitor/core';
import type { AnanthSecureMediaPlugin } from './definitions';

export class AnanthSecureMediaPluginWeb
  extends WebPlugin
  implements AnanthSecureMediaPlugin {
  requestMediaPermissions(): Promise<{ granted: boolean; }> {
    throw new Error('Method not implemented.');
  }
  setSecuritySettings(_options: { antiScreenshot?: boolean; hideMedia?: boolean; autoLock?: boolean; }): Promise<{ saved: boolean; }> {
    throw new Error('Method not implemented.');
  }
  openAppSettings(): Promise<{ opened: boolean; }> {
    throw new Error('Method not implemented.');
  }
  hideFolderFromGallery(_options: { path: string; }): Promise<{ hidden: boolean; }> {
    throw new Error('Method not implemented.');
  }

  /* ---------------------------------------------
     Media fetch
  --------------------------------------------- */

  async getPhotos(): Promise<{ items: any[] }> {
    return { items: [] };
  }

  async getVideos(): Promise<{ items: any[] }> {
    return { items: [] };
  }

  async getAudio(): Promise<{ items: any[] }> {
    return { items: [] };
  }

  async getDocuments(): Promise<{ items: any[] }> {
    return { items: [] };
  }

  /* ---------------------------------------------
     Camera
  --------------------------------------------- */

  async capturePhoto(): Promise<{ uri: string }> {
    throw this.unimplemented('Camera capture not available on web');
  }

  async captureVideo(): Promise<{ uri: string }> {
    throw this.unimplemented('Video capture not available on web');
  }

  /* ---------------------------------------------
     Vault
  --------------------------------------------- */

  async encryptFile(): Promise<{ path: string }> {
    throw this.unimplemented('Encryption not available on web');
  }

  async encryptMedia(): Promise<{ encrypted: boolean }> {
    return { encrypted: false };
  }

  async decryptTemp(): Promise<{ path: string }> {
    throw this.unimplemented('Decryption not available on web');
  }

  async hideMedia(): Promise<{ success: boolean }> {
    return { success: false };
  }

  async getVaultPath(): Promise<{ path: string }> {
    return { path: '' };
  }

  async moveMediaToVault(): Promise<{ path: string }> {
    return { path: '' };
  }

  /* ---------------------------------------------
     Security
  --------------------------------------------- */

  async enableSecureScreen(options?: { enabled: boolean }): Promise<{ enabled: boolean }> {
    return { enabled: !!options?.enabled };
  }

  async biometricAuth(): Promise<{ success: boolean }> {
    return { success: false };
  }

  /* ---------------------------------------------
     Advanced Security
  --------------------------------------------- */

  async setScreenshotProtection(options: { enabled: boolean }): Promise<{ enabled: boolean }> {
    console.log('Screenshot protection', options);
    return { enabled: options.enabled };
  }

  async setScreenRecordingProtection(options: { enabled: boolean }): Promise<{ enabled: boolean }> {
    console.log('Recording protection', options);
    return { enabled: options.enabled };
  }

  async setAutoLock(options: { enabled: boolean; timeout: number }): Promise<{ enabled: boolean }> {
    console.log('Auto lock', options);
    return { enabled: options.enabled };
  }

  async hideVaultFromGallery(options: { enabled: boolean }): Promise<{ enabled: boolean }> {
    console.log('Hide vault', options);
    return { enabled: options.enabled };
  }

}