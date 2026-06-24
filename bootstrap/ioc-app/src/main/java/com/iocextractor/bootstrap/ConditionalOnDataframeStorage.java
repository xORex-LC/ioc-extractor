package com.iocextractor.bootstrap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Active when dataframe business data uses JDBC as the source of truth. Runtime
 * mode is intentionally not part of this condition: both oneshot and daemon
 * must switch truth storage together.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnExpression("'${ioc.storage.dataframe.type:disabled}' == 'jdbc'")
public @interface ConditionalOnDataframeStorage {
}
