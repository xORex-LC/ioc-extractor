package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.port.out.LookupRepository;
import com.iocextractor.common.IocExtractorException;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorCategory;
import com.iocextractor.domain.model.IndicatorType;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.Optional;

/**
 * Lookup repository backed by indexed dataframe tables.
 */
public final class JdbcLookupRepository implements LookupRepository {

    private final JdbcClient jdbc;

    public JdbcLookupRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public boolean contains(Indicator indicator) {
        if (indicator.type().category() == IndicatorCategory.NETWORK) {
            if (indicator.type() == IndicatorType.IPV4 && isBareIp(indicator.value())) {
                return exists("ip_list", "ip", indicator.value().toLowerCase(Locale.ROOT));
            }
            return exists("masks", "mask", indicator.value().toLowerCase(Locale.ROOT));
        }
        return switch (indicator.type()) {
            case MD5 -> exists("hashes", "hash_md5", indicator.value().toUpperCase(Locale.ROOT));
            case SHA1 -> exists("hashes", "hash_sha1", indicator.value().toUpperCase(Locale.ROOT));
            case SHA256 -> exists("hashes", "hash_sha256", indicator.value().toUpperCase(Locale.ROOT));
            default -> false;
        };
    }

    @Override
    public long maxId() {
        return Math.max(Math.max(maxId("masks"), maxId("ip_list")), maxId("hashes"));
    }

    @Override
    public long maxId(String artifactName) {
        String table = quote(artifactName);
        try {
            Optional<Long> value = jdbc.sql("SELECT MAX(" + quote("id") + ") FROM " + table)
                    .query(Long.class)
                    .optional();
            return value.orElse(0L) == null ? 0L : value.orElse(0L);
        } catch (RuntimeException e) {
            throw new IocExtractorException("Failed to read max id for JDBC artifact: " + artifactName, e);
        }
    }

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
