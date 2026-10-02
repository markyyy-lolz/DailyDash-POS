-- DailyDash POS: inventory + reports
-- Applied to the live supabase2 project on 2026-10-02.

create table if not exists public.dailydash_inventory (
  product_id text primary key references public.dailydash_products(id) on delete cascade,
  stock_qty integer not null default 0 check (stock_qty >= 0),
  low_stock_level integer not null default 5 check (low_stock_level >= 0),
  track_stock boolean not null default false,
  updated_at timestamptz not null default now()
);

create table if not exists public.dailydash_stock_movements (
  id bigint generated always as identity primary key,
  product_id text not null references public.dailydash_products(id) on delete cascade,
  order_id uuid references public.dailydash_orders(id) on delete set null,
  quantity_change integer not null check (quantity_change <> 0),
  balance_after integer not null check (balance_after >= 0),
  reason text not null check (reason in ('manual_adjustment','sale','void_restock')),
  note text,
  created_at timestamptz not null default now()
);

create index if not exists dailydash_stock_movements_product_created_idx
  on public.dailydash_stock_movements(product_id, created_at desc);
create index if not exists dailydash_stock_movements_order_id_idx
  on public.dailydash_stock_movements(order_id);
create index if not exists dailydash_inventory_low_stock_idx
  on public.dailydash_inventory(track_stock, stock_qty, low_stock_level);

insert into public.dailydash_inventory(product_id, stock_qty, low_stock_level, track_stock)
select id, 0, 5, false
from public.dailydash_products
on conflict (product_id) do nothing;

alter table public.dailydash_inventory enable row level security;
alter table public.dailydash_stock_movements enable row level security;

drop policy if exists "DailyDash no client inventory" on public.dailydash_inventory;
create policy "DailyDash no client inventory"
on public.dailydash_inventory for all to anon, authenticated
using (false) with check (false);

drop policy if exists "DailyDash no client stock movements" on public.dailydash_stock_movements;
create policy "DailyDash no client stock movements"
on public.dailydash_stock_movements for all to anon, authenticated
using (false) with check (false);

revoke all on public.dailydash_inventory from anon, authenticated;
revoke all on public.dailydash_stock_movements from anon, authenticated;

create or replace function public.dailydash_get_pos_products()
returns table (
  id text,
  name text,
  category text,
  price integer,
  allow_upsize boolean,
  upsize_price integer,
  available boolean,
  sort_order integer
)
language sql
security definer
stable
set search_path = public
as $$
  select
    p.id,
    p.name,
    p.category,
    p.price,
    p.allow_upsize,
    p.upsize_price,
    case
      when coalesce(i.track_stock, false) then coalesce(i.stock_qty, 0) > 0
      else true
    end as available,
    p.sort_order
  from public.dailydash_products p
  left join public.dailydash_inventory i on i.product_id = p.id
  where p.available = true
  order by p.sort_order, p.name;
$$;

revoke all on function public.dailydash_get_pos_products() from public;
grant execute on function public.dailydash_get_pos_products() to anon, authenticated;

create or replace function public.dailydash_set_inventory(
  p_product_id text,
  p_stock_qty integer,
  p_low_stock_level integer default 5,
  p_track_stock boolean default true,
  p_note text default null
) returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_old integer;
  v_delta integer;
begin
  if p_stock_qty < 0 then raise exception 'Stock cannot be negative'; end if;
  if p_low_stock_level < 0 then raise exception 'Low-stock level cannot be negative'; end if;
  if not exists (select 1 from public.dailydash_products where id = p_product_id) then
    raise exception 'Product not found';
  end if;

  insert into public.dailydash_inventory(product_id, stock_qty, low_stock_level, track_stock)
  values (p_product_id, 0, p_low_stock_level, p_track_stock)
  on conflict (product_id) do nothing;

  select stock_qty into v_old
  from public.dailydash_inventory
  where product_id = p_product_id
  for update;

  v_delta := p_stock_qty - v_old;

  update public.dailydash_inventory
  set stock_qty = p_stock_qty,
      low_stock_level = p_low_stock_level,
      track_stock = p_track_stock,
      updated_at = now()
  where product_id = p_product_id;

  if v_delta <> 0 then
    insert into public.dailydash_stock_movements(
      product_id, quantity_change, balance_after, reason, note
    ) values (
      p_product_id, v_delta, p_stock_qty, 'manual_adjustment',
      nullif(trim(coalesce(p_note,'')), '')
    );
  end if;

  return jsonb_build_object(
    'product_id', p_product_id,
    'stock_qty', p_stock_qty,
    'low_stock_level', p_low_stock_level,
    'track_stock', p_track_stock
  );
end;
$$;

revoke all on function public.dailydash_set_inventory(text,integer,integer,boolean,text) from public;
grant execute on function public.dailydash_set_inventory(text,integer,integer,boolean,text) to service_role;

create or replace function public.dailydash_void_order(p_order_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_order public.dailydash_orders%rowtype;
  v_move record;
  v_balance integer;
begin
  select * into v_order
  from public.dailydash_orders
  where id = p_order_id
  for update;

  if not found then raise exception 'Order not found'; end if;
  if v_order.status <> 'completed' then
    raise exception 'Only completed orders can be voided';
  end if;

  update public.dailydash_orders set status = 'voided' where id = p_order_id;

  for v_move in
    select product_id, sum(-quantity_change)::integer as restore_qty
    from public.dailydash_stock_movements
    where order_id = p_order_id and reason = 'sale'
    group by product_id
  loop
    update public.dailydash_inventory
    set stock_qty = stock_qty + v_move.restore_qty,
        updated_at = now()
    where product_id = v_move.product_id
    returning stock_qty into v_balance;

    if found and v_move.restore_qty > 0 then
      insert into public.dailydash_stock_movements(
        product_id, order_id, quantity_change, balance_after, reason, note
      ) values (
        v_move.product_id, p_order_id, v_move.restore_qty, v_balance,
        'void_restock', 'Automatic restock from voided order ' || v_order.order_no
      );
    end if;
  end loop;

  return jsonb_build_object('id', v_order.id, 'order_no', v_order.order_no, 'status', 'voided');
end;
$$;

revoke all on function public.dailydash_void_order(uuid) from public;
grant execute on function public.dailydash_void_order(uuid) to service_role;

create or replace function public.dailydash_report(p_start timestamptz, p_end timestamptz)
returns jsonb
language plpgsql
security definer
stable
set search_path = public
as $$
declare
  v_sales bigint := 0;
  v_orders bigint := 0;
  v_items bigint := 0;
  v_voided bigint := 0;
  v_tenders jsonb;
  v_products jsonb;
  v_categories jsonb;
  v_daily jsonb;
begin
  if p_end <= p_start then raise exception 'Report end must be after start'; end if;

  select coalesce(sum(total),0), count(*)
  into v_sales, v_orders
  from public.dailydash_orders
  where status='completed' and created_at>=p_start and created_at<p_end;

  select count(*) into v_voided
  from public.dailydash_orders
  where status='voided' and created_at>=p_start and created_at<p_end;

  select coalesce(sum(i.quantity),0) into v_items
  from public.dailydash_order_items i
  join public.dailydash_orders o on o.id=i.order_id
  where o.status='completed' and o.created_at>=p_start and o.created_at<p_end;

  select coalesce(jsonb_agg(to_jsonb(x) order by x.total desc),'[]'::jsonb)
  into v_tenders
  from (
    select tender, count(*)::integer as orders, sum(total)::bigint as total
    from public.dailydash_orders
    where status='completed' and created_at>=p_start and created_at<p_end
    group by tender
  ) x;

  select coalesce(jsonb_agg(to_jsonb(x) order by x.revenue desc, x.quantity desc),'[]'::jsonb)
  into v_products
  from (
    select i.product_id, i.product_name, i.category,
      sum(i.quantity)::bigint as quantity,
      sum(i.line_total)::bigint as revenue
    from public.dailydash_order_items i
    join public.dailydash_orders o on o.id=i.order_id
    where o.status='completed' and o.created_at>=p_start and o.created_at<p_end
    group by i.product_id, i.product_name, i.category
    order by revenue desc, quantity desc
    limit 20
  ) x;

  select coalesce(jsonb_agg(to_jsonb(x) order by x.revenue desc),'[]'::jsonb)
  into v_categories
  from (
    select i.category,
      sum(i.quantity)::bigint as quantity,
      sum(i.line_total)::bigint as revenue
    from public.dailydash_order_items i
    join public.dailydash_orders o on o.id=i.order_id
    where o.status='completed' and o.created_at>=p_start and o.created_at<p_end
    group by i.category
  ) x;

  select coalesce(jsonb_agg(to_jsonb(x) order by x.day),'[]'::jsonb)
  into v_daily
  from (
    select (created_at at time zone 'Asia/Manila')::date as day,
      count(*)::integer as orders,
      sum(total)::bigint as sales
    from public.dailydash_orders
    where status='completed' and created_at>=p_start and created_at<p_end
    group by 1
  ) x;

  return jsonb_build_object(
    'start',p_start,'end',p_end,'sales',v_sales,'orders',v_orders,
    'average_order',case when v_orders>0 then round(v_sales::numeric/v_orders)::bigint else 0 end,
    'items_sold',v_items,'voided_orders',v_voided,
    'tenders',v_tenders,'top_products',v_products,'categories',v_categories,'daily',v_daily
  );
end;
$$;

revoke all on function public.dailydash_report(timestamptz,timestamptz) from public;
grant execute on function public.dailydash_report(timestamptz,timestamptz) to service_role;

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
  v_inventory public.dailydash_inventory%rowtype;
  v_required record;
  v_order_id uuid := gen_random_uuid();
  v_order_no text;
  v_subtotal integer := 0;
  v_total integer := 0;
  v_cash integer := greatest(coalesce(p_cash_received,0),0);
  v_qty integer;
  v_upsized boolean;
  v_unit integer;
  v_balance integer;
begin
  if p_items is null or jsonb_typeof(p_items)<>'array' or jsonb_array_length(p_items)=0 then
    raise exception 'Cart is empty';
  end if;
  if jsonb_array_length(p_items)>50 then raise exception 'Too many cart lines'; end if;
  if p_tender not in ('Cash','GCash','Maya') then raise exception 'Unsupported tender'; end if;

  for v_required in
    select item->>'product_id' as product_id,
      sum(greatest(least(coalesce((item->>'quantity')::integer,1),50),1))::integer as required_qty
    from jsonb_array_elements(p_items) item
    group by item->>'product_id'
  loop
    select * into v_inventory
    from public.dailydash_inventory
    where product_id=v_required.product_id
    for update;

    if found and v_inventory.track_stock and v_inventory.stock_qty<v_required.required_qty then
      select * into v_product from public.dailydash_products where id=v_required.product_id;
      raise exception 'Insufficient stock for % (available: %, requested: %)',
        coalesce(v_product.name,v_required.product_id),
        v_inventory.stock_qty,
        v_required.required_qty;
    end if;
  end loop;

  for v_item in select value from jsonb_array_elements(p_items)
  loop
    v_qty := greatest(least(coalesce((v_item->>'quantity')::integer,1),50),1);
    v_upsized := coalesce((v_item->>'upsized')::boolean,false);

    select * into v_product
    from public.dailydash_products
    where id=v_item->>'product_id' and available=true;

    if not found then raise exception 'Unavailable product: %', coalesce(v_item->>'product_id','unknown'); end if;
    if v_upsized and not v_product.allow_upsize then v_upsized:=false; end if;

    v_unit := v_product.price + case when v_upsized then v_product.upsize_price else 0 end;
    v_subtotal := v_subtotal + (v_unit*v_qty);
  end loop;

  v_total := v_subtotal;
  if p_tender='Cash' and v_cash<v_total then raise exception 'Insufficient cash'; end if;
  if p_tender<>'Cash' then v_cash:=v_total; end if;

  v_order_no := 'DD-'||to_char(now(),'YYMMDD')||'-'||upper(substr(replace(v_order_id::text,'-',''),1,6));

  insert into public.dailydash_orders(
    id,order_no,device_code,tender,subtotal,total,cash_received,change_amount,status
  ) values (
    v_order_id,v_order_no,coalesce(nullif(trim(p_device_code),''),'ANDROID-POS'),
    p_tender,v_subtotal,v_total,v_cash,greatest(v_cash-v_total,0),'completed'
  );

  for v_item in select value from jsonb_array_elements(p_items)
  loop
    v_qty := greatest(least(coalesce((v_item->>'quantity')::integer,1),50),1);
    v_upsized := coalesce((v_item->>'upsized')::boolean,false);

    select * into v_product
    from public.dailydash_products
    where id=v_item->>'product_id' and available=true;

    if v_upsized and not v_product.allow_upsize then v_upsized:=false; end if;
    v_unit := v_product.price + case when v_upsized then v_product.upsize_price else 0 end;

    insert into public.dailydash_order_items(
      order_id,product_id,product_name,category,quantity,upsized,unit_price,line_total
    ) values (
      v_order_id,v_product.id,v_product.name,v_product.category,v_qty,v_upsized,v_unit,v_unit*v_qty
    );
  end loop;

  for v_required in
    select item->>'product_id' as product_id,
      sum(greatest(least(coalesce((item->>'quantity')::integer,1),50),1))::integer as required_qty
    from jsonb_array_elements(p_items) item
    group by item->>'product_id'
  loop
    update public.dailydash_inventory
    set stock_qty=stock_qty-v_required.required_qty, updated_at=now()
    where product_id=v_required.product_id and track_stock=true
    returning stock_qty into v_balance;

    if found then
      insert into public.dailydash_stock_movements(
        product_id,order_id,quantity_change,balance_after,reason,note
      ) values (
        v_required.product_id,v_order_id,-v_required.required_qty,v_balance,'sale','Sale '||v_order_no
      );
    end if;
  end loop;

  return jsonb_build_object(
    'id',v_order_id,'order_no',v_order_no,'subtotal',v_subtotal,'total',v_total,
    'cash_received',v_cash,'change_amount',greatest(v_cash-v_total,0),
    'tender',p_tender,'created_at',now()
  );
end;
$$;

revoke all on function public.dailydash_create_order(jsonb,text,integer,text) from public;
grant execute on function public.dailydash_create_order(jsonb,text,integer,text) to anon, authenticated;
