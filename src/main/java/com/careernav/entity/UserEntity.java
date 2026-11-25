package com.careernav.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "app_users", uniqueConstraints = {
        @UniqueConstraint(name = "uk_app_user_username", columnNames = "username"),
        @UniqueConstraint(name = "uk_app_user_email", columnNames = "email")
})
public class UserEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 40) private String username;
    @Column(nullable = false, length = 160) private String email;
    @Column(nullable = false, length = 100) private String passwordHash;
    @Column(length = 80) private String displayName;
    @Column(nullable = false, length = 20) private String role;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime lastLoginAt;

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public String getUsername() { return username; } public void setUsername(String v) { username = v; }
    public String getEmail() { return email; } public void setEmail(String v) { email = v; }
    public String getPasswordHash() { return passwordHash; } public void setPasswordHash(String v) { passwordHash = v; }
    public String getDisplayName() { return displayName; } public void setDisplayName(String v) { displayName = v; }
    public String getRole() { return role; } public void setRole(String v) { role = v; }
    public Boolean getEnabled() { return enabled; } public void setEnabled(Boolean v) { enabled = v; }
    public LocalDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(LocalDateTime v) { createdAt = v; }
    public LocalDateTime getLastLoginAt() { return lastLoginAt; } public void setLastLoginAt(LocalDateTime v) { lastLoginAt = v; }
}
