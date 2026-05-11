package myfluxo.adapter.http.auth;

public record ChangePasswordRequest(String oldPassword, String newPassword) {}
