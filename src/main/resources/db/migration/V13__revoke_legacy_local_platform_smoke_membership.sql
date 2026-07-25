-- V12 was a historical local smoke-data migration. Its checksum must remain immutable for
-- databases that already recorded it, so remove only the two fixed rows it created.

DELETE FROM user_role_assignment
WHERE id = 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee'
  AND organisation_id = '00000000-0000-0000-0000-000000000000'
  AND user_id = '11111111-1111-1111-1111-111111111111'
  AND role_id = '50000000-0000-0000-0000-000000000001';

DELETE FROM user_organisation_membership
WHERE id = 'dddddddd-dddd-dddd-dddd-dddddddddddd'
  AND organisation_id = '00000000-0000-0000-0000-000000000000'
  AND user_id = '11111111-1111-1111-1111-111111111111'
  AND NOT EXISTS (
      SELECT 1
      FROM user_role_assignment
      WHERE organisation_id = '00000000-0000-0000-0000-000000000000'
        AND user_id = '11111111-1111-1111-1111-111111111111'
  );
