# ananth-secure-media

This media plugin for ionic Capacitor apps.Access device photos,videos,audio,and documents using Android MediaStore with camera capture,albums,thumbnails,pagination,duplication detection,and encrypted secure storage.

## Install

To use npm

```bash
npm install ananth-secure-media
````

To use yarn

```bash
yarn add ananth-secure-media
```

Sync native files

```bash
npx cap sync
```

## API

<docgen-index>

* [`requestMediaPermissions()`](#requestmediapermissions)
* [`openAppSettings()`](#openappsettings)
* [`getPhotos(...)`](#getphotos)
* [`getVideos(...)`](#getvideos)
* [`getAudio(...)`](#getaudio)
* [`getDocuments(...)`](#getdocuments)
* [`capturePhoto()`](#capturephoto)
* [`captureVideo()`](#capturevideo)
* [`encryptFile(...)`](#encryptfile)
* [`encryptMedia(...)`](#encryptmedia)
* [`decryptTemp(...)`](#decrypttemp)
* [`hideMedia(...)`](#hidemedia)
* [`getVaultPath()`](#getvaultpath)
* [`moveMediaToVault(...)`](#movemediatovault)
* [`enableSecureScreen(...)`](#enablesecurescreen)
* [`biometricAuth()`](#biometricauth)
* [`hideFolderFromGallery(...)`](#hidefolderfromgallery)
* [`setScreenshotProtection(...)`](#setscreenshotprotection)
* [`setScreenRecordingProtection(...)`](#setscreenrecordingprotection)
* [`setAutoLock(...)`](#setautolock)
* [`hideVaultFromGallery(...)`](#hidevaultfromgallery)
* [`setSecuritySettings(...)`](#setsecuritysettings)
* [Interfaces](#interfaces)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### requestMediaPermissions()

```typescript
requestMediaPermissions() => Promise<{ granted: boolean; }>
```

**Returns:** <code>Promise&lt;{ granted: boolean; }&gt;</code>

--------------------


### openAppSettings()

```typescript
openAppSettings() => Promise<{ opened: boolean; }>
```

**Returns:** <code>Promise&lt;{ opened: boolean; }&gt;</code>

--------------------


### getPhotos(...)

```typescript
getPhotos(options?: { limit?: number | undefined; offset?: number | undefined; } | undefined) => Promise<MediaResult>
```

| Param         | Type                                              |
| ------------- | ------------------------------------------------- |
| **`options`** | <code>{ limit?: number; offset?: number; }</code> |

**Returns:** <code>Promise&lt;<a href="#mediaresult">MediaResult</a>&gt;</code>

--------------------


### getVideos(...)

```typescript
getVideos(options?: { limit?: number | undefined; offset?: number | undefined; } | undefined) => Promise<MediaResult>
```

| Param         | Type                                              |
| ------------- | ------------------------------------------------- |
| **`options`** | <code>{ limit?: number; offset?: number; }</code> |

**Returns:** <code>Promise&lt;<a href="#mediaresult">MediaResult</a>&gt;</code>

--------------------


### getAudio(...)

```typescript
getAudio(options?: { limit?: number | undefined; offset?: number | undefined; } | undefined) => Promise<MediaResult>
```

| Param         | Type                                              |
| ------------- | ------------------------------------------------- |
| **`options`** | <code>{ limit?: number; offset?: number; }</code> |

**Returns:** <code>Promise&lt;<a href="#mediaresult">MediaResult</a>&gt;</code>

--------------------


### getDocuments(...)

```typescript
getDocuments(options?: { limit?: number | undefined; offset?: number | undefined; } | undefined) => Promise<MediaResult>
```

| Param         | Type                                              |
| ------------- | ------------------------------------------------- |
| **`options`** | <code>{ limit?: number; offset?: number; }</code> |

**Returns:** <code>Promise&lt;<a href="#mediaresult">MediaResult</a>&gt;</code>

--------------------


### capturePhoto()

```typescript
capturePhoto() => Promise<{ uri: string; }>
```

**Returns:** <code>Promise&lt;{ uri: string; }&gt;</code>

--------------------


### captureVideo()

```typescript
captureVideo() => Promise<{ uri: string; }>
```

**Returns:** <code>Promise&lt;{ uri: string; }&gt;</code>

--------------------


### encryptFile(...)

```typescript
encryptFile(options: { path: string; }) => Promise<{ path: string; }>
```

| Param         | Type                           |
| ------------- | ------------------------------ |
| **`options`** | <code>{ path: string; }</code> |

**Returns:** <code>Promise&lt;{ path: string; }&gt;</code>

--------------------


### encryptMedia(...)

```typescript
encryptMedia(options: { path: string; password: string; }) => Promise<{ encrypted: boolean; }>
```

| Param         | Type                                             |
| ------------- | ------------------------------------------------ |
| **`options`** | <code>{ path: string; password: string; }</code> |

**Returns:** <code>Promise&lt;{ encrypted: boolean; }&gt;</code>

--------------------


### decryptTemp(...)

```typescript
decryptTemp(options: { path: string; password: string; }) => Promise<{ path: string; }>
```

| Param         | Type                                             |
| ------------- | ------------------------------------------------ |
| **`options`** | <code>{ path: string; password: string; }</code> |

**Returns:** <code>Promise&lt;{ path: string; }&gt;</code>

--------------------


### hideMedia(...)

```typescript
hideMedia(options: { path: string; }) => Promise<{ success: boolean; }>
```

| Param         | Type                           |
| ------------- | ------------------------------ |
| **`options`** | <code>{ path: string; }</code> |

**Returns:** <code>Promise&lt;{ success: boolean; }&gt;</code>

--------------------


### getVaultPath()

```typescript
getVaultPath() => Promise<{ path: string; }>
```

**Returns:** <code>Promise&lt;{ path: string; }&gt;</code>

--------------------


### moveMediaToVault(...)

```typescript
moveMediaToVault(options: { source: string; }) => Promise<{ path: string; }>
```

| Param         | Type                             |
| ------------- | -------------------------------- |
| **`options`** | <code>{ source: string; }</code> |

**Returns:** <code>Promise&lt;{ path: string; }&gt;</code>

--------------------


### enableSecureScreen(...)

```typescript
enableSecureScreen(options?: { enabled?: boolean | undefined; } | undefined) => Promise<{ enabled: boolean; }>
```

| Param         | Type                                |
| ------------- | ----------------------------------- |
| **`options`** | <code>{ enabled?: boolean; }</code> |

**Returns:** <code>Promise&lt;{ enabled: boolean; }&gt;</code>

--------------------


### biometricAuth()

```typescript
biometricAuth() => Promise<{ success: boolean; }>
```

**Returns:** <code>Promise&lt;{ success: boolean; }&gt;</code>

--------------------


### hideFolderFromGallery(...)

```typescript
hideFolderFromGallery(options: { path: string; }) => Promise<{ hidden: boolean; }>
```

| Param         | Type                           |
| ------------- | ------------------------------ |
| **`options`** | <code>{ path: string; }</code> |

**Returns:** <code>Promise&lt;{ hidden: boolean; }&gt;</code>

--------------------


### setScreenshotProtection(...)

```typescript
setScreenshotProtection(options: { enabled: boolean; }) => Promise<{ enabled: boolean; }>
```

| Param         | Type                               |
| ------------- | ---------------------------------- |
| **`options`** | <code>{ enabled: boolean; }</code> |

**Returns:** <code>Promise&lt;{ enabled: boolean; }&gt;</code>

--------------------


### setScreenRecordingProtection(...)

```typescript
setScreenRecordingProtection(options: { enabled: boolean; }) => Promise<{ enabled: boolean; }>
```

| Param         | Type                               |
| ------------- | ---------------------------------- |
| **`options`** | <code>{ enabled: boolean; }</code> |

**Returns:** <code>Promise&lt;{ enabled: boolean; }&gt;</code>

--------------------


### setAutoLock(...)

```typescript
setAutoLock(options: { enabled: boolean; timeout?: number; }) => Promise<{ enabled: boolean; }>
```

| Param         | Type                                                 |
| ------------- | ---------------------------------------------------- |
| **`options`** | <code>{ enabled: boolean; timeout?: number; }</code> |

**Returns:** <code>Promise&lt;{ enabled: boolean; }&gt;</code>

--------------------


### hideVaultFromGallery(...)

```typescript
hideVaultFromGallery(options: { enabled: boolean; }) => Promise<{ enabled: boolean; }>
```

| Param         | Type                               |
| ------------- | ---------------------------------- |
| **`options`** | <code>{ enabled: boolean; }</code> |

**Returns:** <code>Promise&lt;{ enabled: boolean; }&gt;</code>

--------------------


### setSecuritySettings(...)

```typescript
setSecuritySettings(options: { antiScreenshot?: boolean; hideMedia?: boolean; autoLock?: boolean; }) => Promise<{ saved: boolean; }>
```

| Param         | Type                                                                                |
| ------------- | ----------------------------------------------------------------------------------- |
| **`options`** | <code>{ antiScreenshot?: boolean; hideMedia?: boolean; autoLock?: boolean; }</code> |

**Returns:** <code>Promise&lt;{ saved: boolean; }&gt;</code>

--------------------


### Interfaces


#### MediaResult

| Prop        | Type                     |
| ----------- | ------------------------ |
| **`items`** | <code>MediaItem[]</code> |


#### MediaItem

| Prop             | Type                |
| ---------------- | ------------------- |
| **`id`**         | <code>string</code> |
| **`filename`**   | <code>string</code> |
| **`name`**       | <code>string</code> |
| **`uri`**        | <code>string</code> |
| **`size`**       | <code>number</code> |
| **`createdAt`**  | <code>number</code> |
| **`album`**      | <code>string</code> |
| **`thumbnail`**  | <code>string</code> |
| **`path`**       | <code>string</code> |
| **`identifier`** | <code>string</code> |
| **`webPath`**    | <code>string</code> |

</docgen-api>
