import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Content-Type": "application/json; charset=utf-8",
};

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: corsHeaders });

async function sha256(value: string) {
  const hash = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(hash)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

function fingerprint(req: Request) {
  return req.headers.get("cf-connecting-ip") ||
    req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ||
    req.headers.get("x-real-ip") ||
    "unknown";
}

function manilaDayStartUtcIso() {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Manila",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date());

  const values: Record<string, string> = {};
  for (const part of parts) values[part.type] = part.value;

  return new Date(
    Date.UTC(Number(values.year), Number(values.month) - 1, Number(values.day)) -
      8 * 60 * 60 * 1000,
  ).toISOString();
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);

  const url = Deno.env.get("SUPABASE_URL") ?? "";
  const serviceKey =
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ??
    Deno.env.get("SUPABASE_SECRET_KEY") ??
    "";

  if (!url || !serviceKey) return json({ error: "Server is not configured." }, 500);

  const supabase = createClient(url, serviceKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  let body: Record<string, any>;
  try {
    body = await req.json();
  } catch {
    return json({ error: "Invalid JSON body." }, 400);
  }

  const action = String(body.action ?? "dashboard");
  const pin = String(body.pin ?? "");
  const fp = fingerprint(req);

  const { data: attempt } = await supabase
    .from("dailydash_admin_attempts")
    .select("failures,blocked_until")
    .eq("fingerprint", fp)
    .maybeSingle();

  if (attempt?.blocked_until && new Date(attempt.blocked_until).getTime() > Date.now()) {
    return json({ error: "Too many failed attempts. Try again later." }, 429);
  }

  const { data: setting } = await supabase
    .from("dailydash_settings")
    .select("value")
    .eq("key", "manager_pin_sha256")
    .single();

  const validPin =
    /^\d{6}$/.test(pin) &&
    !!setting?.value &&
    (await sha256(pin)) === setting.value;

  if (!validPin) {
    const failures = Number(attempt?.failures ?? 0) + 1;

    await supabase.from("dailydash_admin_attempts").upsert({
      fingerprint: fp,
      failures: failures >= 5 ? 0 : failures,
      blocked_until:
        failures >= 5 ? new Date(Date.now() + 10 * 60 * 1000).toISOString() : null,
      updated_at: new Date().toISOString(),
    });

    return json({ error: "Incorrect manager PIN." }, 401);
  }

  await supabase.from("dailydash_admin_attempts").delete().eq("fingerprint", fp);

  if (action === "login") {
    return json({ ok: true, store_name: "DailyDash" });
  }

  if (action === "dashboard") {
    const { data: orders, error } = await supabase
      .from("dailydash_orders")
      .select("id,order_no,tender,total,status,created_at")
      .gte("created_at", manilaDayStartUtcIso())
      .order("created_at", { ascending: false });

    if (error) return json({ error: error.message }, 500);

    const completed = (orders ?? []).filter((o) => o.status === "completed");
    const sales = completed.reduce((sum, order) => sum + Number(order.total || 0), 0);
    const tenderTotals: Record<string, number> = { Cash: 0, GCash: 0, Maya: 0 };

    for (const order of completed) {
      tenderTotals[order.tender] =
        (tenderTotals[order.tender] ?? 0) + Number(order.total || 0);
    }

    const { count } = await supabase
      .from("dailydash_products")
      .select("*", { count: "exact", head: true });

    return json({
      sales_today: sales,
      orders_today: completed.length,
      average_order: completed.length ? Math.round(sales / completed.length) : 0,
      product_count: count ?? 0,
      tender_totals: tenderTotals,
      recent_orders: (orders ?? []).slice(0, 10),
    });
  }

  if (action === "orders") {
    const limit = Math.max(1, Math.min(Number(body.limit ?? 100), 300));

    const { data, error } = await supabase
      .from("dailydash_orders")
      .select("id,order_no,device_code,tender,subtotal,total,cash_received,change_amount,status,created_at")
      .order("created_at", { ascending: false })
      .limit(limit);

    return error ? json({ error: error.message }, 500) : json({ orders: data ?? [] });
  }

  if (action === "order_details") {
    const orderId = String(body.order_id ?? "");
    if (!orderId) return json({ error: "order_id is required." }, 400);

    const { data: order, error: orderError } = await supabase
      .from("dailydash_orders")
      .select("*")
      .eq("id", orderId)
      .single();

    if (orderError) return json({ error: orderError.message }, 404);

    const { data: items, error: itemError } = await supabase
      .from("dailydash_order_items")
      .select("*")
      .eq("order_id", orderId)
      .order("product_name");

    if (itemError) return json({ error: itemError.message }, 500);
    return json({ order, items: items ?? [] });
  }

  if (action === "products") {
    const { data, error } = await supabase
      .from("dailydash_products")
      .select("*")
      .order("sort_order");

    return error ? json({ error: error.message }, 500) : json({ products: data ?? [] });
  }

  if (action === "update_product") {
    const id = String(body.id ?? "");
    if (!id) return json({ error: "Product id is required." }, 400);

    const patch: Record<string, unknown> = { updated_at: new Date().toISOString() };

    if (body.price !== undefined) {
      const price = Number(body.price);
      if (!Number.isInteger(price) || price < 0 || price > 100000) {
        return json({ error: "Invalid price." }, 400);
      }
      patch.price = price;
    }

    if (body.available !== undefined) patch.available = Boolean(body.available);
    if (body.name !== undefined) patch.name = String(body.name).trim();
    if (body.category !== undefined) patch.category = String(body.category).trim();
    if (body.allow_upsize !== undefined) patch.allow_upsize = Boolean(body.allow_upsize);

    if (body.upsize_price !== undefined) {
      const upsize = Number(body.upsize_price);
      if (!Number.isInteger(upsize) || upsize < 0 || upsize > 100000) {
        return json({ error: "Invalid upsize price." }, 400);
      }
      patch.upsize_price = upsize;
    }

    const { data, error } = await supabase
      .from("dailydash_products")
      .update(patch)
      .eq("id", id)
      .select("*")
      .single();

    return error ? json({ error: error.message }, 500) : json({ product: data });
  }

  if (action === "void_order") {
    const orderId = String(body.order_id ?? "");
    if (!orderId) return json({ error: "order_id is required." }, 400);

    const { data, error } = await supabase
      .from("dailydash_orders")
      .update({ status: "voided" })
      .eq("id", orderId)
      .eq("status", "completed")
      .select("*")
      .single();

    return error ? json({ error: error.message }, 500) : json({ order: data });
  }

  if (action === "change_pin") {
    const newPin = String(body.new_pin ?? "");
    if (!/^\d{6}$/.test(newPin)) {
      return json({ error: "New PIN must be exactly 6 digits." }, 400);
    }

    const { error } = await supabase
      .from("dailydash_settings")
      .update({ value: await sha256(newPin), updated_at: new Date().toISOString() })
      .eq("key", "manager_pin_sha256");

    return error ? json({ error: error.message }, 500) : json({ ok: true });
  }

  return json({ error: "Unknown action." }, 400);
});
