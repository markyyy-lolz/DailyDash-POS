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
  const data = new TextEncoder().encode(value);
  const hash = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(hash))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

function clientFingerprint(req: Request) {
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
  for (const p of parts) values[p.type] = p.value;

  return new Date(
    Date.UTC(
      Number(values.year),
      Number(values.month) - 1,
      Number(values.day),
    ) - 8 * 60 * 60 * 1000,
  ).toISOString();
}

function reportRange(start: string, end: string) {
  const valid = /^\d{4}-\d{2}-\d{2}$/;
  if (!valid.test(start) || !valid.test(end)) {
    throw new Error("Invalid report date range.");
  }

  const startDate = new Date(`${start}T00:00:00+08:00`);
  const endDate = new Date(`${end}T00:00:00+08:00`);
  endDate.setUTCDate(endDate.getUTCDate() + 1);

  if (!Number.isFinite(startDate.getTime()) || !Number.isFinite(endDate.getTime())) {
    throw new Error("Invalid report date range.");
  }
  if (endDate.getTime() <= startDate.getTime()) {
    throw new Error("Report end must be on or after start.");
  }

  return {
    start: startDate.toISOString(),
    end: endDate.toISOString(),
  };
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);

  const url = Deno.env.get("SUPABASE_URL") ?? "";
  const serviceKey =
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ??
    Deno.env.get("SUPABASE_SECRET_KEY") ??
    "";

  if (!url || !serviceKey) {
    return json({ error: "Server is not configured." }, 500);
  }

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
  const fingerprint = clientFingerprint(req);

  const { data: attempt } = await supabase
    .from("dailydash_admin_attempts")
    .select("failures,blocked_until")
    .eq("fingerprint", fingerprint)
    .maybeSingle();

  if (
    attempt?.blocked_until &&
    new Date(attempt.blocked_until).getTime() > Date.now()
  ) {
    return json({ error: "Too many failed attempts. Try again later." }, 429);
  }

  const { data: setting, error: settingError } = await supabase
    .from("dailydash_settings")
    .select("value")
    .eq("key", "manager_pin_sha256")
    .single();

  if (settingError || !setting?.value) {
    return json({ error: "Manager PIN is not configured." }, 500);
  }

  const validPin =
    /^\d{6}$/.test(pin) &&
    (await sha256(pin)) === setting.value;

  if (!validPin) {
    const failures = Number(attempt?.failures ?? 0) + 1;
    const blockedUntil =
      failures >= 5
        ? new Date(Date.now() + 10 * 60 * 1000).toISOString()
        : null;

    await supabase.from("dailydash_admin_attempts").upsert({
      fingerprint,
      failures: failures >= 5 ? 0 : failures,
      blocked_until: blockedUntil,
      updated_at: new Date().toISOString(),
    });

    return json({ error: "Incorrect manager PIN." }, 401);
  }

  await supabase
    .from("dailydash_admin_attempts")
    .delete()
    .eq("fingerprint", fingerprint);

  if (action === "login") {
    const { data: store } = await supabase
      .from("dailydash_settings")
      .select("value")
      .eq("key", "store_name")
      .maybeSingle();

    return json({ ok: true, store_name: store?.value ?? "DailyDash" });
  }

  if (action === "dashboard") {
    const [
      ordersResult,
      productCountResult,
      inventoryResult,
    ] = await Promise.all([
      supabase
        .from("dailydash_orders")
        .select("id,order_no,tender,total,status,created_at")
        .gte("created_at", manilaDayStartUtcIso())
        .order("created_at", { ascending: false }),
      supabase
        .from("dailydash_products")
        .select("*", { count: "exact", head: true }),
      supabase
        .from("dailydash_inventory")
        .select("stock_qty,low_stock_level,track_stock"),
    ]);

    if (ordersResult.error) return json({ error: ordersResult.error.message }, 500);
    if (inventoryResult.error) return json({ error: inventoryResult.error.message }, 500);

    const orders = ordersResult.data ?? [];
    const completed = orders.filter((o) => o.status === "completed");
    const sales = completed.reduce((sum, o) => sum + Number(o.total || 0), 0);
    const tenderTotals: Record<string, number> = { Cash: 0, GCash: 0, Maya: 0 };

    for (const o of completed) {
      tenderTotals[o.tender] =
        (tenderTotals[o.tender] ?? 0) + Number(o.total || 0);
    }

    const tracked = (inventoryResult.data ?? []).filter((i) => i.track_stock);
    const lowStockCount = tracked.filter(
      (i) => Number(i.stock_qty) <= Number(i.low_stock_level),
    ).length;
    const outOfStockCount = tracked.filter(
      (i) => Number(i.stock_qty) <= 0,
    ).length;

    return json({
      sales_today: sales,
      orders_today: completed.length,
      average_order:
        completed.length ? Math.round(sales / completed.length) : 0,
      product_count: productCountResult.count ?? 0,
      low_stock_count: lowStockCount,
      out_of_stock_count: outOfStockCount,
      tender_totals: tenderTotals,
      recent_orders: orders.slice(0, 10),
    });
  }

  if (action === "orders") {
    const limit = Math.max(1, Math.min(Number(body.limit ?? 100), 300));

    const { data, error } = await supabase
      .from("dailydash_orders")
      .select(
        "id,order_no,device_code,tender,subtotal,total,cash_received,change_amount,status,created_at",
      )
      .order("created_at", { ascending: false })
      .limit(limit);

    if (error) return json({ error: error.message }, 500);
    return json({ orders: data ?? [] });
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

    if (error) return json({ error: error.message }, 500);
    return json({ products: data ?? [] });
  }

  if (action === "update_product") {
    const id = String(body.id ?? "");
    if (!id) return json({ error: "Product id is required." }, 400);

    const patch: Record<string, unknown> = {
      updated_at: new Date().toISOString(),
    };

    if (body.name !== undefined) patch.name = String(body.name).trim();
    if (body.category !== undefined) patch.category = String(body.category).trim();

    if (body.price !== undefined) {
      const price = Number(body.price);
      if (!Number.isInteger(price) || price < 0 || price > 100000) {
        return json({ error: "Invalid price." }, 400);
      }
      patch.price = price;
    }

    if (body.available !== undefined) patch.available = Boolean(body.available);
    if (body.allow_upsize !== undefined) {
      patch.allow_upsize = Boolean(body.allow_upsize);
    }

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

    if (error) return json({ error: error.message }, 500);
    return json({ product: data });
  }

  if (action === "inventory") {
    const [productsResult, inventoryResult, movementsResult] = await Promise.all([
      supabase
        .from("dailydash_products")
        .select("id,name,category,price,available,sort_order")
        .order("sort_order"),
      supabase
        .from("dailydash_inventory")
        .select("product_id,stock_qty,low_stock_level,track_stock,updated_at"),
      supabase
        .from("dailydash_stock_movements")
        .select(
          "id,product_id,order_id,quantity_change,balance_after,reason,note,created_at",
        )
        .order("created_at", { ascending: false })
        .limit(60),
    ]);

    if (productsResult.error) return json({ error: productsResult.error.message }, 500);
    if (inventoryResult.error) return json({ error: inventoryResult.error.message }, 500);
    if (movementsResult.error) return json({ error: movementsResult.error.message }, 500);

    const byProduct = new Map(
      (inventoryResult.data ?? []).map((i) => [i.product_id, i]),
    );

    const items = (productsResult.data ?? []).map((p) => ({
      ...p,
      ...(byProduct.get(p.id) ?? {
        stock_qty: 0,
        low_stock_level: 5,
        track_stock: false,
        updated_at: null,
      }),
    }));

    return json({
      items,
      movements: movementsResult.data ?? [],
    });
  }

  if (action === "set_inventory") {
    const productId = String(body.product_id ?? "");
    const stockQty = Number(body.stock_qty);
    const lowStockLevel = Number(body.low_stock_level ?? 5);
    const trackStock = Boolean(body.track_stock);
    const note = String(body.note ?? "").slice(0, 250);

    if (!productId) return json({ error: "product_id is required." }, 400);
    if (!Number.isInteger(stockQty) || stockQty < 0 || stockQty > 1000000) {
      return json({ error: "Invalid stock quantity." }, 400);
    }
    if (
      !Number.isInteger(lowStockLevel) ||
      lowStockLevel < 0 ||
      lowStockLevel > 1000000
    ) {
      return json({ error: "Invalid low-stock level." }, 400);
    }

    const { data, error } = await supabase.rpc("dailydash_set_inventory", {
      p_product_id: productId,
      p_stock_qty: stockQty,
      p_low_stock_level: lowStockLevel,
      p_track_stock: trackStock,
      p_note: note || null,
    });

    if (error) return json({ error: error.message }, 500);
    return json({ inventory: data });
  }

  if (action === "reports") {
    let range;
    try {
      range = reportRange(String(body.start ?? ""), String(body.end ?? ""));
    } catch (error) {
      return json({ error: error instanceof Error ? error.message : "Invalid date range." }, 400);
    }

    const { data, error } = await supabase.rpc("dailydash_report", {
      p_start: range.start,
      p_end: range.end,
    });

    if (error) return json({ error: error.message }, 500);
    return json({ report: data });
  }

  if (action === "void_order") {
    const id = String(body.order_id ?? "");
    if (!id) return json({ error: "order_id is required." }, 400);

    const { data, error } = await supabase.rpc("dailydash_void_order", {
      p_order_id: id,
    });

    if (error) return json({ error: error.message }, 500);
    return json({ order: data });
  }

  if (action === "change_pin") {
    const newPin = String(body.new_pin ?? "");
    if (!/^\d{6}$/.test(newPin)) {
      return json({ error: "New PIN must be exactly 6 digits." }, 400);
    }

    const { error } = await supabase
      .from("dailydash_settings")
      .update({
        value: await sha256(newPin),
        updated_at: new Date().toISOString(),
      })
      .eq("key", "manager_pin_sha256");

    if (error) return json({ error: error.message }, 500);
    return json({ ok: true });
  }

  return json({ error: "Unknown action." }, 400);
});
