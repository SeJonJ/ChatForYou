package webChat.controller;

import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.model.login.OauthRedis;
import webChat.service.file.impl.MinioFileService;
import webChat.service.file.impl.RecordingFileService;
import webChat.service.monitoring.DownloadLogService;
import webChat.service.redis.RedisService;
import webChat.service.user.UserService;
import webChat.security.jwt.JwtRoomProvider;
import webChat.service.monitoring.ClientCheckService;
import webChat.service.monitoring.PrometheusService;
import webChat.utils.TokenUtils;

import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * FileController 업로드/다운로드 예외 경계값 테스트.
 *
 * 업로드 케이스:
 * - 허용되지 않는 확장자 → 400 + { code: "F001" }
 * - 파일 크기 초과(MaxUploadSizeExceededException) → 413 + F002
 *
 * 다운로드 케이스:
 * - 만료 Firebase 토큰 → 401 + A003
 * - bucket=recording + 유효 토큰 → RecordingFileService.getObject() 호출 검증
 * - 잘못된 bucket 값 → 401 + A002
 * - 방 없음(R001) → 404
 * - bucket=file 일반 경로 무회귀 확인
 *
 * Firebase 인증은 TokenUtils 정적 메서드를 통해 수행되므로 MockedStatic 으로 제어한다.
 *
 * 참가자 검증 케이스(bucket=RECORDING):
 * - 참가자 → 200 정상 다운로드
 * - 비참가자 → 403 A001 (핵심 보안 검증)
 * - bucket=FILE → isRoomMember 미호출, 기존 동작 무회귀
 * - 참가자 검증 통과 후 R001 → 검증 순서 확인
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
    private static final String ROOM_ID = "room-001";
    private static final String TESTER_EMAIL = "tester@example.com";

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
    private RedisService redisService;

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

    // ── 다운로드 경로 — 만료 토큰(A003) ──────────────────────────────────────

    @Test
    @DisplayName("만료된 Firebase 토큰으로 다운로드 요청 시 401 + A003 반환")
    void download_expiredFirebaseToken_returns401WithA003() throws Exception {
        // given — TokenUtils.checkGoogleOAuthToken 이 TOKEN_EXPIRED 예외를 던짐
        try (MockedStatic<TokenUtils> tokenUtils = mockStatic(TokenUtils.class)) {
            tokenUtils.when(() -> TokenUtils.checkGoogleOAuthToken(AUTHORIZATION))
                    .thenThrow(new ChatForYouException(ErrorCode.TOKEN_EXPIRED));

            // when & then
            mockMvc.perform(post("/chatforyou/api/file/download")
                            .param("roomId", "room-001")
                            .param("bucket", "recording")
                            .param("fileName", "recording.mp4")
                            .param("filePath", "room-001/rec-001/recording.mp4")
                            .header("Authorization", AUTHORIZATION))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("A003"));
        }
    }

    // ── 다운로드 경로 — bucket=recording + 유효 토큰 → RecordingFileService 호출 검증 ──

    @Test
    @DisplayName("bucket=recording 유효 토큰 다운로드 요청 시 RecordingFileService.getObject 가 호출된다")
    void download_recordingBucket_validToken_invokesRecordingFileService() throws Exception {
        // given — 참가자 검증 통과 + 파일 서비스 정상 응답
        given(redisService.isRoomMember(ROOM_ID, TESTER_EMAIL)).willReturn(true);
        given(recordingFileService.getObject(anyString(), anyString(), anyString()))
                .willReturn(ResponseEntity.ok(new byte[]{1, 2, 3}));

        try (MockedStatic<TokenUtils> tokenUtils = mockValidTokenWithOauthRedis()) {
            // when & then
            mockMvc.perform(post("/chatforyou/api/file/download")
                            .param("roomId", ROOM_ID)
                            .param("bucket", "recording")
                            .param("fileName", "recording.mp4")
                            .param("filePath", "room-001/rec-001/recording.mp4")
                            .header("Authorization", AUTHORIZATION))
                    .andExpect(status().isOk());

            // RecordingFileService.getObject 가 실제로 호출되어야 한다
            verify(recordingFileService).getObject(ROOM_ID, "recording.mp4", "room-001/rec-001/recording.mp4");
        }
    }

    // ── 참가자 검증 — 비참가자 차단(A001) ────────────────────────────────────

    @Test
    @DisplayName("bucket=RECORDING + 비참가자 요청 시 403 + A001 반환 (FR-2 핵심 검증)")
    void downloadRecording_비멤버_A001차단() throws Exception {
        // given — isRoomMember 가 false 를 반환 → 참가자 아님
        given(redisService.isRoomMember(ROOM_ID, TESTER_EMAIL)).willReturn(false);

        try (MockedStatic<TokenUtils> tokenUtils = mockValidTokenWithOauthRedis()) {
            // when & then
            mockMvc.perform(post("/chatforyou/api/file/download")
                            .param("roomId", ROOM_ID)
                            .param("bucket", "recording")
                            .param("fileName", "recording.mp4")
                            .param("filePath", "room-001/rec-001/recording.mp4")
                            .header("Authorization", AUTHORIZATION))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("A001"));

            // 비참가자는 RecordingFileService 에 도달하면 안 된다
            verify(recordingFileService, never()).getObject(anyString(), anyString(), anyString());
        }
    }

    @Test
    @DisplayName("bucket=RECORDING + 비참가자 차단 시 FAIL 다운로드 로그가 기록된다 (FR-6 감사 로그)")
    void downloadRecording_비멤버_FAIL로그기록() throws Exception {
        // given — 비참가자 차단 후 감사 로그 FAIL 기록 검증 (FR-6)
        given(redisService.isRoomMember(ROOM_ID, TESTER_EMAIL)).willReturn(false);

        try (MockedStatic<TokenUtils> tokenUtils = mockValidTokenWithOauthRedis()) {
            // when
            mockMvc.perform(post("/chatforyou/api/file/download")
                            .param("roomId", ROOM_ID)
                            .param("bucket", "recording")
                            .param("fileName", "recording.mp4")
                            .param("filePath", "room-001/rec-001/recording.mp4")
                            .header("Authorization", AUTHORIZATION))
                    .andExpect(status().isForbidden());

            // then — A001 차단도 FAIL 상태의 DownloadLog 가 저장되어야 한다 (감사 추적 가능)
            verify(downloadLogService).saveDownloadLog(argThat(log ->
                    log.getStatus() == webChat.entity.DownloadLog.DownloadStatus.FAIL
                    && TESTER_EMAIL.equals(log.getEmail())
                    && ROOM_ID.equals(log.getRoomId())
            ));
        }
    }

    // ── 참가자 검증 — 참가자 성공 후 R001 (검증 순서 확인) ────────────────────

    @Test
    @DisplayName("bucket=RECORDING + 참가자지만 방이 삭제된 경우 R001 반환 (A001 이전 통과, 검증 순서 확인)")
    void downloadRecording_memberCheck후_R001() throws Exception {
        // given — 참가자 검증 통과 but 방 없음 → R001
        // 설계 확정(§2.4): 멤버십 A001 이 방 존재 R001 보다 먼저 확인됨
        given(redisService.isRoomMember(ROOM_ID, TESTER_EMAIL)).willReturn(true);
        given(recordingFileService.getObject(anyString(), anyString(), anyString()))
                .willThrow(new ChatForYouException(ErrorCode.ROOM_NOT_FOUND));

        try (MockedStatic<TokenUtils> tokenUtils = mockValidTokenWithOauthRedis()) {
            // when & then
            mockMvc.perform(post("/chatforyou/api/file/download")
                            .param("roomId", ROOM_ID)
                            .param("bucket", "recording")
                            .param("fileName", "recording.mp4")
                            .param("filePath", "room-001/rec-001/recording.mp4")
                            .header("Authorization", AUTHORIZATION))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("R001"));
        }
    }

    // ── 참가자 검증 무관 — bucket=FILE 무회귀 ────────────────────────────────

    // ── 다운로드 경로 — 잘못된 bucket → A002(UNAUTHORIZED) ──────────────────

    @Test
    @DisplayName("잘못된 bucket 값으로 다운로드 요청 시 401 + A002 반환")
    void download_invalidBucket_returns401WithA002() throws Exception {
        // given — bucket enum 변환 실패 → ChatForYouException(UNAUTHORIZED)
        try (MockedStatic<TokenUtils> tokenUtils = mockValidTokenWithOauthRedis()) {
            // when & then
            mockMvc.perform(post("/chatforyou/api/file/download")
                            .param("roomId", "room-001")
                            .param("bucket", "unknown_bucket")
                            .param("fileName", "recording.mp4")
                            .param("filePath", "room-001/rec-001/recording.mp4")
                            .header("Authorization", AUTHORIZATION))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("A002"));
        }
    }

    // ── 다운로드 경로 — 방 없음 → R001(ROOM_NOT_FOUND) ─────────────────────

    @Test
    @DisplayName("방 없음(R001) 다운로드 요청 시 404 반환")
    void download_roomNotFound_returns404WithR001() throws Exception {
        // given — 참가자 검증은 통과하지만 RecordingFileService 가 ROOM_NOT_FOUND 던짐
        given(redisService.isRoomMember("room-gone", TESTER_EMAIL)).willReturn(true);
        given(recordingFileService.getObject(anyString(), anyString(), anyString()))
                .willThrow(new ChatForYouException(ErrorCode.ROOM_NOT_FOUND));

        try (MockedStatic<TokenUtils> tokenUtils = mockValidTokenWithOauthRedis()) {
            // when & then
            mockMvc.perform(post("/chatforyou/api/file/download")
                            .param("roomId", "room-gone")
                            .param("bucket", "recording")
                            .param("fileName", "recording.mp4")
                            .param("filePath", "room-gone/rec-001/recording.mp4")
                            .header("Authorization", AUTHORIZATION))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("R001"));
        }
    }

    @Test
    @DisplayName("bucket=FILE 일반 파일 다운로드 시 isRoomMember 가 호출되지 않는다 (FR-3 무회귀)")
    void downloadFile_참가자검증_무관() throws Exception {
        // given — 일반 파일 경로: 참가자 검증(isRoomMember)은 호출되지 않아야 한다
        given(minioFileService.getObject(anyString(), anyString()))
                .willReturn(ResponseEntity.ok(new byte[]{4, 5, 6}));

        try (MockedStatic<TokenUtils> tokenUtils = mockValidTokenWithOauthRedis()) {
            // when & then
            mockMvc.perform(post("/chatforyou/api/file/download")
                            .param("roomId", ROOM_ID)
                            .param("bucket", "file")
                            .param("fileName", "test.png")
                            .param("filePath", "room-001/uuid-001/test.png")
                            .header("Authorization", AUTHORIZATION))
                    .andExpect(status().isOk());

            // bucket=FILE 경로에서는 참가자 검증이 호출되지 않아야 한다 (D-A 결정: 녹화만 검증)
            verify(redisService, never()).isRoomMember(anyString(), anyString());
            verify(minioFileService).getObject("test.png", "room-001/uuid-001/test.png");
            verify(recordingFileService, never()).getObject(anyString(), anyString(), anyString());
        }
    }

    // ── 다운로드 경로 — bucket=file 일반 파일 경로 무회귀 ──────────────────

    @Test
    @DisplayName("bucket=file 일반 파일 다운로드 시 MinioFileService.getObject 가 호출된다")
    void download_fileBucket_validToken_invokesMinioFileService() throws Exception {
        // given — 일반 파일 경로: RecordingFileService 는 호출되지 않아야 한다
        given(minioFileService.getObject(anyString(), anyString()))
                .willReturn(ResponseEntity.ok(new byte[]{4, 5, 6}));

        try (MockedStatic<TokenUtils> tokenUtils = mockValidTokenWithOauthRedis()) {
            // when & then
            mockMvc.perform(post("/chatforyou/api/file/download")
                            .param("roomId", ROOM_ID)
                            .param("bucket", "file")
                            .param("fileName", "test.png")
                            .param("filePath", "room-001/uuid-001/test.png")
                            .header("Authorization", AUTHORIZATION))
                    .andExpect(status().isOk());

            verify(minioFileService).getObject("test.png", "room-001/uuid-001/test.png");
            // 일반 파일 경로에서 RecordingFileService 는 호출되지 않아야 한다
            verify(recordingFileService, never()).getObject(anyString(), anyString(), anyString());
        }
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private MockedStatic<TokenUtils> mockValidToken() throws Exception {
        // given — upload 경로용: UserService 반환값은 null 허용(upload는 OauthRedis 미사용)
        FirebaseToken token = mock(FirebaseToken.class);
        given(token.getEmail()).willReturn("tester@example.com");
        given(userService.getValidatedOauthUser("tester@example.com")).willReturn(null);

        MockedStatic<TokenUtils> tokenUtils = mockStatic(TokenUtils.class);
        tokenUtils.when(() -> TokenUtils.checkGoogleOAuthToken(AUTHORIZATION)).thenReturn(token);
        return tokenUtils;
    }

    private MockedStatic<TokenUtils> mockValidTokenWithOauthRedis() {
        // download 경로용: OauthRedis(idx, email) 반환 필요
        FirebaseToken token = mock(FirebaseToken.class);
        given(token.getEmail()).willReturn("tester@example.com");

        OauthRedis oauthRedis = new OauthRedis();
        oauthRedis.setIdx(1L);
        oauthRedis.setEmail("tester@example.com");
        given(userService.getValidatedOauthUser("tester@example.com")).willReturn(oauthRedis);

        MockedStatic<TokenUtils> tokenUtils = mockStatic(TokenUtils.class);
        tokenUtils.when(() -> TokenUtils.checkGoogleOAuthToken(AUTHORIZATION)).thenReturn(token);
        return tokenUtils;
    }
}
