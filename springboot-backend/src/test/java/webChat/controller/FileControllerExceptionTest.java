package webChat.controller;

import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.service.file.impl.MinioFileService;
import webChat.service.file.impl.RecordingFileService;
import webChat.service.monitoring.DownloadLogService;
import webChat.service.user.UserService;
import webChat.security.jwt.JwtRoomProvider;
import webChat.service.monitoring.ClientCheckService;
import webChat.service.monitoring.PrometheusService;
import webChat.utils.TokenUtils;

import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * FileController 파일 업로드 예외 경계값 테스트.
 *
 * - 허용되지 않는 확장자 → 400 + { code: "F001" }
 * - 파일 크기 초과(MaxUploadSizeExceededException) → GlobalExceptionHandler 처리 검증
 *
 * Firebase 인증은 TokenUtils 정적 메서드를 통해 수행되므로
 * MockitoSettings 로 분리 불가하다. 따라서 FirebaseConfig 관련 Bean 을 제외하고
 * TokenUtils 를 통해 토큰 검증이 호출되기 전 단계에서 예외를 발생시키는 방식을 사용한다.
 *
 * 파일 크기 초과는 GlobalExceptionHandler 의 MaxUploadSizeExceededException 핸들러로
 * 413 + F002 응답을 반환해야 한다.
 */
@WebMvcTest(
        controllers = FileController.class,
        excludeAutoConfiguration = {
                org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration.class
        }
)
@Import(GlobalExceptionHandler.class)
class FileControllerExceptionTest {
    private static final String AUTHORIZATION = "Bearer dummy-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MinioFileService minioFileService;

    @MockitoBean
    private RecordingFileService recordingFileService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private DownloadLogService downloadLogService;

    @MockitoBean
    private JwtRoomProvider jwtRoomProvider;

    @MockitoBean
    private ClientCheckService clientCheckService;

    @MockitoBean
    private PrometheusService prometheusService;

    // ── 허용되지 않는 확장자 ────────────────────────────────────────────────

    @Test
    @DisplayName("허용되지 않는 확장자 파일 업로드 시 400 + { code: F001 } 반환")
    void uploadFile_invalidExtension_returns400WithF001() throws Exception {
        // given
        MockMultipartFile executableFile = new MockMultipartFile(
                "file",
                "malicious.exe",
                "application/octet-stream",
                "fake exe content".getBytes()
        );
        // 확장자 검증 실패 시 서비스에서 ChatForYouException(FILE_EXTENSION_INVALID) throw
        given(minioFileService.uploadFile(any(), any()))
                .willThrow(new ChatForYouException(ErrorCode.FILE_EXTENSION_INVALID));

        // when & then
        try (MockedStatic<TokenUtils> tokenUtils = mockValidToken()) {
            mockMvc.perform(multipart("/chatforyou/api/file/upload")
                            .file(executableFile)
                            .param("roomId", "room-123")
                            .header("Authorization", AUTHORIZATION))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("F001"))
                    .andExpect(jsonPath("$.status").value(400));
        }
    }

    @Test
    @DisplayName("허용되지 않는 확장자 목록 추가 검증: .sh 파일 → 400 + F001")
    void uploadFile_shellScriptExtension_returns400WithF001() throws Exception {
        // given
        MockMultipartFile shellFile = new MockMultipartFile(
                "file",
                "script.sh",
                "text/plain",
                "#!/bin/bash".getBytes()
        );
        given(minioFileService.uploadFile(any(), any()))
                .willThrow(new ChatForYouException(ErrorCode.FILE_EXTENSION_INVALID));

        // when & then
        try (MockedStatic<TokenUtils> tokenUtils = mockValidToken()) {
            mockMvc.perform(multipart("/chatforyou/api/file/upload")
                            .file(shellFile)
                            .param("roomId", "room-456")
                            .header("Authorization", AUTHORIZATION))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("F001"));
        }
    }

    @Test
    @DisplayName("허용되지 않는 확장자 목록 추가 검증: .js 파일 → 400 + F001")
    void uploadFile_jsExtension_returns400WithF001() throws Exception {
        // given
        MockMultipartFile jsFile = new MockMultipartFile(
                "file",
                "payload.js",
                "application/javascript",
                "alert(1)".getBytes()
        );
        given(minioFileService.uploadFile(any(), any()))
                .willThrow(new ChatForYouException(ErrorCode.FILE_EXTENSION_INVALID));

        // when & then
        try (MockedStatic<TokenUtils> tokenUtils = mockValidToken()) {
            mockMvc.perform(multipart("/chatforyou/api/file/upload")
                            .file(jsFile)
                            .param("roomId", "room-789")
                            .header("Authorization", AUTHORIZATION))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("F001"));
        }
    }

    // ── 인증 토큰 미포함 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("인증 토큰 미포함 파일 업로드 시 401 + { code: A002 } 반환")
    void uploadFile_missingAuthorizationHeader_returns401WithA002() throws Exception {
        // given
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.png",
                "image/png",
                "image content".getBytes()
        );

        // when & then — Authorization 헤더 없이 요청
        mockMvc.perform(multipart("/chatforyou/api/file/upload")
                        .file(file)
                        .param("roomId", "room-123"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A002"))
                .andExpect(jsonPath("$.status").value(401));
    }

    // ── 파일 크기 초과 ────────────────────────────────────────────────────────

    /**
     * MaxUploadSizeExceededException 은 전용 핸들러를 통해
     * 413 + F002 로 직렬화되어야 한다.
     */
    @Test
    @DisplayName("파일 크기 초과 시 413 + F002 반환")
    void uploadFile_maxSizeExceeded_returns413WithF002() throws Exception {
        // given
        MockMultipartFile oversizedFile = new MockMultipartFile(
                "file",
                "oversized.png",
                "image/png",
                new byte[1] // MockMvc 레이어에서는 실제 크기 제한이 적용되지 않으므로
                            // MaxUploadSizeExceededException 을 서비스에서 throw 시뮬레이션
        );
        given(minioFileService.uploadFile(any(), any()))
                .willThrow(new MaxUploadSizeExceededException(10 * 1024 * 1024L));

        // when & then
        try (MockedStatic<TokenUtils> tokenUtils = mockValidToken()) {
            mockMvc.perform(multipart("/chatforyou/api/file/upload")
                            .file(oversizedFile)
                            .param("roomId", "room-123")
                            .header("Authorization", AUTHORIZATION))
                    .andExpect(status().isPayloadTooLarge())
                    .andExpect(jsonPath("$.code").value("F002"))
                    .andExpect(jsonPath("$.status").value(413))
                    .andExpect(jsonPath("$.message").value(ErrorCode.FILE_SIZE_EXCEEDED.getMessage()));
        }
    }

    private MockedStatic<TokenUtils> mockValidToken() throws Exception {
        // given
        FirebaseToken token = mock(FirebaseToken.class);
        given(token.getEmail()).willReturn("tester@example.com");
        given(userService.getValidatedOauthUser("tester@example.com")).willReturn(null);

        MockedStatic<TokenUtils> tokenUtils = mockStatic(TokenUtils.class);
        tokenUtils.when(() -> TokenUtils.checkGoogleOAuthToken(AUTHORIZATION)).thenReturn(token);
        return tokenUtils;
    }
}
