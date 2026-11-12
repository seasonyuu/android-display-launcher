# Display Launcher icon

Generated with the built-in image_gen tool (not CLI), 2026-11-12.

## Design and destinations

The two-screen/launch-arrow identity is retained, with monitor stands removed and a more compact silhouette.

- `google-play-icon.png`: final **Google Play listing** asset. 512 × 512, 8-bit RGBA / 32-bit PNG, sRGB, all alpha values 255, 282,676 bytes (under 1024 KB). Full square, opaque full-bleed background, no baked outer corner mask or drop shadow.
- `google-play-icon-source.png`: unmodified image_gen store-version source.
- `display-launcher-foreground.png`: unmodified transparent image_gen **Android adaptive icon** foreground; copied to `app/src/main/res/drawable-nodpi/ic_launcher_art.png`.
- Android uses a separate #123846 background, a 6 dp foreground inset, and standard/round adaptive-icon resources with a monochrome layer. Store assets and APK launcher layers are deliberately separate formats.

Specification: https://developer.android.com/distribute/google-play/resources/icon-design-specifications

## Reproduce the store export

The native export script only resizes and encodes generated artwork; it does not redesign the image.

```sh
xcrun swift scripts/export_play_icon.swift assets/branding/google-play-icon-source.png assets/branding/google-play-icon.png
```

## Foreground generation prompt

```text
Use case: logo-brand
Asset type: refined Android adaptive launcher foreground, version 2.
Input image: reference and edit target, existing Display Launcher icon.
Primary request: Refine this existing two-displays-and-launch-arrow identity into a much cleaner, more balanced professional product icon. Preserve its concept and its pale blue, white and cyan palette.
Change: remove both monitor stands, leaving just two overlapping rounded rectangular screen outlines. Use consistent bold stroke thickness, restrained corner radii, truly smooth precise edges, and no gradients or shading. The foreground screen is pale blue; the upper-right screen is white; a single solid cyan diagonal arrow leads toward the upper-right screen. Make the arrow slightly more compact and cleaner than the reference so all three shapes remain readable at small sizes.
Composition: one centered compact symbol on a square transparent canvas. The entire visible artwork must fit inside a central circle whose diameter is 60% of canvas width, with generous transparent margins. Balance the two screens optically.
Background: actual transparent alpha, screen interiors also transparent. No background tile, outer rounded-square boundary, shadow, text, letters, watermark, glow, noise, texture or decorative marks.
Output: one high-quality raster foreground only.
```

## Google Play generation prompt

```text
Use case: logo-brand
Asset type: Google Play store listing icon, matching the supplied Android launcher foreground.
Input image: exact identity reference/edit target.
Primary request: Produce the Google Play store version of this icon. Preserve exactly the two screen-outline shapes, their overlap, arrow geometry and pale-blue/white/cyan colors. Center the entire symbol optically, scaling it to occupy approximately 72 percent of the square canvas width.
Background: a solid opaque dark blue-teal #123846, full bleed to all four straight edges and all four square corners. The hollow interiors of the screens show that same solid background color.
Finish: perfectly clean smooth edges and flat fills, remove any stray speckles or fuzzy halo artifacts. Crisp, professional product icon readable at small sizes.
Constraints: single full-square icon; no rounded outer corners, no circular crop, no tile within a tile, no outer or internal shadows, no text, badges, ratings, watermark, textures or gradients. Do not change the identity of the supplied symbol. This is the raw store asset before Google Play applies its own rounded mask and shadow.
```
