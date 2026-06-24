package com.iocextractor.bootstrap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Active in daemon mode when the ingestion ledger uses the legacy file backend
 * ({@code ioc.ingestion.ledger.type=file}, the default). Counterpart to
 * {@link ConditionalOnJdbcLedger}.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon' && '${ioc.ingestion.ledger.type:file}' == 'file'")
public @interface ConditionalOnFileLedger {
}
