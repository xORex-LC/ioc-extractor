package com.iocextractor.application.tck.artifact;

import com.iocextractor.application.tck.junit.ContractTest;
import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.port.out.artifact.ArtifactIdentityStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reusable identity epoch and drift contract for canonical storage adapters. */
@ContractTest
public abstract class CanonicalIdentityStoreContractTest {

    /** Creates an isolated store with an empty {@code masks(mask, source)} artifact. */
    protected abstract ArtifactIdentityStore createIdentityStore();

    @Test
    void registers_and_replays_one_formula_idempotently() {
        ArtifactIdentityStore store = createIdentityStore();
        ArtifactIdentityDefinition definition = definition(List.of("mask"), 1);

        var first = store.ensure(definition);
        var replay = store.ensure(definition);

        assertThat(first).isEqualTo(replay);
        assertThat(first.identityHash()).isEqualTo(definition.identityHash());
        assertThat(first.epoch()).isOne();
    }

    @Test
    void rejects_formula_drift_without_epoch_bump() {
        ArtifactIdentityStore store = createIdentityStore();
        store.ensure(definition(List.of("mask"), 1));

        assertThatThrownBy(() -> store.ensure(definition(List.of("mask", "source"), 1)))
                .hasMessageContaining("IDENTITY_DRIFT");
    }

    @Test
    void accepts_formula_change_with_monotonic_epoch_bump() {
        ArtifactIdentityStore store = createIdentityStore();
        store.ensure(definition(List.of("mask"), 1));
        ArtifactIdentityDefinition next = definition(List.of("mask", "source"), 2);

        var stored = store.ensure(next);

        assertThat(stored.identityHash()).isEqualTo(next.identityHash());
        assertThat(stored.epoch()).isEqualTo(2);
    }

    private ArtifactIdentityDefinition definition(List<String> columns, int epoch) {
        return new ArtifactIdentityDefinition("masks", columns, false, epoch);
    }
}
