# PokeBinder Android

Android native app shell and scanner based on the reference videos. The existing
React PWA and Supabase database remain the collection service while the native app
focuses on fast, continuous camera recognition.

## Included

- CameraX live preview and frame analysis
- Native search, collection, favorites, scan, and settings tabs
- Collection-first launch screen instead of opening the camera at startup
- Full-screen scanner that releases the camera when closed
- 63:88 Pokemon card guide
- Automatic scan when the center image is bright and stable
- On-device MediaPipe MobileNetV3 image embedding
- Japanese, Korean, and English scan modes
- Supabase Edge Function image recognition client
- Match confidence, card metadata, and market price overlay
- Horizontal candidate list with manual correction
- Duplicate quantity merging and a running scan total
- Light, dark, and system theme controls

The collection shown in this milestone is the current in-memory scan session.
Supabase authentication and persistent portfolio synchronization are the next
native integration step.

## Open In Android Studio

Open this directory as a project:

```text
android-native
```

The project targets Android SDK 33 and requires Android 8.0 or newer.

## Local Configuration

Copy `local.properties.example` to `local.properties` and set:

```properties
sdk.dir=/path/to/Android/sdk
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_ANON_KEY=your-anon-key
CARD_RECOGNITION_FUNCTION=card-image-match
```

The app still opens and verifies camera stability without Supabase settings. Image
matching begins after the Edge Function is configured.

## Recognition Function Contract

The card image stays on the Android device. The app converts it to a normalized
1024-dimension embedding and sends:

```json
{
  "language": "ja",
  "embedding": [0.0123, -0.0456],
  "perceptualHash": "36193d71859b9839",
  "matchCount": 5,
  "minSimilarity": 0.55
}
```

The function may return the card object directly or inside a `card` property:

```json
{
  "card": {
    "id": "sv2a-006",
    "name": "リザードンex",
    "setName": "ポケモンカード151",
    "number": "006/165",
    "imageUrl": "https://example.com/card.png",
    "marketPrice": 1280,
    "currency": "JPY",
    "confidence": 0.94
  }
}
```

## Build The Reference Index

The Supabase migration, Edge Function, and TCGdex indexer are stored at the
repository root:

```text
supabase/migrations/20260614010000_card_image_embeddings.sql
supabase/functions/card-image-match
scripts/index_tcgdex_cards.py
scripts/test_card_match.py
```

After deploying Supabase, create a local Python 3.10 environment and index a
small Japanese test batch:

```bash
python3.10 -m venv .venv-card-index
.venv-card-index/bin/pip install -r scripts/requirements-card-index.txt

SUPABASE_URL=https://your-project.supabase.co \
SUPABASE_SERVICE_ROLE_KEY=your-service-role-key \
.venv-card-index/bin/python scripts/index_tcgdex_cards.py \
  --language ja \
  --limit 100 \
  --model android-native/app/src/main/assets/mobilenet_v3_small.tflite
```

The service role key is backend-only. Never place it in the Android app, PWA,
or a committed file.

Smoke-test the deployed function with a known TCGdex card:

```bash
SUPABASE_URL=https://your-project.supabase.co \
SUPABASE_ANON_KEY=your-anon-key \
.venv-card-index/bin/python scripts/test_card_match.py \
  --language ja \
  --card-id SVK-001 \
  --simulate-camera \
  --model android-native/app/src/main/assets/mobilenet_v3_small.tflite
```
