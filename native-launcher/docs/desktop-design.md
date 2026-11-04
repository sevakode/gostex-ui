# GOSTEX Desktop: visual contract

The source is the owner's supplied `GOSTEX _ Design System.pdf` (eight-page overview in `.codex-attachments/dd3c9914-57d4-44b3-b660-6c28d853a8f2/a42b7ed5-68ef-41d8-9cb8-84865e85c984/`) and the previously captured `.reports/brandbook-reference.html` token sheet. The existing Android `gostex_logo.xml` was extracted from the original PDF by `design/extract_gostex_logo.py`; the desktop vector preserves its seven paths and fill rules.

User clarification, 2026-11-04: the desktop prototype must feel recognizably GOSTEX and pixel-oriented while remaining comfortable to read and navigate. The initial generic rounded prototype was a technical test, not the accepted visual direction.

Applied rules:

- Original custom wordmark, not an uppercase text substitute.
- Flat black/white/lime hierarchy: Ink `#0A0A0A`, Card `#141417`, raised Panel `#1F1F23`, Lime `#CCFF00`. Squared cards/buttons, thin outlines and stepped pixel focus corners. No soft card gradients.
- 4dp spacing rhythm and a faint static 22dp pixel grid. Pixel glyphs and numeric accents carry application type, order and selection; there is no decorative animation timer.
- Uppercase display/section headings, monospaced metadata and hints, normal readable body text. The supplied PDF names NAMU 1990, Inter and Pixelmix, but the original font files are not bundled in this checkout. Current system sans/mono and code-drawn pixel numbers are explicit fallbacks, not a claim that those exact fonts are installed.
- Cards retain a 16:9 visual area with a readable title below, matching the existing SHIELD layout. Focus is visible through line/corner/title changes in addition to color. Long titles/descriptions are bounded; full editable text remains in the right drawer.
- A controller uses the same card/drawer flow as keyboard input. The drawer contains preferences and management actions; Back restores the selected card, and the editor returns to its Add/Edit action.

The PDF is an overview/index. Numeric logo clear-space/minimum-size values are not specified on its overview pages; we do not invent them. This stage does not build a theme engine or change SHIELD's existing design/settings.

Visual evidence: `.reports/universal/desktop-library.png`, `desktop-settings.png`, and `desktop-brand-preview.png` are offscreen Compose renders. They verify layout without claiming native Mac/Steam Deck QA. Full Steam/game artwork and exact font assets remain later integration work.
