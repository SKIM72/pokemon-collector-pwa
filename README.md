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

1. Add OpenCV.js card rectangle detection and perspective crop.
2. Cache TCGdex card image embeddings for visual matching.
3. Add Supabase Edge Function adapters for yuyu-tei and TCGBOX.
4. Move pricing snapshots and multi-device sync into the OCI backend.
5. Wrap the PWA with Capacitor and add native ML Kit if Android accuracy becomes important.

## Data Sources

- TCGdex REST API: https://tcgdex.dev/rest/cards
- Pokemon TCG API: https://docs.pokemontcg.io/api-reference/cards/search-cards/
