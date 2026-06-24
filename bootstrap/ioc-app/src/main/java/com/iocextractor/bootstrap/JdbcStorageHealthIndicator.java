package com.iocextractor.bootstrap;

import com.iocextractor.adapter.out.store.jdbc.JdbcStorageHealth;
import com.iocextractor.adapter.out.store.jdbc.JdbcStorageHealthProbe;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Actuator adapter for JDBC storage health. The JDBC/PRAGMA checks live in the
 * storage adapter; bootstrap only maps the storage VO to Actuator's model.
 */
public final class JdbcStorageHealthIndicator implements HealthIndicator {

    private final JdbcStorageHealthProbe probe;

    public JdbcStorageHealthIndicator(JdbcStorageHealthProbe probe) {
        this.probe = probe;
    }

    @Override
    public Health health() {
        JdbcStorageHealth health = probe.probe();
        Health.Builder builder = health.healthy() ? Health.up() : Health.down();
        builder.withDetail("dbRole", health.dbRole());
        add(builder, "userVersion", health.userVersion());
        add(builder, "foreignKeys", health.foreignKeys());
        add(builder, "journalMode", health.journalMode());
        add(builder, "quickCheck", health.quickCheck());
        add(builder, "error", health.error());
        return builder.build();
    }

    private void add(Health.Builder builder, String key, Object value) {
        if (value != null) {
            builder.withDetail(key, value);
        }
    }
}
