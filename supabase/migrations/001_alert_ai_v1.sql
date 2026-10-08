-- alert.ai V1 database
create extension if not exists pgcrypto;

create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  display_name text,
  email text,
  fcm_token text,
  latitude double precision,
  longitude double precision,
  last_seen timestamptz not null default now(),
  created_at timestamptz not null default now()
);

create table if not exists public.alerts (
  id uuid primary key default gen_random_uuid(),
  sender_id uuid not null references auth.users(id) on delete cascade,
  latitude double precision not null,
  longitude double precision not null,
  delivery_mode text not null default 'internet' check (delivery_mode in ('internet','nearby','hybrid')),
  created_at timestamptz not null default now()
);

create table if not exists public.alert_receipts (
  id uuid primary key default gen_random_uuid(),
  alert_id uuid not null references public.alerts(id) on delete cascade,
  receiver_id uuid not null references auth.users(id) on delete cascade,
  status text not null check (status in ('SENT','DELIVERED','ACKNOWLEDGED')),
  delivered_at timestamptz,
  acknowledged_at timestamptz,
  created_at timestamptz not null default now(),
  unique(alert_id, receiver_id)
);

create index if not exists profiles_location_idx
  on public.profiles(latitude, longitude);
create index if not exists alerts_sender_idx
  on public.alerts(sender_id, created_at desc);
create index if not exists receipts_alert_idx
  on public.alert_receipts(alert_id);

alter table public.profiles enable row level security;
alter table public.alerts enable row level security;
alter table public.alert_receipts enable row level security;

drop policy if exists profiles_select_own on public.profiles;
create policy profiles_select_own
on public.profiles for select
to authenticated
using (id = auth.uid());

drop policy if exists profiles_insert_own on public.profiles;
create policy profiles_insert_own
on public.profiles for insert
to authenticated
with check (id = auth.uid());

drop policy if exists profiles_update_own on public.profiles;
create policy profiles_update_own
on public.profiles for update
to authenticated
using (id = auth.uid())
with check (id = auth.uid());

drop policy if exists alerts_insert_own on public.alerts;
create policy alerts_insert_own
on public.alerts for insert
to authenticated
with check (sender_id = auth.uid());

drop policy if exists alerts_select_own on public.alerts;
create policy alerts_select_own
on public.alerts for select
to authenticated
using (sender_id = auth.uid());

drop policy if exists receipts_insert_own on public.alert_receipts;
create policy receipts_insert_own
on public.alert_receipts for insert
to authenticated
with check (receiver_id = auth.uid());

drop policy if exists receipts_update_own on public.alert_receipts;
create policy receipts_update_own
on public.alert_receipts for update
to authenticated
using (receiver_id = auth.uid())
with check (receiver_id = auth.uid());

drop policy if exists receipts_select_sender on public.alert_receipts;
create policy receipts_select_sender
on public.alert_receipts for select
to authenticated
using (
  receiver_id = auth.uid()
  or exists (
    select 1 from public.alerts a
    where a.id = alert_receipts.alert_id
      and a.sender_id = auth.uid()
  )
);

-- Enable Realtime for delivery acknowledgements.
do $$
begin
  alter publication supabase_realtime add table public.alert_receipts;
exception
  when duplicate_object then null;
end $$;

-- Keep profile rows synchronized for new auth users.
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer set search_path = public
as $$
begin
  insert into public.profiles(id, display_name, email)
  values (
    new.id,
    coalesce(new.raw_user_meta_data->>'display_name', new.raw_user_meta_data->>'name'),
    new.email
  )
  on conflict (id) do update
    set email = excluded.email,
        display_name = coalesce(excluded.display_name, public.profiles.display_name);
  return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
after insert on auth.users
for each row execute procedure public.handle_new_user();
