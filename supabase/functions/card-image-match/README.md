# card-image-match

Receives a 1024-dimension MobileNetV3 Small embedding produced on the Android
device and returns the closest language-specific cards from Supabase pgvector.

The function deliberately does not run the neural network itself. This keeps
latency and Edge Function CPU use low and avoids uploading the camera image.

Deploy after applying the migration:

```bash
supabase link --project-ref gyxeddbvebnefasqxyiv
supabase db push
supabase functions deploy card-image-match
```

## Reference Image Coverage

Japanese scan accuracy depends on the rows in
`public.card_reference_embeddings`. See
`docs/japanese-reference-index.md` for the reference-image import workflow and
`supabase/seed/card_reference_seed_template.csv` for the seed manifest format.

The app can now expose a scan debug panel that shows the latest crop,
detection confidence, recognition path, and returned candidates. Use that data
to decide which Japanese cards need additional reference images.
