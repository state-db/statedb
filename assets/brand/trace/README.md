# Brand asset pipeline

The SVGs in `assets/brand/` (and the favicon + `BrandMark.tsx` in
`modules/web/`) are auto-traced from the original raster artwork
`assets/brand/statedb-logo.png`. Re-run this pipeline if the artwork changes;
don't edit the generated path data by hand.

Steps (crop on macOS with `sips`; everything else runs in the dev container):

```sh
# 1. Crop the regions to trace (offsets are y x; sizes are h w).
cp ../statedb-logo.png crop-mark.png && sips --cropOffset 330 80 -c 350 380 crop-mark.png
cp ../statedb-logo.png crop-full.png && sips --cropOffset 320 70 -c 370 1310 crop-full.png

# 2. Trace to flat-color SVG (quantize → flood-fill background → vectorize).
./bin/in-container bash -lc 'cd assets/brand/trace && bun install && \
  bun trace.ts crop-mark.png traced-mark.svg && \
  bun trace.ts crop-full.png traced-full.svg'

# 3. Derive the dark-background variants.
./bin/in-container bash -lc 'cd assets/brand/trace && \
  bun dark.ts mark traced-mark.svg traced-mark-dark.svg && \
  bun dark.ts full traced-full.svg traced-full-dark.svg'

# 4. Emit the final deliverables (brand SVGs, favicon, BrandMark.tsx).
./bin/in-container bash -lc 'cd assets/brand/trace && bun finalize.ts'

# 5. Regenerate the raster sizes (macOS).
qlmanage -t -s 512 -o . ../statedb-mark.svg && mv statedb-mark.svg.png ../statedb-mark-512.png
cp ../statedb-mark-512.png touch.png
sips --padToHeightWidth 560 560 --padColor FFFFFF touch.png
sips -z 180 180 touch.png --out ../../../modules/web/apple-touch-icon.png
```

The crop offsets, the face bounding box in `dark.ts`/`finalize.ts` (which
separates the otter's outline from its facial features), and the wordmark
x-threshold in `dark.ts` are tuned to the current artwork — re-check them
against `bun dark.ts debug <file>` output if the composition changes.

Intermediate files (`crop-*.png`, `traced-*.svg`) are scratch output; don't
commit them.
