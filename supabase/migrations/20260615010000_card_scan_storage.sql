insert into storage.buckets (
  id,
  name,
  public,
  file_size_limit,
  allowed_mime_types
)
values (
  'card-scans',
  'card-scans',
  false,
  2097152,
  array['image/jpeg']
)
on conflict (id) do update
set
  public = excluded.public,
  file_size_limit = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists "card_scans_select_own" on storage.objects;
create policy "card_scans_select_own"
on storage.objects
for select
to authenticated
using (
  bucket_id = 'card-scans'
  and (storage.foldername(name))[1] = auth.uid()::text
);

drop policy if exists "card_scans_insert_own" on storage.objects;
create policy "card_scans_insert_own"
on storage.objects
for insert
to authenticated
with check (
  bucket_id = 'card-scans'
  and (storage.foldername(name))[1] = auth.uid()::text
);

drop policy if exists "card_scans_update_own" on storage.objects;
create policy "card_scans_update_own"
on storage.objects
for update
to authenticated
using (
  bucket_id = 'card-scans'
  and (storage.foldername(name))[1] = auth.uid()::text
)
with check (
  bucket_id = 'card-scans'
  and (storage.foldername(name))[1] = auth.uid()::text
);

drop policy if exists "card_scans_delete_own" on storage.objects;
create policy "card_scans_delete_own"
on storage.objects
for delete
to authenticated
using (
  bucket_id = 'card-scans'
  and (storage.foldername(name))[1] = auth.uid()::text
);
