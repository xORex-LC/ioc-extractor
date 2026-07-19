package com.iocextractor.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
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

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        RuntimeMode mode = RuntimeMode.parse(environment.getProperty("ioc.runtime.mode", RuntimeMode.ONESHOT_VALUE));
        if (mode.isDaemon()) {
            environment.getPropertySources().addFirst(new MapPropertySource(
                    PROPERTY_SOURCE_NAME,
                    Map.of(
                            "spring.main.web-application-type", "servlet",
                            "spring.main.lazy-initialization", "false")));
            return;
        }
        environment.getPropertySources().addFirst(new MapPropertySource(
                PROPERTY_SOURCE_NAME,
                Map.of("spring.main.lazy-initialization", "true")));
    }

    @Override
    public int getOrder() {
        // After config data processing, so a mode set in application.yml is visible.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
