package com.iocextractor.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Enables a servlet web server only in daemon mode, exposing the actuator/health
 * surface (ING-3). CLI/oneshot runs stay non-web (default
 * {@code spring.main.web-application-type=none}) so a one-shot extract never
 * starts — or blocks on — an HTTP server.
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
        String mode = environment.getProperty("ioc.runtime.mode", "oneshot");
        if ("daemon".equalsIgnoreCase(mode)) {
            environment.getPropertySources().addFirst(new MapPropertySource(
                    PROPERTY_SOURCE_NAME,
                    Map.of("spring.main.web-application-type", "servlet")));
        }
    }

    @Override
    public int getOrder() {
        // After config data processing, so a mode set in application.yml is visible.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
