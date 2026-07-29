CREATE ROLE erp_runtime_startup LOGIN PASSWORD 'runtime-startup-test-only';
ALTER DATABASE erp_runtime_startup SET erp.runtime_role TO 'erp_runtime_startup';
