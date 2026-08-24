package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.port.in.dataframeimport.ProcessNextDataframeImportResult;
import com.iocextractor.application.port.in.dataframeimport.RecoverDataframeImportsResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

class DataframeImportRecoveryServiceTest {

    @Test
    void reconcilesClaimsThenDrainsOnlyBoundedDurableHeadWork() {
        Queue<ProcessNextDataframeImportResult> attempts = new ArrayDeque<>();
        attempts.add(performed("delivery-1"));
        attempts.add(idle());
        var service = new DataframeImportRecoveryService(
                ignored -> new RecoverDataframeImportsResult(2, 1, 0),
                attempts::remove);

        RecoverDataframeImportsResult result = service.recover(10);

        assertThat(result).isEqualTo(new RecoverDataframeImportsResult(4, 2, 0));
        assertThat(attempts).isEmpty();
    }

    @Test
    void stopsAtFirstContradictionAndReportsFailClosedEvidence() {
        var service = new DataframeImportRecoveryService(
                ignored -> new RecoverDataframeImportsResult(1, 0, 0),
                () -> {
                    throw new DataframeImportConsistencyException("contradiction");
                });

        RecoverDataframeImportsResult result = service.recover(10);

        assertThat(result).isEqualTo(new RecoverDataframeImportsResult(2, 0, 1));
    }

    private ProcessNextDataframeImportResult performed(String id) {
        return new ProcessNextDataframeImportResult(
                true, Optional.of(new ImportDeliveryId(id)));
    }

    private ProcessNextDataframeImportResult idle() {
        return new ProcessNextDataframeImportResult(false, Optional.empty());
    }
}
