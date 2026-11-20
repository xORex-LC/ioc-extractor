/**
 * SLF4J event fields and restoring MDC scopes with no service catalog dependency.
 *
 * <p>Services declare immutable {@link com.iocextractor.platform.logging.LogFieldSpec}
 * values, select their provider and own field disclosure. Generic fields check
 * scalar representation; the small ECS helpers supply additional semantics.
 * Same-thread scopes restore touched keys; workers reconstruct context explicitly.
 */
package com.iocextractor.platform.logging;
