package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.dataframeimport.model.ImportCommitEvidence;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.port.out.dataframeimport.ImportCommitEvidenceStore;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Receipt reads use committed views; native cascading purge publishes all child tombstones atomically. */
final class JdbcStagedImportEvidenceStore implements ImportCommitEvidenceStore {
    private final JdbcStagedCanonicalBackend backend;
    private final JdbcImportCommitEvidenceStore reader;
    JdbcStagedImportEvidenceStore(JdbcStagedCanonicalBackend backend) {
        this.backend = backend; reader = new JdbcImportCommitEvidenceStore(backend.configuration().dataSource());
    }
    public Optional<ImportCommitEvidence> find(ImportDeliveryId delivery) { return reader.find(delivery); }
    public void purge(ImportDeliveryId delivery) {
        new JdbcStagedControl(backend).execute(Set.of("import-receipt"), JdbcWriterAdmission.OperationClass.MAINTENANCE,
                (selection, base, slots) -> {
                    for (String table : List.of("import_commit", "import_commit_artifact", "import_row_rejection", "import_row_warning", "import_slot_resolution")) {
                        base.copy(table, "b.delivery_id=?", List.of(delivery.value()));
                    }
                }, (working, context, asOf) -> { new JdbcImportCommitEvidenceStore(working).purge(delivery); return null; });
    }
}
