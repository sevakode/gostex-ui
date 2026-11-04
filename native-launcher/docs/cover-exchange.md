# GOSTEX cover exchange · v1

Select a writable folder in **Settings → Media and library → Game covers → Shared cover folder**. The folder is a saved user choice. On this Shield the selected folder is `/storage/emulated/0/Download/GOSTEX-Covers`.

GOSTEX exports `library.json` and images in `originals/`. The export contains only games in the current imported library, not the bundled reference catalog, and excludes ROM paths, saves and credentials. Each row includes stable `id`, `title`, `platform`, `choice`, `source`, `original`, `current`, `wide`, `needsCover`, `hasWideCover` and `manualOverride`. Image references are relative to the selected folder; missing images are JSON null. `tileAspectRatio` comes from the actual tile layout. Existing built-in generated extensions are represented by `wide`. A manually selected online image may include `selectedCredit` with source, source-page URL and uploader. API keys are never exported.

The agent should read this export, preserve images with `manualOverride`, and generate only missing/unsuitable artwork. A portrait source by itself is not an error when a suitable `wide` variant already exists.

Write completed PNG/JPEG/WebP images to `incoming/` and publish `updates.json` atomically after the images are ready:

```json
{
  "version": 1,
  "covers": [
    {
      "gameId": "COPY_THE_EXPORTED_ID",
      "wide": "incoming/cover-HASH.webp",
      "wideSha256": "SHA256_OF_THE_COMPLETED_IMAGE"
    }
  ]
}
```

For a replacement original use `original` and `originalSha256`; both original and wide may be provided in the same row. Checksums are lowercase 64-character hexadecimal SHA-256. Use content-specific filenames and replace the manifest with a filesystem rename (`os.replace` in Python), not an in-place partial write. Keep source/credit/generation records alongside the results; no publisher attribution is inferred by GOSTEX.

Limits: 512 rows, 512 KiB manifest, unique known game IDs, at most four safe relative path components; no traversal, absolute paths, URIs or arbitrary file extensions. Each image is at most 10 MiB, 8192 pixels per side and 32 megapixels. PNG/JPEG/WebP content must decode successfully. Wide covers must match `tileAspectRatio` within 0.04. Private cover storage is capped at 128 MiB.

GOSTEX validates checksum and decoding, copies accepted images to immutable private storage, and commits its index atomically. Originals and the previous index are retained. A bad image leaves the previous entry intact. Unknown games and invalid entries are skipped. A malformed manifest is rejected while the current library export remains available. `manual` selection stays ahead of folder updates; the owner can select automatic, manual or original artwork separately for each game. The editor and per-game choices are available from a game’s hold menu and details.

When automatic import is enabled, GOSTEX checks the manifest on return to the launcher and every minute while the launcher is visible. The checks stop when it leaves the foreground. Unchanged manifests are skipped; failed entries can be retried. A manual import/export action is available in the same settings panel. The scheduling and image generation agent remains in Termux; this integration does not create or modify its cron jobs.

Disconnecting the folder stops exchange and retains already copied artwork. No root or direct access to GOSTEX private files is needed. The folder can be on removable storage if granted through Android’s folder picker.
