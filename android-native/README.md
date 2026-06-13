# PokeBinder Android Scanner

Android native scanner MVP based on the reference videos. The existing React PWA and
Supabase database remain the collection service, while this module focuses on fast,
continuous camera recognition.

## Included

- CameraX live preview and frame analysis
- 63:88 Pokemon card guide
- Automatic scan when the center image is bright and stable
- Japanese, Korean, and English scan modes
- Supabase Edge Function image recognition client
- Match confidence, card metadata, and market price overlay
- Duplicate quantity merging and a running scan total

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

The app sends:

```json
{
  "language": "ja",
  "imageBase64": "JPEG_BASE64"
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

The next backend milestone is to calculate an image embedding from the submitted
card crop, search a language-specific vector index, and enrich the best candidates
with TCGdex/Pokemon TCG API pricing.
