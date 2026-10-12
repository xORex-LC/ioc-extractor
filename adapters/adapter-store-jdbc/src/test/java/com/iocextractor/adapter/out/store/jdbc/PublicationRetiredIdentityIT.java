package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.tck.junit.IntegrationTest;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Header compaction preserves authority across retirement, restart and a late duplicate attempt. */
@IntegrationTest
@Timeout(45)
class PublicationRetiredIdentityIT {
    @TempDir Path root;

    @Test
    void retiredPromotionCannotRunAgainAndChangedPinsFailAfterReopen() throws Exception {
        var unit = new JdbcStagedPublication.Unit("document:empty-promotion", "exact-pins", Set.of("artifact:masks"), true);
        try (var f = new StagedPublicationFixture(root, true, JdbcPublicationObserver.NOOP)) {
            assertThat(f.backend.publication().execute(unit, empty())).isTrue();
            var maintenance = new JdbcPublicationMaintenance(f.backend);
            for (int i = 0; i < 96; i++) { maintenance.tick(128); }
            assertThat(f.number("SELECT COUNT(*) FROM publication_retired_identity")).isOne();
            assertThat(f.number("SELECT COUNT(*) FROM publication_unit")).isZero();
            assertThat(f.number("SELECT COUNT(*) FROM publication_attempt")).isZero();
            var reopened = JdbcStagedCanonicalBackend.reopen(f.backend.configuration(), JdbcPublicationObserver.NOOP);
            assertThat(reopened.publication().execute(unit, unexpected())).isFalse();
            var changed = new JdbcStagedPublication.Unit(unit.identity(), "changed", unit.scopes(), true);
            assertThatThrownBy(() -> reopened.publication().execute(changed, unexpected()))
                    .hasMessageContaining("publication failed").hasRootCauseMessage("Canonical publication pins changed");
            // Even a caller racing the pre-check cannot allocate a second physical unit.
            try (var c = f.dataframe.getConnection()) {
                c.setAutoCommit(false);
                assertThatThrownBy(() -> new JdbcPublicationProtocol().begin(c, unit.identity(), unit.pins(),
                        new JdbcPublicationProtocol.Manifest(0, 0, Long.MAX_VALUE, Map.of("artifact:masks", 0L))))
                        .isInstanceOf(SQLException.class).hasMessageContaining("cannot be republished");
                c.rollback();
            }
        }
    }

    @Test
    void liveFactsAndValidityAnchorsPreventHeaderRetirement() throws Exception {
        try (var f = new StagedPublicationFixture(root, true, JdbcPublicationObserver.NOOP)) {
            f.seed();
            var before = f.facts("publication_unit");
            var maintenance = new JdbcPublicationMaintenance(f.backend);
            for (int i = 0; i < 96; i++) { maintenance.tick(128); }
            assertThat(f.number("SELECT COUNT(*) FROM publication_retired_identity")).isZero();
            assertThat(f.facts("publication_unit")).isEqualTo(before);
            assertThat(f.number("SELECT COUNT(*) FROM masks")).isOne();
        }
    }

    private static JdbcStagedPublication.NativeTask empty() {
        return new JdbcStagedPublication.NativeTask() {
            public void hydrate(JdbcPublicationSelection selection, JdbcSelectedCanonicalBase base,
                    JdbcPublicationSlotBase slots, EffectiveTime asOf) { }
            public void prepare(DataSource working, JdbcCanonicalPreparationContext context, EffectiveTime asOf) { }
        };
    }

    private static JdbcStagedPublication.NativeTask unexpected() {
        return new JdbcStagedPublication.NativeTask() {
            public void hydrate(JdbcPublicationSelection selection, JdbcSelectedCanonicalBase base,
                    JdbcPublicationSlotBase slots, EffectiveTime asOf) { throw new AssertionError("Retired delivery was prepared again"); }
            public void prepare(DataSource working, JdbcCanonicalPreparationContext context, EffectiveTime asOf) { throw new AssertionError("Retired delivery ran again"); }
        };
    }
}
