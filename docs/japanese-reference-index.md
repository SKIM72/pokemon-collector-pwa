# Japanese Card Reference Index

PokeBinder's Android scanner matches a camera crop against reference card image
embeddings stored in Supabase. The app creates the 1024-dimension MobileNetV3
embedding on device, then `card-image-match` searches `card_reference_embeddings`
with pgvector.

## Why This Exists

TCGdex is the best free source we have found for broad Japanese Pokemon card
metadata, but image and market coverage can still be incomplete. The reliable
long-term path is to keep our own Japanese reference index:

1. Collect Japanese card metadata and images from the Pokemon Card Japan catalog.
2. Keep source, image, and market URLs for auditability.
3. Generate image embeddings with the same on-device model used by the scanner.
4. Upsert those embeddings into Supabase.
5. Let `card-image-match` return the nearest candidates during live scan.

## Seed CSV

Use `scripts/index_pokemon_jp_cards.py` for the official Japanese catalog and
`supabase/seed/card_reference_seed_template.csv` as the hand-checkable
manifest for Japanese cards that need stronger scan coverage. One row should map
to one printed card image.

Required fields:

- `source`: usually `tcgdex`, `yuyu-tei`, or a project-specific importer name.
- `external_id`: stable ID from the source.
- `language`: `ja`, `ko`, or `en`.
- `name`, `set_id`, `set_name`, `card_number`: display/search identity.
- `image_url`: image used for embedding and app display.
- `price_url`: market page used for price refresh or manual audit.
- `market_price`, `currency`, `price_source`: optional price seed.

## Supabase Target

The table is `public.card_reference_embeddings`.

The 2026-06-26 migration adds these audit fields:

- `source_url`
- `image_source_url`
- `price_url`
- `source_updated_at`

These fields make it possible to compare scanner candidates against the exact
reference image and the exact market page that produced the price.

## Import Flow

The safest import flow is intentionally two-step:

1. Build or review the CSV manifest.
2. Run an Android-side or desktop-side embedding job with the same
   MobileNetV3 model as the app and upsert rows with service-role credentials.

Do not run neural-network inference inside Supabase Edge Functions for the main
path. Keeping inference on the device avoids camera image uploads and keeps the
free-tier Edge Function workload small.

## Official Japanese Import

The importer can index a named group first:

```bash
python scripts/index_pokemon_jp_cards.py \
  --model android-native/app/src/main/assets/mobilenet_v3_small.tflite \
  --query 'ピカチュウex' \
  --limit 30
```

Or process catalog pages incrementally:

```bash
python scripts/index_pokemon_jp_cards.py \
  --model android-native/app/src/main/assets/mobilenet_v3_small.tflite \
  --start-page 1 \
  --pages 10 \
  --limit 390
```

The production command requires `SUPABASE_URL` and
`SUPABASE_SERVICE_ROLE_KEY`. Never put the service-role key in the Android app
or commit it to Git.

## Next Implementation Step

Add an indexer screen or debug-only job in the Android app that:

1. downloads each manifest image,
2. creates a `CardImageEmbedder` fingerprint,
3. calls a service-role-protected import endpoint, and
4. reports failed rows for manual review.

That will let us strengthen Japanese scan matching without changing the scanner
UI each time a new card set needs coverage.
