package myfluxo.adapter.http;

import io.helidon.http.Status;
import io.helidon.webserver.http.HttpRouting;
import myfluxo.kernel.aggregate.OptimisticConcurrencyException;
import myfluxo.kernel.id.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Global error handlers installed on the {@link HttpRouting}. They turn
 * any uncaught {@link Throwable} into a sanitized {@link ErrorResponse}.
 *
 * <p>The rules:
 * <ol>
 *     <li>A typed domain error reached HTTP via {@code Result.Err} — that
 *         path already returned a sanitized response before we got here,
 *         and we never see those exceptions. ✓</li>
 *     <li>An {@link OptimisticConcurrencyException} → 409 with a fixed,
 *         non-leaking message.</li>
 *     <li>An {@link IllegalArgumentException} → 400 (typically thrown by
 *         a value-object compact constructor when the body is shaped
 *         wrong). We pass through the message — those are designed-to-be-
 *         seen "Invalid email", "id is not a valid UUID", etc.</li>
 *     <li>Anything else (NPE, JDBC error, OOM, framework bug, …) →
 *         <strong>500 with a sanitized message</strong>. We log the full
 *         exception with a correlation id; the client sees only the id,
 *         not the exception details.</li>
 * </ol>
 *
 * <p>This is the airlock between server internals and the wire. Don't
 * leak past it.
 */
public final class ErrorHandlers {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorHandlers.class);

    private ErrorHandlers() {}

    public static void install(HttpRouting.Builder routing) {
        routing.error(OptimisticConcurrencyException.class, ErrorHandlers::handleOptimisticConcurrency);
        routing.error(IllegalArgumentException.class, ErrorHandlers::handleBadArgument);
        routing.error(Throwable.class, ErrorHandlers::handleUnexpected);
    }

    private static void handleOptimisticConcurrency(
        io.helidon.webserver.http.ServerRequest req,
        io.helidon.webserver.http.ServerResponse res,
        OptimisticConcurrencyException ex
    ) {
        // Don't include the version number — that's an internal concept.
        res.status(Status.CONFLICT_409).send(ErrorResponse.of(
            "OPTIMISTIC_CONCURRENCY",
            "The resource was modified concurrently. Reload and retry."
        ));
    }

    private static void handleBadArgument(
        io.helidon.webserver.http.ServerRequest req,
        io.helidon.webserver.http.ServerResponse res,
        IllegalArgumentException ex
    ) {
        // These come from compact-constructor validation on records.
        // The messages are designed to be human-friendly; pass through.
        res.status(Status.BAD_REQUEST_400).send(ErrorResponse.of(
            "BAD_REQUEST",
            ex.getMessage() == null ? "Request was malformed." : ex.getMessage()
        ));
    }

    private static void handleUnexpected(
        io.helidon.webserver.http.ServerRequest req,
        io.helidon.webserver.http.ServerResponse res,
        Throwable ex
    ) {
        String correlationId = newCorrelationId();
        // Log the FULL exception with the correlation id so support can
        // grep it out of the logs when a user reports the issue.
        LOG.error("Unhandled exception serving {} {} [correlationId={}]",
            req.prologue().method(), req.prologue().uriPath(), correlationId, ex);

        // Never leak the exception type, message, or stack trace.
        res.status(Status.INTERNAL_SERVER_ERROR_500).send(ErrorResponse.withCorrelation(
            "INTERNAL_ERROR",
            "An unexpected error occurred. Reference id: " + correlationId,
            correlationId
        ));
    }

    private static String newCorrelationId() {
        return UuidV7.generate().toString();
    }
}
