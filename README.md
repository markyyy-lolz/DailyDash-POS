# DailyDash POS

Android-first point-of-sale system with a GitHub Pages manager dashboard, connected to the **supabase2** database.

## Live backend
- Supabase project: `smpcs1918ams`
- DailyDash products, orders, order items, settings, and manager login protection
- Server-side checkout price validation
- Deployed `dailydash-admin` Edge Function
- Cash, GCash, and Maya
- Menu management, sales dashboard, order history, voiding, and manager PIN changes

## Android
Open the `android/` folder in Android Studio with JDK 17. The app supports offline menu fallback, cloud menu sync, cart quantities, +₱10 drink upsize, cash change, GCash/Maya, and cloud checkout.

## Web manager
The `web/` folder is deployed with GitHub Pages through GitHub Actions.

## Menu
- Coffee Based — ₱29 (+₱10 upsize)
- Milk Based — ₱29 (+₱10 upsize)
- Waffly Bites — ₱39
- Stuffles Classic — ₱39
- Stuffles Truffle — ₱49
- La Pasta Signatures — ₱39
- La Pasta Truffle — ₱49

## Security
The Supabase service-role key is never stored in Android or the web dashboard. Manager-only operations run through the Edge Function, while checkout totals are recalculated in PostgreSQL.
