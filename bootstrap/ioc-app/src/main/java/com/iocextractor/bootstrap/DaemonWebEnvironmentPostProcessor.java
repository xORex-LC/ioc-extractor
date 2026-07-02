package com.iocextractor.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Applies early Spring runtime settings that differ between daemon and oneshot modes.
 *
 * <p>Daemon mode enables the actuator web surface and forces eager singleton initialization.
 * CLI/oneshot runs stay non-web and use lazy initialization so parsing help or a lightweight
 * command does not resolve storage, transport and use-case graphs.</p>
 *
 * <p>Gated on {@code ioc.runtime.mode} rather than a Spring profile: the systemd
 * unit sets only {@code --ioc.runtime.mode=daemon}, and {@code spring.main.*} is
 * bound from the environment after environment post-processors run, so flipping
 * the property here is honored. This also seeds the web driving-adapter seam
 * (ING-8) for future REST endpoints.
 */
public class DaemonWebEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "iocDaemonWeb";
    private static final String DATA_SOURCE_AUTO_CONFIGURATION =
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration";
    private static final String ONESHOT_AUTO_CONFIGURATION_EXCLUSIONS = String.join(",",
            DATA_SOURCE_AUTO_CONFIGURATION,
            "org.springframework.boot.actuate.autoconfigure.jdbc.DataSourceHealthContributorAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.metrics.jdbc.DataSourcePoolMetricsAutoConfiguration",
            "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
            "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration",
            "org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String mode = environment.getProperty("ioc.runtime.mode", "oneshot");
        if ("daemon".equalsIgnoreCase(mode)) {
            environment.getPropertySources().addFirst(new MapPropertySource(
                    PROPERTY_SOURCE_NAME,
                    Map.of(
                            "spring.main.web-application-type", "servlet",
                            "spring.main.lazy-initialization", "false",
                            "spring.autoconfigure.exclude", DATA_SOURCE_AUTO_CONFIGURATION)));
            return;
        }
        environment.getPropertySources().addFirst(new MapPropertySource(
                PROPERTY_SOURCE_NAME,
                Map.of(
                        "spring.main.lazy-initialization", "true",
                        "spring.autoconfigure.exclude", ONESHOT_AUTO_CONFIGURATION_EXCLUSIONS)));
    }

    @Override
    public int getOrder() {
        // After config data processing, so a mode set in application.yml is visible.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
