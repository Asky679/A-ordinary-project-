package com.careernav.controller;

import com.careernav.common.ApiResponse;
import com.careernav.service.AuthService;
import com.careernav.service.DemoDataService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AuthService service;
    private final DemoDataService demoData;

    public AdminController(AuthService service, DemoDataService demoData) {
        this.service = service;
        this.demoData = demoData;
    }

    @GetMapping("/users")
    public ApiResponse<List<AuthService.UserView>> users() { return ApiResponse.ok(service.listUsers()); }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() { return ApiResponse.ok(service.adminStats()); }

    @GetMapping("/demo-data/stats")
    public ApiResponse<Map<String, Object>> demoStats() { return ApiResponse.ok(demoData.stats()); }

    @PostMapping("/demo-account")
    public ApiResponse<AuthService.AuthResponse> createDemoAccount(
            @Valid @RequestBody DemoAccountRequest request) {
        return ApiResponse.ok("演示账号已创建", service.createDemoAccount(
                request.username(), request.email(), request.password(), request.displayName()));
    }

    @DeleteMapping("/demo-data")
    public ApiResponse<Map<String, Object>> clearDemoData() {
        return ApiResponse.ok("演示数据已清除", demoData.clear());
    }

    public record DemoAccountRequest(
            @NotBlank(message = "请输入演示账号用户名") @Size(min = 3, max = 40) String username,
            @NotBlank(message = "请输入演示账号邮箱") @Email(message = "请输入有效邮箱") String email,
            @NotBlank(message = "请输入演示账号密码") @Size(min = 8, max = 100) String password,
            @Size(max = 80) String displayName) {}
}
