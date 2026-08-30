package com.iocextractor.bootstrap;

import com.iocextractor.application.dataframeimport.model.ImportSourceTransport;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Calculates the configured application-owned SMB session demand without probing the server. */
final class SmbSessionDemandPlanner {

    private SmbSessionDemandPlanner() {
    }

    static Map<String, Integer> plan(IocProperties properties) {
        Set<String> pooledEndpoints = new HashSet<>();
        Map<String, Integer> watchSessions = new HashMap<>();
        collectSyncDemand(properties, pooledEndpoints, watchSessions);
        collectImportDemand(properties, pooledEndpoints, watchSessions);

        Map<String, Integer> planned = new HashMap<>();
        pooledEndpoints.forEach(endpoint -> planned.put(endpoint, 1));
        watchSessions.forEach((endpoint, count) -> planned.merge(endpoint, count, Integer::sum));
        return Map.copyOf(planned);
    }

    private static void collectSyncDemand(
            IocProperties properties,
            Set<String> pooledEndpoints,
            Map<String, Integer> watchSessions) {
        IocProperties.Sync sync = properties.sync();
        if (!sync.enabled()) {
            return;
        }
        if (sync.fetch().enabled()) {
            sync.fetch().sources().forEach(source -> {
                pooledEndpoints.add(source.endpoint());
                if (source.changeNotify().enabled()) {
                    watchSessions.merge(source.endpoint(), 1, Integer::sum);
                }
            });
        }
        if (sync.publish().enabled()) {
            sync.publish().targets().forEach(target -> pooledEndpoints.add(target.endpoint()));
        }
    }

    private static void collectImportDemand(
            IocProperties properties,
            Set<String> pooledEndpoints,
            Map<String, Integer> watchSessions) {
        IocProperties.DataframeImport dataframeImport = properties.dataframeImport();
        if (!dataframeImport.enabled()) {
            return;
        }
        boolean changeNotifications = dataframeImport.runtime().detect().useChangeNotifications();
        dataframeImport.sources().stream()
                .filter(source -> source.transport() == ImportSourceTransport.SMB)
                .forEach(source -> {
                    pooledEndpoints.add(source.endpoint());
                    if (changeNotifications) {
                        watchSessions.merge(source.endpoint(), 1, Integer::sum);
                    }
                });
    }
}
