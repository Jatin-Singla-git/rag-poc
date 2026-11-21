package com.example.rag_poc.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Lightweight observability for the RAG pipeline: a request id (kept in the logging MDC so every log line
 * of one request carries it), per-step timings and request-level metrics.
 * <p>
 * Metrics (Micrometer, readable at /actuator/metrics):
 * <ul>
 *   <li>{@code rag.request.duration} tags kind (ask, upload) and outcome: count, total time, max;</li>
 *   <li>{@code rag.step.duration} tags step and success: timing of selection, retrieval, answer generation, ...</li>
 * </ul>
 * Only ids, counts, lengths and durations are logged, never question text, document text or keys.
 */
@Component
public class RagObservability {

    private static final Logger log = LoggerFactory.getLogger(RagObservability.class);

    /** MDC key; add %X{requestId} to the log pattern (see application.properties). */
    public static final String REQUEST_ID = "requestId";

    private final MeterRegistry registry; // may be null if no registry is available

    public RagObservability(ObjectProvider<MeterRegistry> registryProvider) {
        this.registry = registryProvider.getIfAvailable();
    }

    /** One tracked request (an /ask question or an upload). */
    public final class Request {
        private final String kind;
        private final String requestId;
        private final long startNanos = System.nanoTime();

        private Request(String kind, String requestId) {
            this.kind = kind;
            this.requestId = requestId;
        }

        public void finish(String outcome, String detail) {
            long nanos = System.nanoTime() - startNanos;
            log.info("{} finished: requestId={} outcome={} {} durationMs={}", kind, requestId, outcome,
                    detail == null ? "" : detail, millis(nanos));
            recordRequest(kind, outcome, nanos);
            MDC.remove(REQUEST_ID);
        }

        public void fail(Throwable error) {
            long nanos = System.nanoTime() - startNanos;
            String status = error instanceof ResponseStatusException rse
                    ? " httpStatus=" + rse.getStatusCode().value() : "";
            log.warn("{} failed: requestId={} exception={}{} durationMs={}", kind, requestId,
                    error.getClass().getSimpleName(), status, millis(nanos));
            recordRequest(kind, "error", nanos);
            MDC.remove(REQUEST_ID);
        }
    }

    /** Starts tracking a request: creates its id, puts it in the MDC and logs the start. */
    public Request start(String kind, String detail) {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put(REQUEST_ID, requestId);
        log.info("{} started: requestId={} {}", kind, requestId, detail == null ? "" : detail);
        return new Request(kind, requestId);
    }

    /** Runs a step, logs its duration (and failure) and records it. Exceptions are rethrown unchanged. */
    public <T> T timed(String step, String detail, Supplier<T> work) {
        return timed(step, detail, work, null);
    }

    /** As above; {@code describe} adds a short result summary (counts only) to the log line. */
    public <T> T timed(String step, String detail, Supplier<T> work, Function<T, String> describe) {
        long start = System.nanoTime();
        boolean ok = false;
        T result = null;
        try {
            result = work.get();
            ok = true;
            return result;
        } finally {
            long nanos = System.nanoTime() - start;
            String summary = "";
            if (ok && describe != null) {
                try {
                    summary = " " + describe.apply(result);
                } catch (RuntimeException ignored) {
                    // describing the result must never affect the request
                }
            }
            log.info("Step {}: {}{} durationMs={}{}", step, detail == null ? "" : detail, summary,
                    millis(nanos), ok ? "" : " FAILED");
            recordStep(step, ok, nanos);
        }
    }

    private void recordRequest(String kind, String outcome, long nanos) {
        if (registry != null) {
            Timer.builder("rag.request.duration").tag("kind", kind).tag("outcome", outcome)
                    .register(registry).record(Duration.ofNanos(nanos));
        }
    }

    private void recordStep(String step, boolean success, long nanos) {
        if (registry != null) {
            Timer.builder("rag.step.duration").tag("step", step).tag("success", String.valueOf(success))
                    .register(registry).record(Duration.ofNanos(nanos));
        }
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000;
    }
}
