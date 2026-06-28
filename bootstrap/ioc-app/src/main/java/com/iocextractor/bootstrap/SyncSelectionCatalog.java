package com.iocextractor.bootstrap;

import com.iocextractor.application.port.in.sync.ArtifactPublishCommand;
import com.iocextractor.application.port.in.sync.RemoteFetchCommand;
import com.iocextractor.application.port.in.sync.ValidateSyncSelectionUseCase;

import java.util.List;
import java.util.Objects;

/** IO-free catalog-backed preflight for operator sync commands. */
public final class SyncSelectionCatalog implements ValidateSyncSelectionUseCase {

    private final IocProperties.Sync sync;

    /** Captures the already validated sync configuration without resolving storage or transports. */
    public SyncSelectionCatalog(IocProperties properties) {
        this.sync = Objects.requireNonNull(properties, "properties").sync();
    }

    @Override
    public void validateFetch(RemoteFetchCommand command) {
        Objects.requireNonNull(command, "command");
        requireEnabled(sync.fetch().enabled(), "fetch");
        validateEndpoint(command.endpoint().orElse(null));
        List<IocProperties.Sync.Fetch.Source> matches = sync.fetch().sources().stream()
                .filter(source -> command.source()
                        .map(selected -> source.name().equals(selected))
                        .orElse(true))
                .filter(source -> command.endpoint()
                        .map(selected -> source.endpoint().equals(selected))
                        .orElse(true))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No sync fetch source matches selection");
        }
    }

    @Override
    public void validatePublish(ArtifactPublishCommand command) {
        Objects.requireNonNull(command, "command");
        requireEnabled(sync.publish().enabled(), "publish");
        validateEndpoint(command.endpoint().orElse(null));
        List<IocProperties.Sync.Publish.Target> matches = sync.publish().targets().stream()
                .filter(target -> command.profile()
                        .map(selected -> target.exportProfile().equals(selected))
                        .orElse(true))
                .filter(target -> command.target()
                        .map(selected -> target.name().equals(selected))
                        .orElse(true))
                .filter(target -> command.endpoint()
                        .map(selected -> target.endpoint().equals(selected))
                        .orElse(true))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No sync publish target matches selection");
        }
    }

    private void requireEnabled(boolean operationEnabled, String operation) {
        if (!sync.enabled()) {
            throw new IllegalStateException("Remote sync is disabled (ioc.sync.enabled=false)");
        }
        if (!operationEnabled) {
            throw new IllegalStateException("Remote sync " + operation + " is disabled");
        }
    }

    private void validateEndpoint(String endpoint) {
        if (endpoint == null) {
            return;
        }
        boolean known = sync.endpoints().stream().anyMatch(candidate -> candidate.name().equals(endpoint));
        if (!known) {
            throw new IllegalArgumentException("Unknown sync endpoint: " + endpoint);
        }
    }
}
