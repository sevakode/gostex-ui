# Artwork provenance before publication

The Android release check inspects images in `assets/media/` and `assets/art/`
in the **built APK**. It rejects bundled game images and requires an exact
SHA-256 match to a recorded inclusion decision for every screensaver image.
The record documents what is known about the image and why the project owner
chose to include it; it is not proof of rights in third-party works.

```json
{
  "path": "assets/art/example.png",
  "sha256": "<lowercase SHA-256 of the exact image bytes>",
  "inclusionBasis": "Accurate owner decision and known origin",
  "evidence": "Where provenance and that decision are recorded"
}
```

The three retained works are generated fan illustrations requested by the
owner. Fictional characters, worlds and trademarks belong to their respective
rights holders; the project does not assert a license from those holders. The
metadata identifies references and does not credit their owners as authors of
the generated images. A passing check verifies recorded bytes and provenance;
it does not determine legality, verify rights-holder consent or cover all
assets, fonts, descriptions, dependencies and private information.
