/**
 * A small scalar ECS profile with shared field meanings and outcome vocabulary.
 *
 * <p>The application owns its encoder, runtime metadata, dataset and custom
 * namespace. With a Throwable, the Boot ECS encoder also owns error.*. Services
 * check reserved paths and scalar/object conflicts; this package is not a complete
 * ECS schema validator and does not provide type/category arrays or tracing.
 */
package com.iocextractor.platform.logging.ecs;
