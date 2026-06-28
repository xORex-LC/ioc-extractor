package com.iocextractor.bootstrap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Active when the service-state role uses the JDBC storage adapter. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnExpression("'${ioc.storage.service.type:disabled}' == 'jdbc'")
public @interface ConditionalOnServiceStorage {
}
