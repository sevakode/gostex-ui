# GOSTEX screensaver Easter egg art

Historical 0.9.0 provenance. These two compositions were superseded by new world-specific pixel paintings after the user's feedback; see [world-art-prompts.md](world-art-prompts.md) for the current assets and exact prompts. The 0.9.0 outputs are now archived as `design/art-originals/aurora-hyrule-v09.png` and `design/art-originals/amber-droids-v09.png` and are not shipped. The project asset paths below describe the original 0.9.0 delivery, not their current pixel content.

Created 2026-11-02 using the built-in `image_gen` tool in **edit** mode (`precise-object-edit`). No CLI/API fallback used. Original paintings preserved in `design/art-originals` for provenance; only the new paintings with Easter eggs ship in the app. Captions are deliberately not baked into image pixels and belong in the localized application data/UI.

## Aurora / The Legend of Zelda

- Edit target: `/Users/seva/Documents/ChatGPT/shield/native-launcher/app/src/main/assets/art/aurora-lake.png`
- Original now archived at: `/Users/seva/Documents/ChatGPT/shield/native-launcher/design/art-originals/aurora-lake.png`
- Final project asset: `/Users/seva/Documents/ChatGPT/shield/native-launcher/app/src/main/assets/art/aurora-hyrule.png`
- Built-in output: `/Users/seva/.codex/generated_images/01a19f03-4986-7022-b4ad-9a8fd97e399c/exec-d507bd3d-8372-43e7-af66-03627f949701.png`
- Category: games; reference: The Legend of Zelda / Master Sword and Triforce.
- Visual QA: blue winged hilt, silver blade and gold Triforce are clearly visible on the foreground pedestal. Landscape, cabin and aurora composition preserved. No text or watermark.

Exact final prompt:

```text
Use case: precise-object-edit.
Asset type: 16:9 artwork for a large TV screensaver.
Input image: the provided aurora lake painting is the EDIT TARGET.
Primary request: preserve the beautiful landscape almost exactly, and add a tasteful, clearly recognizable Easter egg to The Legend of Zelda: the Master Sword, standing upright embedded in a weathered carved stone pedestal, with its iconic blue-violet wing-shaped crossguard and blue hilt, bright silver blade, and a small gold Triforce (three triangular pieces arranged as one larger triangle) engraving on the face of the pedestal.
Placement: on the near right foreground shoreline, below and slightly left of the cabin, among the existing rocks, without covering the cabin. The sword and pedestal together should occupy about 20% of image height so the reference is legible from a sofa on a TV, but remain secondary to the aurora landscape.
Match the existing painterly brushwork, perspective, turquoise aurora reflected light and warm cabin rim light. Ground the pedestal naturally among mossy rocks. The sword's silhouette, blue winged hilt, and gold triangle symbol must be easy to read.
Invariants: keep the wide composition, exact aspect ratio, mountains, aurora shapes, trees, cabin, lake and major reflections. Keep all other imagery unchanged. No added people, no other new objects, no text or lettering, no UI, no captions, no watermark. Preserve opaque image.
```

## Coast / Star Wars

- Edit target: `/Users/seva/Documents/ChatGPT/shield/native-launcher/app/src/main/assets/art/amber-coast.png`
- Original now archived at: `/Users/seva/Documents/ChatGPT/shield/native-launcher/design/art-originals/amber-coast.png`
- Final project asset: `/Users/seva/Documents/ChatGPT/shield/native-launcher/app/src/main/assets/art/amber-droids.png`
- Built-in output: `/Users/seva/.codex/generated_images/01a19f03-4986-7022-b4ad-9a8fd97e399c/exec-8775c220-a962-4364-8ceb-d5a845bb9ae9.png`
- Category: media; reference: Star Wars / R2-D2 and C-3PO.
- Visual QA: both recognizable droids fully visible on the foreground rock terrace, with matching sunset lighting and contact shadows. Landscape, lighthouse and sunset composition preserved. No text or watermark.

Exact final prompt:

```text
Use case: precise-object-edit.
Asset type: 16:9 artwork for a large TV screensaver.
Input image: the provided sunset coast painting is the EDIT TARGET.
Primary request: preserve the beautiful landscape almost exactly, and add a tasteful, clearly recognizable Star Wars Easter egg: R2-D2 and C-3PO standing quietly together on the near foreground rocky cliff on the right, seen in three-quarter rear/side view looking out across the sea toward the lighthouse. R2-D2 is the small white and blue cylindrical astromech with a silver domed head and stout feet; C-3PO is the tall slender gold humanoid protocol droid with recognizable mechanical body and head. Both must be fully visible and immediately recognizable, rendered in the same painterly style as the landscape.
Placement: on the existing foreground rock terrace in the right lower quarter, below and a little left of the lighthouse, leaving lighthouse, water and horizon unobstructed. C-3PO height about 20% of image height, R2-D2 half his height. They should be readable on a TV from a sofa while staying secondary to the sunset landscape.
Match the existing brushwork, warm orange sunset rim light, purple shadow, perspective and contact shadows. No glow, no dramatic action.
Invariants: preserve the exact wide aspect ratio and major composition, sun, sunset cloud formations, sea waves, distant islands, lighthouse and flowers. Keep all other imagery unchanged. No additional new figures or objects, no spacecraft, no text or lettering, no UI, no captions, no watermark. Preserve opaque image.
```

