-- Formation/recovery owns each profile independently. Existing v12 runs and
-- progress are preserved; the v12 singleton already implies profile uniqueness.
DROP INDEX ux_export_run_active_singleton;
CREATE UNIQUE INDEX ux_export_run_active_profile
ON export_run (profile)
WHERE status IN ('STARTED', 'STAGED', 'AVAILABLE');
