-- ============================================================
-- SATEY WINGS — FRESH SCHEMA FOR PRESENT WORKING
-- Guest SOS + photo upload to Storage (anon-key friendly)
-- ============================================================
-- HOW TO USE:
-- 1. Supabase Dashboard → SQL Editor → New query
-- 2. Paste this ENTIRE file → Run
-- 3. Storage → check bucket "emergency-photos" is Public
-- 4. Paste your NEW project URL + anon key into:
--      app/.../utils/SupabaseConfig.kt  (Android)
--      webmodel/js/supabase.js           (dashboard)
-- ============================================================

-- ---------- STEP 1: TABLES ----------

-- USERS (registered + guest). Guests have email = NULL and
-- is_guest = true with a device id from the phone.
CREATE TABLE IF NOT EXISTS public.users (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name TEXT NOT NULL DEFAULT 'Guest',
  email TEXT UNIQUE,                       -- NULL allowed → many guests OK
  phone TEXT,
  wake_word TEXT NOT NULL DEFAULT 'help me',
  cancel_password TEXT,
  last_latitude DOUBLE PRECISION,
  last_longitude DOUBLE PRECISION,
  last_location_updated_at TIMESTAMPTZ,
  is_guest BOOLEAN NOT NULL DEFAULT false,
  guest_device_id TEXT UNIQUE,             -- e.g. ANDROID_ID / locally generated UUID
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ALERT HISTORY (one row per SOS; guest rows use guest_device_id)
CREATE TABLE IF NOT EXISTS public.alert_history (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID REFERENCES public.users(id) ON DELETE SET NULL,
  guest_device_id TEXT,                    -- set when sender is a guest
  user_name TEXT NOT NULL DEFAULT 'Guest',
  user_email TEXT,
  user_phone TEXT NOT NULL DEFAULT '',
  latitude DOUBLE PRECISION,
  longitude DOUBLE PRECISION,
  location_accuracy REAL,
  alert_type TEXT NOT NULL DEFAULT 'manual'
    CHECK (alert_type IN ('voice_help', 'manual', 'emergency', 'guest_sos')),
  status TEXT NOT NULL DEFAULT 'sent'
    CHECK (status IN ('sent', 'acknowledged', 'resolved')),
  front_photo_url TEXT,                    -- Supabase public URL (PIC1)
  back_photo_url TEXT,                     -- Supabase public URL (PIC2)
  sms_payload TEXT,                        -- exact SMS text sent (debug/audit)
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT alert_owner_present
    CHECK (user_id IS NOT NULL OR guest_device_id IS NOT NULL)
);

-- ---------- STEP 2: INDEXES ----------

CREATE INDEX IF NOT EXISTS idx_users_email ON public.users(email);
CREATE INDEX IF NOT EXISTS idx_users_guest_device ON public.users(guest_device_id);
CREATE INDEX IF NOT EXISTS idx_alert_user ON public.alert_history(user_id);
CREATE INDEX IF NOT EXISTS idx_alert_guest_device ON public.alert_history(guest_device_id);
CREATE INDEX IF NOT EXISTS idx_alert_created ON public.alert_history(created_at DESC);

-- ---------- STEP 3: UPDATED_AT TRIGGER ----------

CREATE OR REPLACE FUNCTION public.handle_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS set_users_updated_at ON public.users;
CREATE TRIGGER set_users_updated_at
  BEFORE UPDATE ON public.users
  FOR EACH ROW EXECUTE FUNCTION public.handle_updated_at();

-- ---------- STEP 4: RLS (anon-key guest friendly) ----------
-- Present app sends with the ANON key and no Supabase Auth session,
-- so anon must be able to INSERT/SELECT. When you later wire Auth,
-- replace these with auth.uid()-scoped policies.

ALTER TABLE public.users ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.alert_history ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "guest_users_all" ON public.users;
CREATE POLICY "guest_users_all" ON public.users
  FOR ALL TO anon, authenticated USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS "guest_alerts_all" ON public.alert_history;
CREATE POLICY "guest_alerts_all" ON public.alert_history
  FOR ALL TO anon, authenticated USING (true) WITH CHECK (true);

-- ---------- STEP 5: STORAGE BUCKET + POLICIES ----------

INSERT INTO storage.buckets (id, name, public)
VALUES ('emergency-photos', 'emergency-photos', true)
ON CONFLICT (id) DO UPDATE SET public = true;

-- Public read (guardian phones + web dashboard open PIC links directly)
DROP POLICY IF EXISTS "public read emergency-photos" ON storage.objects;
CREATE POLICY "public read emergency-photos" ON storage.objects
  FOR SELECT TO anon, authenticated, public
  USING (bucket_id = 'emergency-photos');

-- Anon upload from the app (no login / guest SOS works)
DROP POLICY IF EXISTS "anon upload emergency-photos" ON storage.objects;
CREATE POLICY "anon upload emergency-photos" ON storage.objects
  FOR INSERT TO anon, authenticated
  WITH CHECK (bucket_id = 'emergency-photos');

DROP POLICY IF EXISTS "anon update emergency-photos" ON storage.objects;
CREATE POLICY "anon update emergency-photos" ON storage.objects
  FOR UPDATE TO anon, authenticated
  USING (bucket_id = 'emergency-photos');

DROP POLICY IF EXISTS "anon delete emergency-photos" ON storage.objects;
CREATE POLICY "anon delete emergency-photos" ON storage.objects
  FOR DELETE TO anon, authenticated
  USING (bucket_id = 'emergency-photos');

-- ---------- STEP 6: GRANTS ----------

GRANT USAGE ON SCHEMA public TO anon, authenticated;
GRANT ALL ON public.users, public.alert_history TO anon, authenticated;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO anon, authenticated;

-- ============================================================
-- VERIFY (run after, expect 2 rows in buckets / policies):
--   select id, name, public from storage.buckets where id = 'emergency-photos';
--   select * from public.users limit 1;
--   select * from public.alert_history limit 1;
-- TEST UPLOAD (replace with your URL + anon key):
--   curl -X PUT "https://YOUR-URL/storage/v1/object/emergency-photos/test/ping.jpg" \
--     -H "apikey: YOUR-ANON-KEY" -H "Authorization: Bearer YOUR-ANON-KEY" \
--     -H "Content-Type: image/jpeg" --data-binary @test.jpg -v
-- Then open: https://YOUR-URL/storage/v1/object/public/emergency-photos/test/ping.jpg
-- ============================================================
