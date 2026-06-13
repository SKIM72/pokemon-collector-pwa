# PokeBinder

Personal Pokemon TCG collection PWA built with React + Vite.

## First Milestone

- React + Vite static app
- Supabase Auth and Postgres cloud storage
- TCGdex Japanese/Korean search
- Pokemon TCG API English search with TCGPlayer/Cardmarket price fields
- Hybrid price lookup pipeline: optional Supabase Edge Function for JP/KR local markets, then Pokemon TCG API fallback
- Unified multilingual search for Japanese, Korean, and English names
- Portfolio display currency switcher for JPY, KRW, and USD
- Price refresh, price history snapshots, and mini line charts
- Login, signup, password reset, profile, settings, logout
- Light, dark, and system theme modes
- Camera capture entry point for the future OCR/image matching pipeline
- Android native CameraX scanner MVP with continuous frame stability detection
- On-device MediaPipe image embeddings with Supabase pgvector candidate search
- JSON export/import backup

## Run Locally

```bash
npm install
npm run dev
```

Open the Vite local URL.

## Environment

Copy `.env.example` to `.env.local` and fill:

```bash
VITE_SUPABASE_URL=
VITE_SUPABASE_ANON_KEY=
VITE_PRICE_EDGE_FUNCTION=
```

`VITE_PRICE_EDGE_FUNCTION` is optional. Leave it empty to use Pokemon TCG API pricing only.
Later, set it to a Supabase Edge Function name such as `card-price-lookup` to prefer:

- Japanese cards: yuyu-tei market lookup
- Korean cards: TCGBOX market lookup
- All cards: Pokemon TCG API TCGPlayer/Cardmarket fallback when local lookup is unavailable

Expected Edge Function response:

```json
{
  "marketPrice": 1280,
  "currency": "JPY",
  "priceSource": "yuyutei",
  "priceFinish": "holo",
  "marketReferenceId": "https://example.com/card",
  "marketReferenceName": "リザードン",
  "updatedAtMarket": "2026-06-01T00:00:00.000Z"
}
```

## Deploy to GitHub Pages

This project includes `.github/workflows/deploy-pages.yml`.

1. Create a GitHub repository and push this project to the `main` branch.
2. In GitHub, open `Settings` -> `Secrets and variables` -> `Actions`.
3. Add repository secrets:
   - `VITE_SUPABASE_URL`
   - `VITE_SUPABASE_ANON_KEY`
4. Open `Settings` -> `Pages` and set the source to `GitHub Actions`.
5. Optional: add `VITE_PRICE_EDGE_FUNCTION` as a repository variable or secret after deploying the Edge Function.
6. Push to `main`, or run the `Deploy GitHub Pages` workflow manually.

After deployment, add the GitHub Pages URL to Supabase Auth redirect URLs:

```text
https://<github-user>.github.io/<repository-name>/
```

## Next Milestones

1. Expand the Japanese reference index beyond the initial validation batch.
2. Measure recognition quality with real camera photos and tune thresholds.
3. Add card rectangle/perspective correction before embedding generation.
4. Index Korean and English reference images.
5. Connect Android scan sessions directly to the existing portfolio tables.

## Android Native Scanner

The native CameraX MVP lives in [`android-native`](./android-native). It keeps the
PWA as the collection and settings experience while providing the continuous,
low-latency scanner shown in the reference videos.

The scanner uses the official MediaPipe MobileNetV3 Small image embedder on the
phone. Only the 1024-dimension embedding is sent to the `card-image-match`
Supabase Edge Function. Reference embeddings are generated from TCGdex images
with [`scripts/index_tcgdex_cards.py`](./scripts/index_tcgdex_cards.py) and
searched by cosine similarity in pgvector.

Apply and deploy the scanner backend:

```bash
supabase link --project-ref gyxeddbvebnefasqxyiv
supabase db push
supabase functions deploy card-image-match
```

For repeatable bulk indexing, the manual `Index card images` GitHub Actions
workflow accepts a language, card limit, and TCGdex start page. Add
`SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` as repository secrets before
running it.

## Data Sources

- TCGdex REST API: https://tcgdex.dev/rest/cards
- Pokemon TCG API: https://docs.pokemontcg.io/api-reference/cards/search-cards/
