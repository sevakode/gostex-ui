# Artwork review for the first public build

The 0.33.5 local candidate excludes all bundled game covers and screenshots
at the owner's request. It retains three generated screensaver illustrations,
also at the owner's request. Their references to fictional worlds belong to
their respective rights holders; generation provenance does not establish a
license for those references. The owner explicitly requested inclusion; the exact-byte manifest records that decision and the known provenance, without asserting rights-holder permission.

The previous 0.33.4-preview3 APK contained 140 image files under `assets/media/`
and `assets/art/`. The following is historical provenance, **not** confirmed
permission to redistribute the images:

| Images | Current evidence | Public release status |
| --- | --- | --- |
| 132 | `media-sources.json` attributes box scans and screenshots to libretro thumbnail repositories. | Rights to redistribute have not been established. |
| 2 | `cover-extensions.json` records AI widescreen adaptations of identified source covers, including original and generated image hashes. | Derivative-image and underlying artwork rights require review. |
| 3 | `psp-demo.png`, `n64-demo.png` and `dreamcast-demo.png` are used by demo metadata but have no image-source record identified in the repository. | Origin and rights require investigation. |
| 3 | The bundled world illustrations under `art/` have generation provenance and prompts in `design/world-art-prompts.md`. Their catalog links identify fictional-world references, not image authors or publication permission. | Generated-image and referenced-world rights require review. |

The local `.reports/release96/artwork-inventory.json` records the SHA-256 and
available provenance for each image in the 0.33.4-preview3 candidate. All 140
matched their current source files; all 134 records with a recorded image hash
also matched. This inventory describes that specific APK and is not a grant of
rights or a substitute for checking the final release APK.

The [upstream thumbnail README](https://github.com/libretro-thumbnails/libretro-thumbnails/blob/master/README.md)
identifies box art, screenshots and game artwork as coming from several sources
and from the respective developers and publishers. The existence of an upstream
image and a source URL does not itself establish permission to include that
image in this APK. The repository has a separate
[software license list](https://github.com/libretro/docs/blob/master/docs/development/licenses.md);
do not treat a license for RetroArch software or UI assets as a license for
individual game covers.

The current signed historical build also remains necessary for direct recovery
of some existing libraries. It already contains the old catalog and artwork;
removing images from a future APK does not remove them from that old release or
Git history. Any retirement or replacement of the historical release must be
coordinated with a tested migration; deleting it now could break recovery.

Available paths:

1. **Personal test first:** use the private local preview only with the owner,
   then complete the public rights review separately.
2. **Public build without unreviewed bundled art:** replace or omit such images,
   preserve existing user-selected covers and test the resulting offline and
   online experience before publishing. This needs implementation and testing.
3. **Public build with specific images:** establish and record a valid basis for
   each exact image, including required attribution. The digest-bound review
   manifest is described in `README.md`.

The automated check records exact-byte inclusion decisions and provenance. It does not make a legal determination, establish rights-holder permission or inspect all other resources.
