package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.security.AuthenticatedUserPrincipal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;

@ExtendWith(MockitoExtension.class)
class UserDetailsServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserDetailsServiceImpl userDetailsService;

    @Test
    void loadUserByUsername_loadsUserOnceAndReturnsAuthenticatedPrincipal() {
        User user = User.builder()
                .id("user-1")
                .name("Test User")
                .email("test@example.com")
                .password("encoded-password")
                .profileImage("profiles/test.png")
                .build();
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        UserDetails userDetails = userDetailsService.loadUserByUsername("TEST@EXAMPLE.COM");

        assertThat(userDetails).isInstanceOf(AuthenticatedUserPrincipal.class);
        AuthenticatedUserPrincipal principal = (AuthenticatedUserPrincipal) userDetails;
        assertThat(principal.getId()).isEqualTo("user-1");
        assertThat(principal.getName()).isEqualTo("Test User");
        assertThat(principal.getEmail()).isEqualTo("test@example.com");
        assertThat(principal.getProfileImage()).isEqualTo("profiles/test.png");
        verify(userRepository, times(1)).findByEmail("test@example.com");
    }
}
