create extension if not exists vector with schema extensions;

create table if not exists public.card_reference_embeddings (
  id uuid primary key default gen_random_uuid(),
  source text not null,
  external_id text not null,
  language text not null check (language in ('ja', 'ko', 'en')),
  name text not null,
  set_id text,
  set_name text,
  card_number text,
  rarity text,
  image_url text not null,
  image_high_url text,
  market_price numeric(14, 2),
  currency text,
  price_source text,
  embedding extensions.vector(1024) not null,
  perceptual_hash text,
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (source, external_id, language)
);

create index if not exists card_reference_embeddings_language_idx
  on public.card_reference_embeddings (language);

create index if not exists card_reference_embeddings_cosine_idx
  on public.card_reference_embeddings
  using hnsw (embedding extensions.vector_cosine_ops);

create or replace function public.set_card_reference_updated_at()
returns trigger
language plpgsql
set search_path = public
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

drop trigger if exists card_reference_embeddings_updated_at
  on public.card_reference_embeddings;

create trigger card_reference_embeddings_updated_at
before update on public.card_reference_embeddings
for each row execute function public.set_card_reference_updated_at();

alter table public.card_reference_embeddings enable row level security;

revoke all on public.card_reference_embeddings from anon, authenticated;

create or replace function public.match_card_embeddings(
  query_embedding extensions.vector(1024),
  match_language text,
  match_count integer default 5,
  min_similarity double precision default 0.55
)
returns table (
  source text,
  external_id text,
  language text,
  name text,
  set_id text,
  set_name text,
  card_number text,
  rarity text,
  image_url text,
  image_high_url text,
  market_price numeric,
  currency text,
  price_source text,
  perceptual_hash text,
  metadata jsonb,
  similarity double precision
)
language sql
stable
security definer
set search_path = public, extensions
as $$
  select
    card.source,
    card.external_id,
    card.language,
    card.name,
    card.set_id,
    card.set_name,
    card.card_number,
    card.rarity,
    card.image_url,
    card.image_high_url,
    card.market_price,
    card.currency,
    card.price_source,
    card.perceptual_hash,
    card.metadata,
    1 - (card.embedding <=> query_embedding) as similarity
  from public.card_reference_embeddings as card
  where card.language = match_language
    and 1 - (card.embedding <=> query_embedding) >= min_similarity
  order by card.embedding <=> query_embedding
  limit least(greatest(match_count, 1), 12);
$$;

revoke all on function public.match_card_embeddings(
  extensions.vector(1024),
  text,
  integer,
  double precision
) from public, anon, authenticated;

grant execute on function public.match_card_embeddings(
  extensions.vector(1024),
  text,
  integer,
  double precision
) to service_role;
