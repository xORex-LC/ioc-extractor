package com.iocextractor.bootstrap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class IocConfigurationOverrideReporterTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(IocConfigurationOverrideReporter.class);

    @AfterEach
    void detachAppenders() {
        logger.detachAndStopAllAppenders();
        logger.setAdditive(true);
    }

    @Test
    void reportsOnlyWinningExternalOverridesWithoutValues() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("commandLineArgs", Map.of(
                "ioc.pipeline.deduplicate", "false")));
        environment.getPropertySources().addAfter("commandLineArgs", new SystemEnvironmentPropertySource(
                "systemEnvironment", Map.of("IOC_INGESTION_STABILITY_QUIET_PERIOD", "15s")));
        environment.getPropertySources().addLast(new MapPropertySource("Config resource 'file [./configs/application.yml]'", Map.of(
                "ioc.pipeline.deduplicate", "true",
                "ioc.engine", "jdk")));
        environment.getPropertySources().addLast(new MapPropertySource(
                "Config resource 'class path resource [application.yml]'", Map.of("ioc.engine", "re2j")));
        IocConfigurationOverrideReporter reporter = new IocConfigurationOverrideReporter(environment);
        ListAppender<ILoggingEvent> appender = appender();

        reporter.reportOverrides();

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "IOC configuration override: ioc.pipeline.deduplicate <- command line",
                "IOC configuration override: ioc.ingestion.stability.quiet-period <- environment",
                "IOC configuration override: ioc.engine <- external file");
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains("false") || message.contains("15s") || message.contains("jdk"));
    }

    @Test
    void doesNotReportPackagedDefaults() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource(
                "Config resource 'class path resource [application.yml]'", Map.of("ioc.engine", "re2j")));
        IocConfigurationOverrideReporter reporter = new IocConfigurationOverrideReporter(environment);
        ListAppender<ILoggingEvent> appender = appender();

        reporter.reportOverrides();

        assertThat(appender.list).isEmpty();
    }

    @Test
    void warnsAboutCompatibilityAliasWithoutLoggingItsValue() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("commandLineArgs", Map.of(
                "ioc.sync.endpoints[0].smb.encrypt", "true")));
        IocConfigurationOverrideReporter reporter = new IocConfigurationOverrideReporter(environment);
        ListAppender<ILoggingEvent> appender = appender();

        reporter.reportOverrides();

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "IOC configuration override: ioc.sync.endpoints[0].smb.encrypt <- command line",
                "CONFIG.LEGACY_SMB_ENCRYPTION: ioc.sync.endpoints[0].smb.encrypt is accepted temporarily "
                        + "for rollback compatibility; migrate to ioc.sync.endpoints[0].smb.encryption");
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains("true"));
    }

    @Test
    void derivesIndexedNoticesFromTheSameTraversalAndKeepsOnlyFirstDeclarations() {
        AtomicInteger enumerations = new AtomicInteger();
        MapPropertySource first = new MapPropertySource("commandLineArgs", Map.of(
                "ioc.sync.endpoints[0].smb.encrypt", "private-first",
                "ioc.sync.endpoints[2].smb.encrypt", "private-second")) {
            @Override public String[] getPropertyNames() {
                enumerations.incrementAndGet();
                return super.getPropertyNames();
            }
            @Override public Object getProperty(String name) { throw new AssertionError("No value reads"); }
        };
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(first);
        environment.getPropertySources().addLast(new MapPropertySource("lower", Map.of(
                "ioc.sync.endpoints[0].smb.encrypt", "private-shadowed")));
        ListAppender<ILoggingEvent> appender = appender();

        new IocConfigurationOverrideReporter(environment).reportOverrides();

        assertThat(enumerations.get()).isEqualTo(1);
        assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                        "CONFIG.LEGACY_SMB_ENCRYPTION: ioc.sync.endpoints[0].smb.encrypt is accepted temporarily "
                                + "for rollback compatibility; migrate to ioc.sync.endpoints[0].smb.encryption",
                        "CONFIG.LEGACY_SMB_ENCRYPTION: ioc.sync.endpoints[2].smb.encrypt is accepted temporarily "
                                + "for rollback compatibility; migrate to ioc.sync.endpoints[2].smb.encryption");
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains("private-") || message.contains("lower"));
    }

    @Test
    void preservesIocWarningsForCustomEnvironmentSourceConventions() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("custom-env", Map.of(
                "IOC_SYNC_ENDPOINTS_3_SMB_ENCRYPT", "private-value")));
        ListAppender<ILoggingEvent> appender = appender();

        new IocConfigurationOverrideReporter(environment).reportOverrides();

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "IOC configuration override: ioc.sync.endpoints[3].smb.encrypt <- environment",
                "CONFIG.LEGACY_SMB_ENCRYPTION: ioc.sync.endpoints[3].smb.encrypt is accepted temporarily "
                        + "for rollback compatibility; migrate to ioc.sync.endpoints[3].smb.encryption");
    }

    @Test
    void listensBeforeApplicationRunners() throws NoSuchMethodException {
        assertThat(IocConfigurationOverrideReporter.class
                .getDeclaredMethod("onStarted", ApplicationStartedEvent.class)
                .isAnnotationPresent(org.springframework.context.event.EventListener.class)).isTrue();
    }

    private ListAppender<ILoggingEvent> appender() {
        logger.detachAndStopAllAppenders();
        logger.setAdditive(false);
        logger.setLevel(Level.TRACE);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
