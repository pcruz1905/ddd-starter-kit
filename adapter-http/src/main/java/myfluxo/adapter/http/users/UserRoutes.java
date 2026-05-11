package myfluxo.adapter.http.users;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.helidon.http.Status;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import jakarta.inject.Singleton;
import myfluxo.adapter.http.ErrorResponse;
import myfluxo.adapter.http.idempotency.HttpResult;
import myfluxo.adapter.http.idempotency.IdempotencyMiddleware;
import myfluxo.application.users.commands.RegisterUserCommand;
import myfluxo.application.users.usecases.RegisterUser;
import myfluxo.domain.users.User;
import myfluxo.domain.users.errors.UserError;
import myfluxo.domain.users.model.UserId;
import myfluxo.domain.users.UserRepository;
import myfluxo.kernel.result.Result;

/**
 * Routes for the User bounded context.
 *
 * <p>Endpoints:
 * <ul>
 *     <li>{@code POST /users}        — register a new user. Honours
 *         {@code Idempotency-Key} via {@link IdempotencyMiddleware}.</li>
 *     <li>{@code GET  /users/{id}}   — fetch a user by id.</li>
 * </ul>
 *
 * <p>This layer's only jobs are: parse the request, call the use case,
 * translate {@link Result} into an HTTP response. Domain errors are mapped
 * exhaustively. Infrastructure exceptions (optimistic concurrency, bad
 * JSON, unexpected) are handled globally by {@code ErrorHandlers}.
 */
@Singleton
public final class UserRoutes implements HttpService {

    private final RegisterUser registerUser;
    private final UserRepository users;
    private final IdempotencyMiddleware idempotency;
    private final ObjectMapper json;

    public UserRoutes(
        RegisterUser registerUser,
        UserRepository users,
        IdempotencyMiddleware idempotency,
        ObjectMapper json
    ) {
        this.registerUser = registerUser;
        this.users = users;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public void routing(HttpRules rules) {
        rules
            .post("/users", this::register)
            .get("/users/{id}", this::findById);
    }

    private void register(ServerRequest req, ServerResponse res) {
        idempotency.run(req, res, this::registerHandler);
    }

    /**
     * Pure handler: takes the buffered request body, returns the result
     * the caller should see. {@link IdempotencyMiddleware} handles
     * caching, replay, and key-reuse-with-different-body detection.
     */
    private HttpResult registerHandler(byte[] body) {
        RegisterUserRequest parsed;
        try {
            parsed = json.readValue(body, RegisterUserRequest.class);
        } catch (Exception ex) {
            return new HttpResult(Status.BAD_REQUEST_400.code(), ErrorResponse.of(
                "INVALID_JSON", "Request body is not valid JSON: " + ex.getMessage()));
        }

        var result = registerUser.handle(RegisterUserCommand.of(parsed.email()));
        return switch (result) {
            case Result.Ok<User, UserError>(User user) ->
                new HttpResult(Status.CREATED_201.code(), UserResponse.from(user));
            case Result.Err<User, UserError>(UserError error) ->
                errorResult(error);
        };
    }

    private void findById(ServerRequest req, ServerResponse res) {
        var rawId = req.path().pathParameters().first("id").orElseThrow();
        var parsed = UserId.parse(rawId);
        if (parsed instanceof Result.Err<UserId, UserId.ParseError>(var parseError)) {
            res.status(Status.BAD_REQUEST_400).send(ErrorResponse.of(
                "INVALID_USER_ID",
                "User id '" + parseError.input() + "' is not a valid UUID (" + parseError.reason() + ")"
            ));
            return;
        }
        var userId = ((Result.Ok<UserId, UserId.ParseError>) parsed).value();
        users.findById(userId).ifPresentOrElse(
            user -> res.send(UserResponse.from(user)),
            () -> res.status(Status.NOT_FOUND_404)
                .send(ErrorResponse.of("USER_NOT_FOUND", "No user with id " + userId.value()))
        );
    }

    private static HttpResult errorResult(UserError error) {
        return switch (error) {
            case UserError.EmailAlreadyTaken e ->
                new HttpResult(Status.CONFLICT_409.code(), ErrorResponse.of(
                    "EMAIL_ALREADY_TAKEN",
                    "Email already in use: " + e.email().value()));
            case UserError.InvalidEmail e ->
                new HttpResult(Status.BAD_REQUEST_400.code(), ErrorResponse.of(
                    "INVALID_EMAIL",
                    "Invalid email '" + e.input() + "': " + e.reason()));
            case UserError.UserNotFound e ->
                new HttpResult(Status.NOT_FOUND_404.code(), ErrorResponse.of(
                    "USER_NOT_FOUND",
                    "No user with id " + e.id().value()));
            case UserError.AlreadyActive e ->
                new HttpResult(Status.CONFLICT_409.code(), ErrorResponse.of(
                    "USER_ALREADY_ACTIVE",
                    "User " + e.id().value() + " is already active"));
            case UserError.AlreadyDeactivated e ->
                new HttpResult(Status.CONFLICT_409.code(), ErrorResponse.of(
                    "USER_ALREADY_DEACTIVATED",
                    "User " + e.id().value() + " is already deactivated"));
            case UserError.CannotActivateDeactivated e ->
                new HttpResult(Status.CONFLICT_409.code(), ErrorResponse.of(
                    "CANNOT_ACTIVATE_DEACTIVATED",
                    "User " + e.id().value() + " is deactivated and cannot be reactivated"));
        };
    }
}
