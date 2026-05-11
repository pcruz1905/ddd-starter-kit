package myfluxo.adapter.http.users;

/**
 * Wire-level shape for the body of {@code POST /users}.
 */
public record RegisterUserRequest(String email) {}
