/**
 * Framework-free Pipes-and-Filters contracts with sequential calling-thread execution.
 *
 * <p>{@link com.iocextractor.platform.etl.Pipeline} composes stage order;
 * {@link com.iocextractor.platform.etl.PipelineRunner} budgets element/run diagnostic
 * detail while preserving exact observed counts. Policy checkpoints follow stages,
 * and observation callbacks can fail after stage effects. Payload sharing,
 * transactions, retries, resources and cancellation remain the caller's concern.
 * See the runner and observer contracts before treating a returned outcome or
 * callback as an application completion boundary.
 */
package com.iocextractor.platform.etl;
