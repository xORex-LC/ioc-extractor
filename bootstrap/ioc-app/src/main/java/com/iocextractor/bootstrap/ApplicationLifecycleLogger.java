package com.iocextractor.bootstrap;

import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.env.Environment;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Emits application lifecycle log events after Spring logging is configured.
 */
@Component
public final class ApplicationLifecycleLogger {

    private static final Logger log = LoggerFactory.getLogger(ApplicationLifecycleLogger.class);

    private final IocProperties properties;
    private final Environment environment;

    public ApplicationLifecycleLogger(IocProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @EventListener
    public void onReady(ApplicationReadyEvent event) {
        LogEvents.info(log)
                .action(EventAction.APP_START)
                .outcome(EventOutcome.SUCCESS)
                .field(LogField.IOC_MODE, properties.observability().mode())
                .message("application started" + healthSuffix())
                .log();
    }

    /** When the daemon web surface is on, tell ops where health lives. */
    private String healthSuffix() {
        if (!"servlet".equalsIgnoreCase(environment.getProperty("spring.main.web-application-type", "none"))) {
            return "";
        }
        String address = environment.getProperty("server.address", "127.0.0.1");
        String port = environment.getProperty("server.port", "8081");
        return "; health at http://" + address + ":" + port + "/actuator/health";
    }

    @EventListener
    public void onClosed(ContextClosedEvent event) {
        LogEvents.info(log)
                .action(EventAction.APP_STOP)
                .outcome(EventOutcome.SUCCESS)
                .field(LogField.IOC_MODE, properties.observability().mode())
                .message("application stopped")
                .log();
    }
}
