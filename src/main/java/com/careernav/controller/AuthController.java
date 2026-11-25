package com.careernav.controller;

import com.careernav.common.ApiResponse;
import com.careernav.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService service;

    public AuthController(AuthService service) { this.service = service; }

    @PostMapping("/register")
    public ApiResponse<AuthService.AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok("注册成功", service.register(
                request.username(), request.email(), request.password(), request.displayName()));
    }

    @PostMapping("/login")
    public ApiResponse<AuthService.AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok("登录成功", service.login(request.account(), request.password()));
    }

    @GetMapping("/me")
    public ApiResponse<AuthService.UserView> me() { return ApiResponse.ok(service.me()); }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() { return ApiResponse.ok("已退出登录", null); }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok(Map.of("authentication", "bearer", "registration", true));
    }

    public record RegisterRequest(@NotBlank @Size(min = 3, max = 40) String username,
                                  @NotBlank @Email String email,
                                  @NotBlank @Size(min = 8, max = 80) String password,
                                  String displayName) {}
    public record LoginRequest(@NotBlank String account, @NotBlank String password) {}
}
