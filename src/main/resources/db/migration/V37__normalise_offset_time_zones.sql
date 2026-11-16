-- Issue #331: time zones stored before TimeZoneRule (#276).
--
-- WHAT. Data only, no schema. Every organisation.timezone and branch.timezone that spells a
-- whole-hour UTC offset is rewritten to the IANA id that names the same fixed offset:
--
--   zero hours            -> 'UTC'           ('Z', 'UT', '+00:00', 'UTC+0', 'GMT-00', ...)
--   +H, H in 1..14        -> 'Etc/GMT-H'     ('+3', '+03', '+0300', '+03:00', '+03:00:00',
--                                             '+030000', each optionally after 'UTC', 'GMT' or
--                                             'UT')
--   -H, H in 1..12        -> 'Etc/GMT+H'     (the IANA Etc names invert the sign)
--
-- The spellings are exactly the ones java.time.ZoneId.of reads as that offset, which is what the
-- organisation (create and amend) and a branch update checked before #276; a branch create checked
-- nothing. The rewrite changes no local date the application computes (the rules are the same
-- fixed offset), and it makes SQL agree with Kotlin: PostgreSQL reads AT TIME ZONE '+03:00' with
-- the POSIX sign, three hours the wrong way (V18's header), but reads 'Etc/GMT-3' as UTC+3.
--
-- WHAT IT LEAVES. A minute or second offset ('+05:30' has no Etc id), an offset outside the Etc
-- range ('+15:00'), a misspelt or unknown id, and every IANA id. It never guesses a region:
-- '+03:00' is not necessarily 'Africa/Nairobi'. What remains that TimeZoneRule refuses is
-- reported by a WARN at every start (StoredTimeZoneStartupWarning); the operator steps are in
-- docs/operations/tenant-settings.md ("Time zones stored before the rule").
--
-- Only the timezone column is written, not updated_at, updated_by or row_version: the value names
-- the same zone, so it records a fact rather than an edit (the V18 precedent), and a client's
-- version-checked write in flight is not refused for it. No setting row is touched:
-- default_timezone has always been held to the JDK's region list.
--
-- No trigger (ADR 0024 does not apply). Idempotent: a rewritten value is an IANA id, which the
-- pattern never matches, so a second run changes nothing. Pre- and post-conditions are asserted.

SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
    IF to_regclass('organisation') IS NULL OR to_regclass('branch') IS NULL THEN
        RAISE EXCEPTION 'V37: expected the V1 organisation and branch tables to exist';
    END IF;
END $$;

-- The mapping, written once: the IANA id that names the same fixed offset as [stored], or NULL
-- when [stored] is not a whole-hour offset spelling with one. In the pattern, group 1 is the sign,
-- group 2 a one-digit hour, and group 3 a two-digit hour followed only by zero minutes and seconds
-- in one of ZoneOffset's layouts. 'Z' and 'UT' are UTC to ZoneId.of but not in the JDK's region
-- list. A pg_temp function lives only in this session and is dropped at the end of the file.
CREATE FUNCTION pg_temp.v37_iana_equivalent(stored TEXT) RETURNS TEXT
LANGUAGE sql IMMUTABLE STRICT AS $fn$
    SELECT CASE
        WHEN stored IN ('Z', 'UT') THEN 'UTC'
        WHEN parsed.m IS NULL THEN NULL
        WHEN parsed.hours = 0 THEN 'UTC'
        WHEN parsed.m[1] = '+' AND parsed.hours <= 14 THEN 'Etc/GMT-' || parsed.hours
        WHEN parsed.m[1] = '-' AND parsed.hours <= 12 THEN 'Etc/GMT+' || parsed.hours
    END
    FROM (
        SELECT r.m, COALESCE(r.m[2], r.m[3])::INT AS hours
        FROM (
            SELECT regexp_match(
                stored,
                '^(?:UTC|GMT|UT)?([+-])(?:([0-9])|([0-9]{2})(?:|00|:00|0000|:00:00))$'
            ) AS m
        ) r
    ) parsed
$fn$;

UPDATE organisation
SET timezone = pg_temp.v37_iana_equivalent(timezone)
WHERE pg_temp.v37_iana_equivalent(timezone) IS NOT NULL;

UPDATE branch
SET timezone = pg_temp.v37_iana_equivalent(timezone)
WHERE pg_temp.v37_iana_equivalent(timezone) IS NOT NULL;

DO $$
DECLARE
    remaining BIGINT;
BEGIN
    SELECT COUNT(*) INTO remaining
    FROM (
        SELECT timezone FROM organisation
        UNION ALL
        SELECT timezone FROM branch
    ) z
    WHERE pg_temp.v37_iana_equivalent(z.timezone) IS NOT NULL;
    IF remaining > 0 THEN
        RAISE EXCEPTION
            'V37: % organisation or branch time zone(s) still spell a whole-hour offset',
            remaining;
    END IF;

    -- Every id the mapping can produce is one PostgreSQL knows, so SQL reads it as Kotlin does.
    SELECT COUNT(*) INTO remaining
    FROM generate_series(-12, 14) AS hours
    WHERE pg_temp.v37_iana_equivalent(
              CASE WHEN hours < 0 THEN '-' ELSE '+' END || lpad(abs(hours)::TEXT, 2, '0') || ':00'
          ) NOT IN (SELECT name FROM pg_timezone_names);
    IF remaining > 0 THEN
        RAISE EXCEPTION 'V37: % offset(s) map to a zone PostgreSQL does not know', remaining;
    END IF;
END $$;

DROP FUNCTION pg_temp.v37_iana_equivalent(TEXT);
