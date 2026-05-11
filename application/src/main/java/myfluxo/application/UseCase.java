package myfluxo.application;

import myfluxo.kernel.result.Result;

/**
 * Application service contract. A use case is the entry point for a
 * business operation — it takes a command (the application-layer input
 * shape), orchestrates domain operations inside a {@link UnitOfWork},
 * and returns either the produced domain value or a typed domain error.
 *
 * <p>Every use case in the codebase should implement this interface so
 * that:
 * <ul>
 *     <li>Tooling can wrap them generically (logging, metrics,
 *         distributed tracing, retry policies, structured audit).</li>
 *     <li>Adapters dispatch them uniformly without knowing the concrete
 *         class.</li>
 *     <li>The HTTP layer has a single shape to map onto: command in,
 *         result out.</li>
 * </ul>
 *
 * <h2>Type parameters</h2>
 * <ul>
 *     <li>{@code C} — command record. Application-layer DTO; flat data
 *         from the caller (HTTP, CLI, message handler).</li>
 *     <li>{@code R} — success value. Typically a domain aggregate or
 *         an aggregate id.</li>
 *     <li>{@code E} — typed error union. A sealed domain error interface
 *         (e.g. {@code UserError}) so the compiler enforces exhaustive
 *         handling at every call site.</li>
 * </ul>
 *
 * <p>Implementations should be {@code @Singleton} and stateless.
 * Per-request mutable state lives on the command/result, never on the
 * use case.
 */
public interface UseCase<C, R, E> {

    Result<R, E> handle(C command);
}
