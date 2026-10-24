/**
 * Framework-free control-event model and publish-only contracts.
 *
 * <p>Services supply concrete payloads and metadata; delivery adapters define execution and
 * failure behavior. The JDK-only package supplies a publication port, observational callbacks,
 * stateless no-ops and an unbounded recording test double. It owns no subscriptions, executor,
 * wire format, delivery ledger or recovery process.</p>
 */
package com.iocextractor.platform.events;
