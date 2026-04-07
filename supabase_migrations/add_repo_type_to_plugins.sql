-- Migration: Add repo_type column to plugins table
-- This allows NuvioTV to persist the repository type (NUVIO_JS / EXTERNAL_DEX)
-- across sync, avoiding fragile HTTP-based re-detection on every pull.
-- Backward-compatible: old clients send NULL, new clients send the type string.

-- 1. Add the column
ALTER TABLE plugins ADD COLUMN IF NOT EXISTS repo_type TEXT;

-- 2. Update the RPC to accept and store repo_type
CREATE OR REPLACE FUNCTION sync_push_plugins(p_plugins JSONB, p_profile_id INT DEFAULT 1)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE v_user_id uuid := get_sync_owner();
BEGIN
    INSERT INTO plugins (user_id, url, name, enabled, sort_order, profile_id, repo_type)
    SELECT
        v_user_id,
        (e->>'url')::text,
        (e->>'name')::text,
        COALESCE((e->>'enabled')::boolean, true),
        COALESCE((e->>'sort_order')::integer, 0),
        p_profile_id,
        (e->>'repo_type')::text
    FROM jsonb_array_elements(p_plugins) AS e
    ON CONFLICT (user_id, md5(url), profile_id)
    DO UPDATE SET
        name       = EXCLUDED.name,
        enabled    = EXCLUDED.enabled,
        sort_order = EXCLUDED.sort_order,
        repo_type  = EXCLUDED.repo_type
    WHERE plugins.name IS DISTINCT FROM EXCLUDED.name
       OR plugins.enabled IS DISTINCT FROM EXCLUDED.enabled
       OR plugins.sort_order IS DISTINCT FROM EXCLUDED.sort_order
       OR plugins.repo_type IS DISTINCT FROM EXCLUDED.repo_type;

    DELETE FROM plugins p
    WHERE p.user_id    = v_user_id
      AND p.profile_id = p_profile_id
      AND NOT EXISTS (
          SELECT 1 FROM jsonb_array_elements(p_plugins) AS e
          WHERE md5((e->>'url')::text) = md5(p.url)
      );
END;
$$;
