CREATE ROLE erp_runtime_test LOGIN PASSWORD 'runtime-test-only';
GRANT erp_runtime_test TO erp_migration_test;
ALTER DATABASE erp_test SET erp.runtime_role TO 'erp_runtime_test';
