package com.iocextractor.bootstrap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Active for the dataframe storage foundation. The default is disabled until
 * the business-data truth switch is wired; tests and deployments can opt in
 * explicitly with {@code ioc.storage.dataframe.type=jdbc}.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon' && '${ioc.storage.dataframe.type:disabled}' == 'jdbc'")
public @interface ConditionalOnDataframeStorage {
}
