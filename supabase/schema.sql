-- DailyDash POS schema template.
-- The live supabase2 project is already provisioned.
-- If deploying to another project, apply this as a Supabase migration.

create extension if not exists pgcrypto;

create table if not exists public.dailydash_products (
  id text primary key,
  name text not null,
  category text not null,
  price integer not null check (price >= 0),
  allow_upsize boolean not null default false,
  upsize_price integer not null default 10 check (upsize_price >= 0),
  available boolean not null default true,
  sort_order integer not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.dailydash_orders (
  id uuid primary key default gen_random_uuid(),
  order_no text not null unique,
  device_code text not null default 'ANDROID-POS',
  tender text not null check (tender in ('Cash','GCash','Maya')),
  subtotal integer not null check (subtotal >= 0),
  total integer not null check (total >= 0),
  cash_received integer not null default 0 check (cash_received >= 0),
  change_amount integer not null default 0 check (change_amount >= 0),
  status text not null default 'completed' check (status in ('completed','voided')),
  created_at timestamptz not null default now()
);

create table if not exists public.dailydash_order_items (
  id uuid primary key default gen_random_uuid(),
  order_id uuid not null references public.dailydash_orders(id) on delete cascade,
  product_id text references public.dailydash_products(id),
  product_name text not null,
  category text not null,
  quantity integer not null check (quantity > 0 and quantity <= 50),
  upsized boolean not null default false,
  unit_price integer not null check (unit_price >= 0),
  line_total integer not null check (line_total >= 0)
);

create table if not exists public.dailydash_settings (
  key text primary key,
  value text not null,
  updated_at timestamptz not null default now()
);

create table if not exists public.dailydash_admin_attempts (
  fingerprint text primary key,
  failures integer not null default 0,
  blocked_until timestamptz,
  updated_at timestamptz not null default now()
);

create index if not exists dailydash_orders_created_at_idx on public.dailydash_orders(created_at desc);
create index if not exists dailydash_order_items_order_id_idx on public.dailydash_order_items(order_id);
create index if not exists dailydash_order_items_product_id_idx on public.dailydash_order_items(product_id);
create index if not exists dailydash_products_category_idx on public.dailydash_products(category, sort_order);

alter table public.dailydash_products enable row level security;
alter table public.dailydash_orders enable row level security;
alter table public.dailydash_order_items enable row level security;
alter table public.dailydash_settings enable row level security;
alter table public.dailydash_admin_attempts enable row level security;

create policy "DailyDash public menu read"
on public.dailydash_products for select to anon, authenticated
using (available = true);

create policy "DailyDash no client orders"
on public.dailydash_orders for all to anon, authenticated using (false) with check (false);
create policy "DailyDash no client order items"
on public.dailydash_order_items for all to anon, authenticated using (false) with check (false);
create policy "DailyDash no client settings"
on public.dailydash_settings for all to anon, authenticated using (false) with check (false);
create policy "DailyDash no client admin attempts"
on public.dailydash_admin_attempts for all to anon, authenticated using (false) with check (false);

revoke all on public.dailydash_orders from anon, authenticated;
revoke all on public.dailydash_order_items from anon, authenticated;
revoke all on public.dailydash_settings from anon, authenticated;
revoke all on public.dailydash_admin_attempts from anon, authenticated;
grant select on public.dailydash_products to anon, authenticated;

insert into public.dailydash_settings(key,value) values
  ('store_name','DailyDash'),
  ('currency','PHP')
on conflict (key) do update set value=excluded.value, updated_at=now();

-- Set manager_pin_sha256 separately. Never commit the plain-text PIN.
-- insert into public.dailydash_settings(key,value)
-- values ('manager_pin_sha256','<SHA256_OF_6_DIGIT_PIN>')
-- on conflict (key) do update set value=excluded.value, updated_at=now();

create or replace function public.dailydash_create_order(
  p_items jsonb,
  p_tender text,
  p_cash_received integer default 0,
  p_device_code text default 'ANDROID-POS'
) returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_item jsonb;
  v_product public.dailydash_products%rowtype;
  v_order_id uuid := gen_random_uuid();
  v_order_no text;
  v_subtotal integer := 0;
  v_total integer := 0;
  v_cash integer := greatest(coalesce(p_cash_received,0),0);
  v_qty integer;
  v_upsized boolean;
  v_unit integer;
begin
  if p_items is null or jsonb_typeof(p_items) <> 'array' or jsonb_array_length(p_items)=0 then
    raise exception 'Cart is empty';
  end if;
  if jsonb_array_length(p_items)>50 then raise exception 'Too many cart lines'; end if;
  if p_tender not in ('Cash','GCash','Maya') then raise exception 'Unsupported tender'; end if;

  for v_item in select value from jsonb_array_elements(p_items) loop
    v_qty := greatest(least(coalesce((v_item->>'quantity')::integer,1),50),1);
    v_upsized := coalesce((v_item->>'upsized')::boolean,false);
    select * into v_product from public.dailydash_products where id=v_item->>'product_id' and available=true;
    if not found then raise exception 'Unavailable product: %', coalesce(v_item->>'product_id','unknown'); end if;
    if v_upsized and not v_product.allow_upsize then v_upsized := false; end if;
    v_unit := v_product.price + case when v_upsized then v_product.upsize_price else 0 end;
    v_subtotal := v_subtotal + (v_unit*v_qty);
  end loop;

  v_total := v_subtotal;
  if p_tender='Cash' and v_cash<v_total then raise exception 'Insufficient cash'; end if;
  if p_tender<>'Cash' then v_cash:=v_total; end if;
  v_order_no := 'DD-'||to_char(now(),'YYMMDD')||'-'||upper(substr(replace(v_order_id::text,'-',''),1,6));

  insert into public.dailydash_orders(id,order_no,device_code,tender,subtotal,total,cash_received,change_amount,status)
  values(v_order_id,v_order_no,coalesce(nullif(trim(p_device_code),''),'ANDROID-POS'),p_tender,v_subtotal,v_total,v_cash,greatest(v_cash-v_total,0),'completed');

  for v_item in select value from jsonb_array_elements(p_items) loop
    v_qty := greatest(least(coalesce((v_item->>'quantity')::integer,1),50),1);
    v_upsized := coalesce((v_item->>'upsized')::boolean,false);
    select * into v_product from public.dailydash_products where id=v_item->>'product_id' and available=true;
    if v_upsized and not v_product.allow_upsize then v_upsized := false; end if;
    v_unit := v_product.price + case when v_upsized then v_product.upsize_price else 0 end;
    insert into public.dailydash_order_items(order_id,product_id,product_name,category,quantity,upsized,unit_price,line_total)
    values(v_order_id,v_product.id,v_product.name,v_product.category,v_qty,v_upsized,v_unit,v_unit*v_qty);
  end loop;

  return jsonb_build_object(
    'id',v_order_id,'order_no',v_order_no,'subtotal',v_subtotal,'total',v_total,
    'cash_received',v_cash,'change_amount',greatest(v_cash-v_total,0),'tender',p_tender,'created_at',now()
  );
end;
$$;

revoke all on function public.dailydash_create_order(jsonb,text,integer,text) from public;
grant execute on function public.dailydash_create_order(jsonb,text,integer,text) to anon, authenticated;
