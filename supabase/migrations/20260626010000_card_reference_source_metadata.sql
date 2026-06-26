alter table public.card_reference_embeddings
  add column if not exists source_url text,
  add column if not exists image_source_url text,
  add column if not exists price_url text,
  add column if not exists source_updated_at timestamptz;

create index if not exists card_reference_embeddings_source_url_idx
  on public.card_reference_embeddings (source_url)
  where source_url is not null;

create index if not exists card_reference_embeddings_price_source_idx
  on public.card_reference_embeddings (price_source)
  where price_source is not null;
