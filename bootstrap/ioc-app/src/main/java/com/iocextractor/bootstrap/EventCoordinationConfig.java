package com.iocextractor.bootstrap;

import com.iocextractor.platform.events.ControlEventObserver;
import com.iocextractor.platform.events.ControlEventPublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root segment for control-event publication and observation. */
@Configuration
public class EventCoordinationConfig {

    @Bean
    public ControlEventObserver controlEventObserver() {
        return new LoggingControlEventObserver();
    }

    @Bean
    public ControlEventPublisher controlEventPublisher(ApplicationEventPublisher publisher,
                                                       ControlEventObserver observer) {
        return new SpringControlEventPublisher(publisher, observer);
    }
}
