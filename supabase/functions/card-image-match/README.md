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
