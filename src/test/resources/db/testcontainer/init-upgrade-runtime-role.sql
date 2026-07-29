CREATE ROLE erp_runtime_upgrade LOGIN PASSWORD 'runtime-upgrade-test-only';
GRANT erp_runtime_upgrade TO erp_migration_upgrade;
ALTER DATABASE erp_upgrade_test SET erp.runtime_role TO 'erp_runtime_upgrade';
