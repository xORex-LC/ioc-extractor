package com.iocextractor.bootstrap;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.validation.Validator;

/**
 * Early configuration-properties validator wiring for the {@code ioc.*} root.
 */
@Configuration(proxyBeanMethods = false)
class ConfigPreflightConfiguration {

    @Bean(name = EnableConfigurationProperties.VALIDATOR_BEAN_NAME)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Validator configurationPropertiesValidator() {
        return new IocConfigPreflight();
    }
}
