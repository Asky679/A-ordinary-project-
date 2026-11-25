package com.careernav.service;

import com.careernav.auth.CurrentUser;
import com.careernav.auth.JwtService;
import com.careernav.common.BusinessException;
import com.careernav.entity.ResumeEntity;
import com.careernav.entity.UserEntity;
import com.careernav.repository.EducationVerificationRepository;
import com.careernav.repository.InterviewSessionRepository;
import com.careernav.repository.ResumeRepository;
import com.careernav.repository.ResumeVersionRepository;
import com.careernav.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthAndUserIsolationTest {
    @AfterEach
    void clearSecurity() { SecurityContextHolder.clearContext(); }

    @Test
    void firstRegisteredUserBecomesAdminAndPasswordIsHashed() {
        UserRepository users = mock(UserRepository.class);
        when(users.count()).thenReturn(0L);
        when(users.existsByUsernameIgnoreCase("alice")).thenReturn(false);
        when(users.existsByEmailIgnoreCase("alice@example.com")).thenReturn(false);
        when(users.save(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(1L);
            return user;
        });
        JwtService jwt = new JwtService("test-secret", 24);
        AuthService service = new AuthService(users, new BCryptPasswordEncoder(4), jwt,
                mock(ResumeRepository.class), mock(InterviewSessionRepository.class), mock(DemoDataService.class));

        AuthService.AuthResponse response = service.register(
                "alice", "alice@example.com", "password123", "Alice");

        assertEquals("ADMIN", response.user().role());
        assertTrue(jwt.parse(response.token()).isPresent());
        verify(users).save(any(UserEntity.class));
    }

    @Test
    void resumeListAndGetAreScopedToCurrentStudent() {
        ResumeRepository repository = mock(ResumeRepository.class);
        EducationVerificationRepository verifications = mock(EducationVerificationRepository.class);
        InterviewSkillEvidenceService evidence = mock(InterviewSkillEvidenceService.class);
        ResumeService service = new ResumeService(repository, mock(ResumeVersionRepository.class),
                verifications, evidence, new JsonCodec(new ObjectMapper()));

        ResumeEntity own = new ResumeEntity();
        own.setId(1L);
        own.setUserId(7L);
        own.setOwnerName("Current");
        own.setTargetRole("Java");
        own.setCreatedAt(LocalDateTime.now());
        own.setUpdatedAt(LocalDateTime.now());
        when(repository.findByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(List.of(own));
        when(repository.findById(1L)).thenReturn(Optional.of(own));
        when(verifications.findByResumeId(1L)).thenReturn(Optional.empty());
        when(evidence.latestForResume(1L)).thenReturn(List.of());

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CurrentUser(7L, "student", "Student", "STUDENT"), null, List.of()));

        assertEquals(1, service.list().size());
        assertEquals(1L, service.get(1L).id());

        ResumeEntity foreign = new ResumeEntity();
        foreign.setId(2L);
        foreign.setUserId(8L);
        foreign.setOwnerName("Foreign");
        foreign.setTargetRole("Java");
        when(repository.findById(2L)).thenReturn(Optional.of(foreign));
        assertThrows(BusinessException.class, () -> service.get(2L));
    }
}
