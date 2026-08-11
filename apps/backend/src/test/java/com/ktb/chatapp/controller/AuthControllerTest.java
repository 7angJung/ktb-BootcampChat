package com.ktb.chatapp.controller;

import tools.jackson.databind.ObjectMapper;
import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.dto.LoginRequest;
import com.ktb.chatapp.dto.RegisterRequest;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.SessionCreationResult;
import com.ktb.chatapp.service.SessionMetadata;
import com.ktb.chatapp.service.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({MongoTestContainer.class})
@TestPropertySource(properties = "socketio.enabled=false")
public class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SessionService sessionService;

    @MockitoSpyBean
    private UserRepository userRepository;

    @Test
    @WithAnonymousUser
    public void testRegisterUser() throws Exception {
        when(sessionService.createSession(any(String.class), any(SessionMetadata.class)))
                .thenReturn(SessionCreationResult.builder()
                        .sessionId("mock-session-id")
                        .expiresIn(3600L)
                        .build());

        String email = "test" + System.currentTimeMillis() + "@example.com";
        RegisterRequest registerRequest = new RegisterRequest();
        registerRequest.setName("Test User");
        registerRequest.setEmail(email);
        registerRequest.setPassword("password");

        mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("회원가입이 완료되었습니다."))
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.name").value("Test User"));
    }

    @Test
    @WithAnonymousUser
    public void registerUser_whenRequestIsInvalid_shouldReturnValidationErrorContract() throws Exception {
        RegisterRequest registerRequest = new RegisterRequest();
        registerRequest.setName("");
        registerRequest.setEmail("invalid-email");
        registerRequest.setPassword("short");

        mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    @WithAnonymousUser
    public void testAuthenticateUser() throws Exception {
        when(sessionService.createSession(any(String.class), any(SessionMetadata.class)))
                .thenReturn(SessionCreationResult.builder()
                        .sessionId("mock-session-id")
                        .expiresIn(3600L)
                        .build());

        String email = "test" + System.currentTimeMillis() + "@example.com";

        RegisterRequest registerRequest = new RegisterRequest();
        registerRequest.setName("Test User");
        registerRequest.setEmail(email);
        registerRequest.setPassword("password");

        mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        clearInvocations(userRepository, sessionService);
        LoginRequest loginRequest = new LoginRequest(email.toUpperCase(), "password");

        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(header().string("Authorization", startsWith("Bearer ")))
                .andExpect(header().string("x-session-id", "mock-session-id"))
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.sessionId").value("mock-session-id"))
                .andExpect(jsonPath("$.user._id").exists())
                .andExpect(jsonPath("$.user.name").value("Test User"))
                .andExpect(jsonPath("$.user.email").value(email));

        verify(userRepository, times(1)).findByEmail(email);
        verify(sessionService, times(1)).createSession(any(String.class), any(SessionMetadata.class));
        verify(sessionService, never()).removeAllUserSessions(any(String.class));
    }

    @Test
    @WithAnonymousUser
    public void login_whenEmailDoesNotExist_shouldReturnUnauthorizedContract() throws Exception {
        LoginRequest loginRequest = new LoginRequest(
                "missing" + System.currentTimeMillis() + "@example.com",
                "password");

        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value("이메일 또는 비밀번호가 올바르지 않습니다."));

        verify(sessionService, never()).createSession(any(String.class), any(SessionMetadata.class));
    }

    @Test
    @WithAnonymousUser
    public void login_whenPasswordIsWrong_shouldReturnUnauthorizedContract() throws Exception {
        String email = "wrong-password" + System.currentTimeMillis() + "@example.com";
        RegisterRequest registerRequest = new RegisterRequest();
        registerRequest.setName("Test User");
        registerRequest.setEmail(email);
        registerRequest.setPassword("password");

        mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        clearInvocations(sessionService);
        LoginRequest loginRequest = new LoginRequest(email, "wrong-password");

        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value("이메일 또는 비밀번호가 올바르지 않습니다."));

        verify(sessionService, never()).createSession(any(String.class), any(SessionMetadata.class));
    }
}
