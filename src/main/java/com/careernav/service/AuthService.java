package com.careernav.service;

import com.careernav.auth.AuthContext;
import com.careernav.auth.JwtService;
import com.careernav.common.BusinessException;
import com.careernav.entity.UserEntity;
import com.careernav.repository.InterviewSessionRepository;
import com.careernav.repository.ResumeRepository;
import com.careernav.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final JwtService tokens;
    private final ResumeRepository resumes;
    private final InterviewSessionRepository interviews;
    private final DemoDataService demoData;

    public AuthService(UserRepository users, PasswordEncoder passwords, JwtService tokens,
                       ResumeRepository resumes, InterviewSessionRepository interviews,
                       DemoDataService demoData) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.resumes = resumes;
        this.interviews = interviews;
        this.demoData = demoData;
    }

    @Transactional
    public AuthResponse register(String username, String email, String password, String displayName) {
        String safeUsername = clean(username);
        String safeEmail = clean(email).toLowerCase(Locale.ROOT);
        if (safeUsername.length() < 3) throw new BusinessException("用户名至少 3 个字符");
        if (safeEmail.isBlank() || !safeEmail.contains("@")) throw new BusinessException("请输入有效邮箱");
        if (password == null || password.length() < 8) throw new BusinessException("密码至少 8 个字符");
        if (users.existsByUsernameIgnoreCase(safeUsername)) throw new BusinessException("用户名已存在");
        if (users.existsByEmailIgnoreCase(safeEmail)) throw new BusinessException("邮箱已注册");

        UserEntity user = new UserEntity();
        user.setUsername(safeUsername);
        user.setEmail(safeEmail);
        user.setPasswordHash(passwords.encode(password));
        user.setDisplayName(clean(displayName).isBlank() ? safeUsername : clean(displayName));
        user.setRole(users.count() == 0 ? "ADMIN" : "STUDENT");
        user.setEnabled(true);
        user.setCreatedAt(LocalDateTime.now());
        user = users.save(user);
        return response(user);
    }

    @Transactional
    public AuthResponse createDemoAccount(String username, String email, String password, String displayName) {
        if (!AuthContext.isAdmin()) throw new BusinessException("仅管理员可以创建演示账号");
        String safeUsername = clean(username);
        String safeEmail = clean(email).toLowerCase(Locale.ROOT);
        if (safeUsername.length() < 3) throw new BusinessException("用户名至少 3 个字符");
        if (safeEmail.isBlank() || !safeEmail.contains("@")) throw new BusinessException("请输入有效邮箱");
        if (password == null || password.length() < 8) throw new BusinessException("密码至少 8 个字符");
        if (users.existsByUsernameIgnoreCase(safeUsername)) throw new BusinessException("用户名已存在");
        if (users.existsByEmailIgnoreCase(safeEmail)) throw new BusinessException("邮箱已注册");
        UserEntity user = new UserEntity();
        user.setUsername(safeUsername);
        user.setEmail(safeEmail);
        user.setPasswordHash(passwords.encode(password));
        user.setDisplayName(clean(displayName).isBlank() ? safeUsername : clean(displayName));
        user.setRole("DEMO");
        user.setEnabled(true);
        user.setCreatedAt(LocalDateTime.now());
        user = users.save(user);
        demoData.assignUnownedLegacyData(user.getId());
        return response(user);
    }

    @Transactional
    public AuthResponse login(String account, String password) {
        String value = clean(account);
        UserEntity user = users.findByUsernameIgnoreCaseOrEmailIgnoreCase(value, value.toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new BusinessException("账号或密码错误"));
        if (!Boolean.TRUE.equals(user.getEnabled())) throw new BusinessException("账号已停用");
        if (password == null || !passwords.matches(password, user.getPasswordHash())) {
            throw new BusinessException("账号或密码错误");
        }
        user.setLastLoginAt(LocalDateTime.now());
        users.save(user);
        return response(user);
    }

    @Transactional(readOnly = true)
    public UserView me() {
        Long id = AuthContext.requireUserId();
        return view(users.findById(id).orElseThrow(() -> new BusinessException("账号不存在")));
    }

    @Transactional(readOnly = true)
    public List<UserView> listUsers() {
        if (!AuthContext.isAdmin()) throw new BusinessException("仅管理员可以查看账号列表");
        return users.findAllByOrderByCreatedAtDesc().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> adminStats() {
        if (!AuthContext.isAdmin()) throw new BusinessException("仅管理员可以查看系统统计");
        return Map.of(
                "users", users.count(),
                "admins", users.countByRole("ADMIN"),
                "students", users.countByRole("STUDENT"),
                "resumes", resumes.count(),
                "interviews", interviews.count()
        );
    }

    private AuthResponse response(UserEntity user) {
        return new AuthResponse(tokens.issue(user), view(user));
    }

    private UserView view(UserEntity user) {
        return new UserView(user.getId(), user.getUsername(), user.getEmail(), user.getDisplayName(),
                user.getRole(), Boolean.TRUE.equals(user.getEnabled()), user.getCreatedAt(), user.getLastLoginAt());
    }

    private String clean(String value) { return value == null ? "" : value.trim(); }

    public record AuthResponse(String token, UserView user) {}
    public record UserView(Long id, String username, String email, String displayName, String role,
                           boolean enabled, LocalDateTime createdAt, LocalDateTime lastLoginAt) {}
}
