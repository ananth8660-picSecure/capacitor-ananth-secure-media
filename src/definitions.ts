// src/definitions.ts

export interface MediaOptions {
   quantity?: number; // plugin uses internally
   limit?: number;
   offset?: number;
   types?: 'photos' | 'videos' | 'audio' | 'documents' | 'all';
   albumIdentifier?: string;
}
export interface MediaItem {
   id: string;
   filename?: string;
   name: string;
   uri: string;
   size: number;
   createdAt: number;
   album?: string;
   thumbnail?: string;
   path: string;
   identifier: string;
   webPath: string;
}

export interface MediaResult {
   items: MediaItem[];
}

export interface AnanthSecureMediaPlugin {

   /* ---------------------------------------------
      Permissions
   --------------------------------------------- */

   requestMediaPermissions(): Promise<{ granted: boolean }>;

   openAppSettings(): Promise<{ opened: boolean }>;

   /* ---------------------------------------------
      Media fetch
   --------------------------------------------- */

   getPhotos(options?: { limit?: number; offset?: number }): Promise<MediaResult>;

   getVideos(options?: { limit?: number; offset?: number }): Promise<MediaResult>;

   getAudio(options?: { limit?: number; offset?: number }): Promise<MediaResult>;

   getDocuments(options?: { limit?: number; offset?: number }): Promise<MediaResult>;

   /* ---------------------------------------------
      Camera / Capture
   --------------------------------------------- */

   capturePhoto(): Promise<{ uri: string }>;

   captureVideo(): Promise<{ uri: string }>;

   /* ---------------------------------------------
      Encryption / Vault
   --------------------------------------------- */

   encryptFile(options: { path: string }): Promise<{ path: string }>;

   encryptMedia(options: { path: string; password: string }): Promise<{ encrypted: boolean }>;

   decryptTemp(options: { path: string; password: string }): Promise<{ path: string }>;

   hideMedia(options: { path: string }): Promise<{ success: boolean }>;

   getVaultPath(): Promise<{ path: string }>;

   moveMediaToVault(options: { source: string }): Promise<{ path: string }>;

   /* ---------------------------------------------
      Security
   --------------------------------------------- */

   enableSecureScreen(options?: { enabled?: boolean }): Promise<{ enabled: boolean }>;

   biometricAuth(): Promise<{ success: boolean }>;

   hideFolderFromGallery(options: { path: string }): Promise<{ hidden: boolean }>;

   /* ---------------------------------------------
      Advanced Security Controls
   --------------------------------------------- */

   setScreenshotProtection(options: { enabled: boolean }): Promise<{ enabled: boolean }>;

   setScreenRecordingProtection(options: { enabled: boolean }): Promise<{ enabled: boolean }>;

   setAutoLock(options: { enabled: boolean; timeout?: number }): Promise<{ enabled: boolean }>;

   hideVaultFromGallery(options: { enabled: boolean }): Promise<{ enabled: boolean }>;

   /* ---------------------------------------------
      Plugin Settings
   --------------------------------------------- */

   setSecuritySettings(options: {
      antiScreenshot?: boolean;
      hideMedia?: boolean;
      autoLock?: boolean;
   }): Promise<{ saved: boolean }>;
}