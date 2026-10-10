package com.iocextractor.adapter.out.store.jdbc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

/** Child process used only to prove that a lease survives across independent JVM owners. */
public final class StagedLeaseProbe {
    private StagedLeaseProbe() { }
    public static void main(String[] args) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + args[0]);
             var lease = JdbcStagedDatabaseLease.acquire(connection)) {
            lease.requireHeld(); Files.writeString(Path.of(args[1]), "owned");
            Thread.sleep(60_000);
        }
    }
}
