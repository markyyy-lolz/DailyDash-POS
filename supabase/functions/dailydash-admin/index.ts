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


  // POS staff login endpoints do not use the manager PIN.
  if (action === "staff_directory") {
    const { data, error } = await supabase
      .from("dailydash_staff")
      .select("id,staff_code,display_name,role,avatar_url,sort_order")
      .eq("is_active", true)
      .order("sort_order")
      .order("display_name");

    if (error) return json({ error: error.message }, 500);
    return json({ staff: data ?? [] });
  }

  if (action === "staff_login") {
    const method = String(body.method ?? "pin");
    const secret = String(body.secret ?? "");
    const staffId = body.staff_id ? String(body.staff_id) : null;
    const username = body.username ? String(body.username) : null;
    const deviceCode = String(body.device_code ?? "DAILYDASH-ANDROID-01").slice(0, 80);
    const deviceName = String(body.device_name ?? "DailyDash Android POS").slice(0, 100);
    const appVersion = String(body.app_version ?? "").slice(0, 30);

    if (!["pin", "password"].includes(method)) {
      return json({ error: "Invalid login method." }, 400);
    }
    if (method === "pin" && !/^\d{4,6}$/.test(secret)) {
      return json({ error: "Enter your 4 to 6 digit PIN." }, 400);
    }
    if (method === "password" && secret.length < 6) {
      return json({ error: "Enter your account password." }, 400);
    }

    const loginKey = "staff:" + fingerprint;
    const { data: staffAttempt } = await supabase
      .from("dailydash_admin_attempts")
      .select("failures,blocked_until")
      .eq("fingerprint", loginKey)
      .maybeSingle();

    if (
      staffAttempt?.blocked_until &&
      new Date(staffAttempt.blocked_until).getTime() > Date.now()
    ) {
      return json({ error: "Too many failed staff logins. Try again later." }, 429);
    }

    const { data: staff, error: authError } = await supabase.rpc("dailydash_staff_auth", {
      p_staff_id: staffId,
      p_username: username,
      p_secret: secret,
      p_method: method,
    });

    if (authError || !staff?.id) {
      const failures = Number(staffAttempt?.failures ?? 0) + 1;
      await supabase.from("dailydash_admin_attempts").upsert({
        fingerprint: loginKey,
        failures: failures >= 5 ? 0 : failures,
        blocked_until:
          failures >= 5
            ? new Date(Date.now() + 2 * 60 * 1000).toISOString()
            : null,
        updated_at: new Date().toISOString(),
      });
      return failures >= 5
        ? json({ error: "Too many failed staff logins. Locked for 2 minutes." }, 429)
        : json({ error: "Invalid staff login. " + (5 - failures) + " attempt(s) remaining." }, 401);
    }

    await supabase.from("dailydash_admin_attempts").delete().eq("fingerprint", loginKey);

    const { data: existingDevice } = await supabase
      .from("dailydash_devices")
      .select("id,is_active")
      .eq("device_code", deviceCode)
      .maybeSingle();

    if (existingDevice && existingDevice.is_active === false) {
      return json({ error: "This POS device is disabled by the manager." }, 403);
    }

    const { error: deviceError } = await supabase
      .from("dailydash_devices")
      .upsert({
        device_code: deviceCode,
        display_name: deviceName || "DailyDash Android POS",
        app_version: appVersion || null,
        current_staff_id: staff.id,
        last_seen_at: new Date().toISOString(),
        last_ip: fingerprint,
        updated_at: new Date().toISOString(),
      }, { onConflict: "device_code" });

    if (deviceError) return json({ error: deviceError.message }, 500);

    const bytes = crypto.getRandomValues(new Uint8Array(32));
    const sessionToken = Array.from(bytes)
      .map((b) => b.toString(16).padStart(2, "0"))
      .join("");
    const tokenHash = await sha256(sessionToken);

    await supabase
      .from("dailydash_staff_sessions")
      .delete()
      .eq("staff_id", staff.id)
      .lt("expires_at", new Date().toISOString());

    const { error: sessionError } = await supabase
      .from("dailydash_staff_sessions")
      .insert({
        token_hash: tokenHash,
        staff_id: staff.id,
        device_code: deviceCode,
        expires_at: new Date(Date.now() + 12 * 60 * 60 * 1000).toISOString(),
        last_seen_at: new Date().toISOString(),
      });

    if (sessionError) return json({ error: sessionError.message }, 500);

    return json({
      ok: true,
      staff,
      session_token: sessionToken,
      expires_in_hours: 12,
    });
  }


  if (action === "app_update") {
    const { data, error } = await supabase.rpc("dailydash_latest_app_release");
    if (error) return json({ error: error.message }, 500);
    return json({ release: data ?? {} });
  }

  if (action === "staff_logout") {
    const sessionToken = String(body.session_token ?? "");
    if (sessionToken) {
      await supabase
        .from("dailydash_staff_sessions")
        .delete()
        .eq("token_hash", await sha256(sessionToken));
    }
    return json({ ok: true });
  }

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
        ? new Date(Date.now() + 2 * 60 * 1000).toISOString()
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


  if (action === "staff") {
    const { data, error } = await supabase
      .from("dailydash_staff")
      .select("id,staff_code,username,display_name,role,avatar_url,is_active,sort_order,last_login_at,created_at")
      .order("sort_order")
      .order("display_name");

    if (error) return json({ error: error.message }, 500);
    return json({ staff: data ?? [] });
  }

  if (action === "staff_save") {
    const { data, error } = await supabase.rpc("dailydash_staff_upsert", {
      p_id: body.id || null,
      p_staff_code: String(body.staff_code ?? ""),
      p_username: body.username ? String(body.username) : null,
      p_display_name: String(body.display_name ?? ""),
      p_role: String(body.role ?? "cashier"),
      p_pin: body.staff_pin ? String(body.staff_pin) : null,
      p_password: body.password ? String(body.password) : null,
      p_avatar_url: body.avatar_url ? String(body.avatar_url) : null,
      p_is_active: body.is_active !== false,
      p_sort_order: Number(body.sort_order ?? 0),
    });

    if (error) {
      if (String(error.message).includes("duplicate key")) {
        return json({ error: "Staff code or username already exists." }, 409);
      }
      return json({ error: error.message }, 400);
    }
    return json({ staff: data });
  }

  if (action === "staff_toggle") {
    const id = String(body.id ?? "");
    if (!id) return json({ error: "Staff id is required." }, 400);

    const { data, error } = await supabase
      .from("dailydash_staff")
      .update({
        is_active: Boolean(body.is_active),
        updated_at: new Date().toISOString(),
      })
      .eq("id", id)
      .select("id,staff_code,username,display_name,role,avatar_url,is_active,sort_order,last_login_at")
      .single();

    if (error) return json({ error: error.message }, 500);

    if (!data.is_active) {
      await supabase.from("dailydash_staff_sessions").delete().eq("staff_id", id);
    }

    return json({ staff: data });
  }


  if (action === "commercial_summary") {
    const start = manilaDayStartUtcIso();
    const [shifts, held, expenses, refunds, ingredients, audit] = await Promise.all([
      supabase.from("dailydash_shifts").select("id", { count: "exact", head: true }).eq("status", "open"),
      supabase.from("dailydash_held_orders").select("id", { count: "exact", head: true }),
      supabase.from("dailydash_expenses").select("amount").gte("created_at", start),
      supabase.from("dailydash_refunds").select("amount").gte("created_at", start),
      supabase.from("dailydash_ingredients").select("id,stock_qty,low_stock_level").eq("active", true),
      supabase.from("dailydash_audit_log")
        .select("id,action,entity_type,entity_id,details,created_at,dailydash_staff(display_name)")
        .order("created_at", { ascending: false }).limit(12),
    ]);

    const lowIngredients = (ingredients.data ?? []).filter((i:any) =>
      Number(i.stock_qty) <= Number(i.low_stock_level)
    ).length;

    return json({
      open_shifts: shifts.count ?? 0,
      held_orders: held.count ?? 0,
      expenses_today: (expenses.data ?? []).reduce((a:any, x:any) => a + Number(x.amount || 0), 0),
      refunds_today: (refunds.data ?? []).reduce((a:any, x:any) => a + Number(x.amount || 0), 0),
      low_ingredients: lowIngredients,
      audit: audit.data ?? [],
    });
  }

  if (action === "discounts") {
    const { data, error } = await supabase
      .from("dailydash_discounts")
      .select("*")
      .order("created_at", { ascending: false });
    if (error) return json({ error: error.message }, 500);
    return json({ discounts: data ?? [] });
  }

  if (action === "discount_save") {
    const id = body.id ? String(body.id) : null;
    const patch:any = {
      name: String(body.name ?? "").trim(),
      code: body.code ? String(body.code).trim().toUpperCase() : null,
      discount_type: String(body.discount_type ?? "percentage"),
      value: Number(body.value ?? 0),
      max_amount: body.max_amount === null || body.max_amount === undefined || body.max_amount === "" ? null : Number(body.max_amount),
      min_spend: Number(body.min_spend ?? 0),
      requires_manager: Boolean(body.requires_manager),
      active: body.active !== false,
      updated_at: new Date().toISOString(),
    };
    if (!patch.name) return json({ error: "Discount name is required." }, 400);
    if (!["percentage","fixed"].includes(patch.discount_type)) return json({ error: "Invalid discount type." }, 400);
    if (!Number.isFinite(patch.value) || patch.value < 0) return json({ error: "Invalid discount value." }, 400);

    let result;
    if (id) {
      result = await supabase.from("dailydash_discounts").update(patch).eq("id", id).select("*").single();
    } else {
      result = await supabase.from("dailydash_discounts").insert(patch).select("*").single();
    }
    if (result.error) return json({ error: result.error.message }, 500);
    return json({ discount: result.data });
  }

  if (action === "discount_toggle") {
    const id = String(body.id ?? "");
    const { data, error } = await supabase
      .from("dailydash_discounts")
      .update({ active: Boolean(body.active), updated_at: new Date().toISOString() })
      .eq("id", id).select("*").single();
    if (error) return json({ error: error.message }, 500);
    return json({ discount: data });
  }

  if (action === "ingredients") {
    const [ingredients, recipes, movements] = await Promise.all([
      supabase.from("dailydash_ingredients").select("*").order("name"),
      supabase.from("dailydash_recipes").select("product_id,ingredient_id,qty"),
      supabase.from("dailydash_ingredient_movements")
        .select("id,ingredient_id,quantity_change,balance_after,reason,note,created_at")
        .order("created_at", { ascending: false }).limit(80),
    ]);
    if (ingredients.error) return json({ error: ingredients.error.message }, 500);
    if (recipes.error) return json({ error: recipes.error.message }, 500);
    if (movements.error) return json({ error: movements.error.message }, 500);
    return json({ ingredients: ingredients.data ?? [], recipes: recipes.data ?? [], movements: movements.data ?? [] });
  }

  if (action === "ingredient_save") {
    const id = body.id ? String(body.id) : null;
    const patch:any = {
      name: String(body.name ?? "").trim(),
      unit: String(body.unit ?? "unit").trim() || "unit",
      low_stock_level: Number(body.low_stock_level ?? 0),
      cost_per_unit: Number(body.cost_per_unit ?? 0),
      active: body.active !== false,
      updated_at: new Date().toISOString(),
    };
    if (!patch.name) return json({ error: "Ingredient name is required." }, 400);

    let result;
    if (id) {
      result = await supabase.from("dailydash_ingredients").update(patch).eq("id", id).select("*").single();
    } else {
      patch.stock_qty = Number(body.stock_qty ?? 0);
      result = await supabase.from("dailydash_ingredients").insert(patch).select("*").single();
    }
    if (result.error) return json({ error: result.error.message }, 500);
    return json({ ingredient: result.data });
  }

  if (action === "ingredient_adjust") {
    const { data, error } = await supabase.rpc("dailydash_adjust_ingredient", {
      p_ingredient_id: String(body.ingredient_id ?? ""),
      p_quantity_change: Number(body.quantity_change ?? 0),
      p_reason: String(body.reason ?? "adjustment"),
      p_note: body.note ? String(body.note) : null,
    });
    if (error) return json({ error: error.message }, 400);
    return json({ ingredient: data });
  }

  if (action === "recipe_save") {
    const productId = String(body.product_id ?? "");
    const items = Array.isArray(body.items) ? body.items : [];
    if (!productId) return json({ error: "Product is required." }, 400);

    const del = await supabase.from("dailydash_recipes").delete().eq("product_id", productId);
    if (del.error) return json({ error: del.error.message }, 500);

    if (items.length) {
      const rows = items
        .filter((x:any) => x.ingredient_id && Number(x.qty) > 0)
        .map((x:any) => ({
          product_id: productId,
          ingredient_id: String(x.ingredient_id),
          qty: Number(x.qty),
        }));
      if (rows.length) {
        const ins = await supabase.from("dailydash_recipes").insert(rows);
        if (ins.error) return json({ error: ins.error.message }, 500);
      }
    }
    return json({ ok: true });
  }

  if (action === "suppliers") {
    const { data, error } = await supabase.from("dailydash_suppliers").select("*").order("name");
    if (error) return json({ error: error.message }, 500);
    return json({ suppliers: data ?? [] });
  }

  if (action === "supplier_save") {
    const id = body.id ? String(body.id) : null;
    const patch:any = {
      name: String(body.name ?? "").trim(),
      contact_name: body.contact_name ? String(body.contact_name).trim() : null,
      phone: body.phone ? String(body.phone).trim() : null,
      email: body.email ? String(body.email).trim() : null,
      notes: body.notes ? String(body.notes).trim() : null,
      active: body.active !== false,
      updated_at: new Date().toISOString(),
    };
    if (!patch.name) return json({ error: "Supplier name is required." }, 400);
    let result;
    if (id) result = await supabase.from("dailydash_suppliers").update(patch).eq("id", id).select("*").single();
    else result = await supabase.from("dailydash_suppliers").insert(patch).select("*").single();
    if (result.error) return json({ error: result.error.message }, 500);
    return json({ supplier: result.data });
  }

  if (action === "purchases") {
    const { data, error } = await supabase
      .from("dailydash_purchases")
      .select("*,dailydash_suppliers(name),dailydash_staff(display_name)")
      .order("received_at", { ascending: false }).limit(100);
    if (error) return json({ error: error.message }, 500);
    return json({ purchases: data ?? [] });
  }

  if (action === "purchase_create") {
    const staffId = body.staff_id ? String(body.staff_id) : null;
    const supplierId = body.supplier_id ? String(body.supplier_id) : null;
    const { data, error } = await supabase.rpc("dailydash_record_purchase", {
      p_supplier_id: supplierId,
      p_staff_id: staffId,
      p_reference_no: body.reference_no ? String(body.reference_no) : null,
      p_notes: body.notes ? String(body.notes) : null,
      p_items: Array.isArray(body.items) ? body.items : [],
    });
    if (error) return json({ error: error.message }, 400);
    return json({ purchase: data });
  }

  if (action === "expenses") {
    const { data, error } = await supabase
      .from("dailydash_expenses")
      .select("*,dailydash_staff(display_name),dailydash_shifts(device_code)")
      .order("created_at", { ascending: false }).limit(200);
    if (error) return json({ error: error.message }, 500);
    return json({ expenses: data ?? [] });
  }

  if (action === "expense_save") {
    const amount = Number(body.amount ?? 0);
    if (!String(body.category ?? "").trim() || amount <= 0) {
      return json({ error: "Category and positive amount are required." }, 400);
    }
    const { data, error } = await supabase.from("dailydash_expenses").insert({
      shift_id: body.shift_id || null,
      staff_id: body.staff_id || null,
      category: String(body.category).trim(),
      amount,
      note: body.note ? String(body.note).trim() : null,
    }).select("*").single();
    if (error) return json({ error: error.message }, 500);
    return json({ expense: data });
  }

  if (action === "shifts") {
    const { data, error } = await supabase
      .from("dailydash_shifts")
      .select("*,dailydash_staff(display_name,role)")
      .order("opened_at", { ascending: false }).limit(150);
    if (error) return json({ error: error.message }, 500);
    return json({ shifts: data ?? [] });
  }

  if (action === "audit") {
    const { data, error } = await supabase
      .from("dailydash_audit_log")
      .select("*,dailydash_staff(display_name,role)")
      .order("created_at", { ascending: false }).limit(300);
    if (error) return json({ error: error.message }, 500);
    return json({ audit: data ?? [] });
  }

  if (action === "kitchen_orders") {
    const { data, error } = await supabase
      .from("dailydash_orders")
      .select("id,order_no,queue_no,prep_status,customer_name,notes,total,created_at,dailydash_order_items(product_name,category,quantity,upsized)")
      .in("prep_status", ["pending","preparing","ready"])
      .neq("status","voided")
      .order("created_at", { ascending: true })
      .limit(100);
    if (error) return json({ error: error.message }, 500);
    return json({ orders: data ?? [] });
  }

  if (action === "kitchen_update") {
    const status = String(body.status ?? "");
    if (!["pending","preparing","ready","served","cancelled"].includes(status)) {
      return json({ error: "Invalid preparation status." }, 400);
    }
    const { data, error } = await supabase
      .from("dailydash_orders")
      .update({ prep_status: status })
      .eq("id", String(body.order_id ?? ""))
      .select("id,order_no,queue_no,prep_status").single();
    if (error) return json({ error: error.message }, 500);
    return json({ order: data });
  }

  if (action === "business_settings") {
    const keys = [
      "store_name","branch_name","receipt_address","receipt_phone","receipt_footer",
      "require_open_shift","queue_enabled"
    ];
    const { data, error } = await supabase
      .from("dailydash_settings").select("key,value").in("key", keys);
    if (error) return json({ error: error.message }, 500);
    return json({ settings: Object.fromEntries((data ?? []).map((x:any)=>[x.key,x.value])) });
  }

  if (action === "business_settings_save") {
    const allowed = new Set([
      "store_name","branch_name","receipt_address","receipt_phone","receipt_footer",
      "require_open_shift","queue_enabled"
    ]);
    const incoming = body.settings && typeof body.settings === "object" ? body.settings : {};
    const rows = Object.entries(incoming)
      .filter(([key]) => allowed.has(key))
      .map(([key,value]) => ({ key, value: String(value ?? ""), updated_at: new Date().toISOString() }));
    if (rows.length) {
      const { error } = await supabase.from("dailydash_settings").upsert(rows, { onConflict: "key" });
      if (error) return json({ error: error.message }, 500);
    }
    return json({ ok: true });
  }

  if (action === "backup_export") {
    const [orders, items, products, inventory, staff, ingredients, expenses, shifts] = await Promise.all([
      supabase.from("dailydash_orders").select("*").order("created_at", { ascending: false }).limit(5000),
      supabase.from("dailydash_order_items").select("*").limit(10000),
      supabase.from("dailydash_products").select("*"),
      supabase.from("dailydash_inventory").select("*"),
      supabase.from("dailydash_staff").select("id,staff_code,username,display_name,role,is_active,sort_order,last_login_at,created_at,updated_at"),
      supabase.from("dailydash_ingredients").select("*"),
      supabase.from("dailydash_expenses").select("*").limit(5000),
      supabase.from("dailydash_shifts").select("*").limit(5000),
    ]);
    return json({
      exported_at: new Date().toISOString(),
      orders: orders.data ?? [],
      order_items: items.data ?? [],
      products: products.data ?? [],
      inventory: inventory.data ?? [],
      staff: staff.data ?? [],
      ingredients: ingredients.data ?? [],
      expenses: expenses.data ?? [],
      shifts: shifts.data ?? [],
    });
  }


  if (action === "refund_order_admin") {
    const orderId = String(body.order_id ?? "");
    const items = Array.isArray(body.items) ? body.items : [];
    const reason = String(body.reason ?? "").trim();
    const staffId = body.staff_id ? String(body.staff_id) : null;
    if (!orderId || !items.length || !reason) {
      return json({ error: "Order, refund items and reason are required." }, 400);
    }
    const { data, error } = await supabase.rpc("dailydash_refund_order_admin", {
      p_order_id: orderId,
      p_items: items,
      p_reason: reason,
      p_staff_id: staffId,
    });
    if (error) return json({ error: error.message }, 400);
    return json({ refund: data });
  }


  if (action === "v21_summary") {
    const start = manilaDayStartUtcIso();
    const [customers, waste, devices, reorder] = await Promise.all([
      supabase.from("dailydash_customers").select("id", { count: "exact", head: true }).eq("active", true),
      supabase.from("dailydash_waste_log").select("estimated_cost").gte("created_at", start),
      supabase.from("dailydash_devices").select("id,is_active,last_seen_at"),
      supabase.rpc("dailydash_smart_reorder"),
    ]);
    const deviceRows = devices.data ?? [];
    return json({
      customers: customers.count ?? 0,
      waste_cost_today: (waste.data ?? []).reduce((a:any,x:any)=>a+Number(x.estimated_cost||0),0),
      active_devices: deviceRows.filter((x:any)=>x.is_active).length,
      online_devices: deviceRows.filter((x:any)=>x.is_active && Date.now()-new Date(x.last_seen_at).getTime()<10*60*1000).length,
      reorder_alerts: Array.isArray(reorder.data) ? reorder.data.filter((x:any)=>Number(x.suggested_qty)>0).length : 0,
    });
  }

  if (action === "modifiers") {
    const { data, error } = await supabase
      .from("dailydash_product_modifiers")
      .select("*,dailydash_products(name,category),dailydash_ingredients(name,unit)")
      .order("product_id").order("group_name").order("sort_order");
    if (error) return json({ error: error.message }, 500);
    return json({ modifiers: data ?? [] });
  }

  if (action === "modifier_save") {
    const id = body.id ? String(body.id) : null;
    const patch:any = {
      product_id: String(body.product_id ?? ""),
      group_name: String(body.group_name ?? "Add-ons").trim() || "Add-ons",
      group_type: String(body.group_type ?? "multi"),
      name: String(body.name ?? "").trim(),
      price_delta: Number(body.price_delta ?? 0),
      is_default: Boolean(body.is_default),
      required: Boolean(body.required),
      max_select: Math.max(1, Math.min(10, Number(body.max_select ?? 1))),
      ingredient_id: body.ingredient_id ? String(body.ingredient_id) : null,
      ingredient_qty: Number(body.ingredient_qty ?? 0),
      active: body.active !== false,
      sort_order: Number(body.sort_order ?? 0),
      updated_at: new Date().toISOString(),
    };
    if (!patch.product_id || !patch.name) return json({ error: "Product and modifier name are required." }, 400);
    if (!["single","multi"].includes(patch.group_type)) return json({ error: "Invalid modifier group type." }, 400);
    if (patch.ingredient_qty < 0) return json({ error: "Ingredient quantity cannot be negative." }, 400);

    let result;
    if (id) result = await supabase.from("dailydash_product_modifiers").update(patch).eq("id",id).select("*").single();
    else result = await supabase.from("dailydash_product_modifiers").insert(patch).select("*").single();
    if (result.error) return json({ error: result.error.message }, 400);
    return json({ modifier: result.data });
  }

  if (action === "modifier_toggle") {
    const { data, error } = await supabase
      .from("dailydash_product_modifiers")
      .update({ active:Boolean(body.active), updated_at:new Date().toISOString() })
      .eq("id",String(body.id ?? ""))
      .select("*").single();
    if (error) return json({ error:error.message },400);
    return json({ modifier:data });
  }

  if (action === "modifier_delete") {
    const { error } = await supabase
      .from("dailydash_product_modifiers")
      .delete().eq("id",String(body.id ?? ""));
    if (error) return json({ error:error.message },400);
    return json({ ok:true });
  }

  if (action === "customers") {
    const q = String(body.q ?? "").trim();
    let query = supabase
      .from("dailydash_customers")
      .select("*")
      .order("updated_at",{ascending:false})
      .limit(300);
    if (q) query = query.or("name.ilike.%"+q+"%,phone.ilike.%"+q+"%");
    const { data, error } = await query;
    if (error) return json({ error:error.message },500);
    return json({ customers:data ?? [] });
  }

  if (action === "customer_save") {
    const id = body.id ? String(body.id) : null;
    const patch:any = {
      name:String(body.name ?? "").trim(),
      phone:String(body.phone ?? "").trim(),
      birthday:body.birthday || null,
      notes:body.notes ? String(body.notes).trim() : null,
      active:body.active !== false,
      updated_at:new Date().toISOString(),
    };
    if (!patch.name || patch.phone.replace(/\D/g,"").length < 7) {
      return json({ error:"Customer name and valid phone are required." },400);
    }
    let result;
    if (id) result=await supabase.from("dailydash_customers").update(patch).eq("id",id).select("*").single();
    else result=await supabase.from("dailydash_customers").insert(patch).select("*").single();
    if (result.error) return json({ error:result.error.message },400);
    return json({ customer:result.data });
  }

  if (action === "loyalty_adjust") {
    const customerId=String(body.customer_id ?? "");
    const change=Number(body.points_change ?? 0);
    const note=String(body.note ?? "Manager adjustment").trim();
    if (!customerId || !Number.isInteger(change) || change===0) return json({ error:"Valid customer and non-zero whole points are required." },400);

    const { data:customer,error:loadError } = await supabase
      .from("dailydash_customers").select("id,points_balance").eq("id",customerId).single();
    if (loadError) return json({ error:loadError.message },400);
    const next=Number(customer.points_balance||0)+change;
    if (next<0) return json({ error:"Adjustment would make points negative." },400);

    const { error:updateError } = await supabase
      .from("dailydash_customers").update({points_balance:next,updated_at:new Date().toISOString()}).eq("id",customerId);
    if (updateError) return json({ error:updateError.message },500);

    await supabase.from("dailydash_loyalty_transactions").insert({
      customer_id:customerId,points_change:change,balance_after:next,reason:"adjustment",note
    });
    return json({ ok:true,balance:next });
  }

  if (action === "waste") {
    const { data, error } = await supabase
      .from("dailydash_waste_log")
      .select("*,dailydash_staff(display_name),dailydash_ingredients(name,unit),dailydash_products(name,category)")
      .order("created_at",{ascending:false}).limit(300);
    if (error) return json({ error:error.message },500);
    return json({ waste:data ?? [] });
  }

  if (action === "waste_save") {
    const { data, error } = await supabase.rpc("dailydash_record_waste",{
      p_staff_id: body.staff_id ? String(body.staff_id) : null,
      p_ingredient_id: body.ingredient_id ? String(body.ingredient_id) : null,
      p_product_id: body.product_id ? String(body.product_id) : null,
      p_quantity: Number(body.quantity ?? 0),
      p_reason: String(body.reason ?? "other"),
      p_note: body.note ? String(body.note) : null,
    });
    if (error) return json({ error:error.message },400);
    return json({ waste:data });
  }

  if (action === "smart_reorder") {
    const { data, error } = await supabase.rpc("dailydash_smart_reorder");
    if (error) return json({ error:error.message },500);
    return json({ suggestions:data ?? [] });
  }

  if (action === "devices") {
    const { data, error } = await supabase
      .from("dailydash_devices")
      .select("*,dailydash_staff(display_name,role)")
      .order("last_seen_at",{ascending:false});
    if (error) return json({ error:error.message },500);
    return json({ devices:data ?? [] });
  }

  if (action === "device_toggle") {
    const id=String(body.id ?? "");
    const active=Boolean(body.active);
    const { data,error } = await supabase
      .from("dailydash_devices")
      .update({is_active:active,updated_at:new Date().toISOString()})
      .eq("id",id).select("*").single();
    if (error) return json({ error:error.message },400);
    if (!active && data?.device_code) {
      await supabase.from("dailydash_staff_sessions").delete().eq("device_code",data.device_code);
    }
    return json({ device:data });
  }

  if (action === "device_save") {
    const id=String(body.id ?? "");
    const { data,error } = await supabase
      .from("dailydash_devices")
      .update({
        display_name:String(body.display_name ?? "DailyDash POS").trim(),
        notes:body.notes ? String(body.notes).trim() : null,
        updated_at:new Date().toISOString()
      })
      .eq("id",id).select("*").single();
    if (error) return json({ error:error.message },400);
    return json({ device:data });
  }

  if (action === "app_releases") {
    const { data,error } = await supabase
      .from("dailydash_app_releases")
      .select("*").order("version_code",{ascending:false});
    if (error) return json({ error:error.message },500);
    return json({ releases:data ?? [] });
  }

  if (action === "app_release_save") {
    const versionCode=Number(body.version_code ?? 0);
    const versionName=String(body.version_name ?? "").trim();
    if (!Number.isInteger(versionCode) || versionCode<=0 || !versionName) {
      return json({ error:"Version code and version name are required." },400);
    }
    const row={
      version_code:versionCode,
      version_name:versionName,
      changelog:String(body.changelog ?? ""),
      update_url:body.update_url ? String(body.update_url).trim() : null,
      required:Boolean(body.required),
      active:body.active !== false,
      published_at:new Date().toISOString(),
    };
    const { data,error } = await supabase
      .from("dailydash_app_releases")
      .upsert(row,{onConflict:"version_code"}).select("*").single();
    if (error) return json({ error:error.message },400);
    return json({ release:data });
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
