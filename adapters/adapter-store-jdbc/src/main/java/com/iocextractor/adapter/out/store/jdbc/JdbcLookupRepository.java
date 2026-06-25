package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.port.out.LookupRepository;
import com.iocextractor.common.IocExtractorException;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorCategory;
import com.iocextractor.domain.model.IndicatorType;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.Objects;

/**
 * Lookup repository backed by indexed dataframe tables.
 */
public final class JdbcLookupRepository implements LookupRepository {

    private final JdbcClient jdbc;

    public JdbcLookupRepository(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public boolean contains(Indicator indicator) {
        if (indicator.type().category() == IndicatorCategory.NETWORK) {
            if (indicator.type() == IndicatorType.IPV4 && isBareIp(indicator.value())) {
                return exists("ip_list", "ip", indicator.value());
            }
            return exists("masks", "mask", indicator.value());
        }
        return switch (indicator.type()) {
            case MD5 -> exists("hashes", "hash_md5", indicator.value());
            case SHA1 -> exists("hashes", "hash_sha1", indicator.value());
            case SHA256 -> exists("hashes", "hash_sha256", indicator.value());
            default -> false;
        };
    }

    @Override
    public long maxId() {
        return Math.max(Math.max(maxId("masks"), maxId("ip_list")), maxId("hashes"));
    }

    @Override
    public long maxId(String artifactName) {
        try {
            return jdbc.sql("SELECT MAX(" + quote("id") + ") FROM " + quote(artifactName))
                    .query(Long.class)
                    .optional()
                    .orElse(0L);
        } catch (RuntimeException e) {
            throw new IocExtractorException("Failed to read max id for JDBC artifact: " + artifactName, e);
        }
    }

    // Case-insensitive existence check. The match stays on lower(column) so it is
    // independent of each artifact's stored casing (masks lower-case, hashes
    // upper-case); a functional index on lower(column) is the wire-time follow-up.
    private boolean exists(String table, String column, String value) {
        Long count = jdbc.sql("SELECT COUNT(1) FROM " + quote(table)
                        + " WHERE lower(" + quote(column) + ") = :value")
                .param("value", value.toLowerCase(Locale.ROOT))
                .query(Long.class)
                .single();
        return count != null && count > 0;
    }

    private boolean isBareIp(String value) {
        return value.matches("\\d{1,3}(?:\\.\\d{1,3}){3}");
    }

    private String quote(String identifier) {
        return "\"" + DataframeColumn.requireSqlIdentifier(identifier, "identifier") + "\"";
    }
}
