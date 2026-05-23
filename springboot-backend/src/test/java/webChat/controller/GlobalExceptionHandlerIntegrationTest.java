package webChat.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.filter.TraceIdFilter;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerIntegrationTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(new ExceptionTriggerController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .addFilters(new TraceIdFilter())
                .build();
    }

    @RestController
    @RequestMapping("/test/exception")
    static class ExceptionTriggerController {

        @GetMapping("/room-not-found")
        public String roomNotFound() {
            throw new ChatForYouException(ErrorCode.ROOM_NOT_FOUND);
        }

        @GetMapping("/access-denied-with-detail")
        public String accessDeniedWithDetail() {
            throw new ChatForYouException(ErrorCode.ACCESS_DENIED, "3");
        }

        @GetMapping("/internal-server-error")
        public String internalServerError() {
            throw new RuntimeException("unexpected internal error");
        }

        @GetMapping("/access-denied")
        public String accessDenied() {
            throw new ChatForYouException(ErrorCode.ACCESS_DENIED);
        }

        @GetMapping("/required-header")
        public String requiredHeader(@RequestHeader("X-Required-Header") String requiredHeader) {
            return requiredHeader;
        }

        @GetMapping("/external-api-error")
        public String externalApiError() {
            throw new ChatForYouException(ErrorCode.EXTERNAL_API_ERROR, "GET 502: upstream error");
        }

        @GetMapping("/json-conversion-error")
        public String jsonConversionError() {
            throw new ChatForYouException(ErrorCode.JSON_CONVERSION_ERROR, "object→json 변환 실패", new RuntimeException("jackson error"));
        }

        @GetMapping("/kurento-composite-not-found")
        public String kurentoCompositeNotFound() {
            throw new ChatForYouException(ErrorCode.KURENTO_COMPOSITE_NOT_FOUND);
        }

        @GetMapping("/recording-start-failed")
        public String recordingStartFailed() {
            throw new ChatForYouException(ErrorCode.RECORDING_START_FAILED, "pipeline 초기화 중 실패");
        }

        @PostMapping("/validation")
        public String validation(@Valid @RequestBody ValidationRequest request) {
            return "ok";
        }
    }

    static class ValidationRequest {
        @NotBlank(message = "방 이름은 필수입니다.")
        private String roomName;

        public String getRoomName() {
            return roomName;
        }
    }

    @Test
    @DisplayName("ChatForYouException(ROOM_NOT_FOUND) 발생 시 404 + ErrorResponse JSON 반환")
    void roomNotFound_returns404WithErrorResponseJson() throws Exception {
        // when & then
        mockMvc.perform(get("/test/exception/room-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("R001"))
                .andExpect(jsonPath("$.message").value(ErrorCode.ROOM_NOT_FOUND.getMessage()))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("ChatForYouException(detail 포함) 발생 시 detail 이 에러 응답에 포함된다")
    void accessDeniedWithDetail_returns403WithDetail() throws Exception {
        // when & then
        mockMvc.perform(get("/test/exception/access-denied-with-detail"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A001"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value("3"));
    }

    @Test
    @DisplayName("일반 RuntimeException 발생 시 500 반환 + 응답 body에 스택 트레이스 미포함")
    void unexpectedException_returns500WithoutStackTrace() throws Exception {
        // when & then
        mockMvc.perform(get("/test/exception/internal-server-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("C003"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    @DisplayName("MethodArgumentNotValidException 발생 시 400 + errors 배열 포함 반환")
    void validationFailed_returns400WithErrorsArray() throws Exception {
        // given
        String requestBody = objectMapper.writeValueAsString(new ValidationRequest());

        // when & then
        mockMvc.perform(post("/test/exception/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("C001"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.errors[0].field").value("roomName"))
                .andExpect(jsonPath("$.errors[0].reason").exists());
    }

    @Test
    @DisplayName("허용되지 않은 HTTP 메서드 요청 시 405 + C002 반환")
    void methodNotAllowed_returns405WithC002() throws Exception {
        // when & then
        mockMvc.perform(post("/test/exception/room-not-found"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("C002"))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.message").value(ErrorCode.METHOD_NOT_ALLOWED.getMessage()))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("TraceIdFilter가 적용되면 traceId 필드가 응답 JSON에 포함된다")
    void errorResponse_traceIdFieldExistsWhenFilterInjected() throws Exception {
        // when & then
        mockMvc.perform(get("/test/exception/room-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("오류 응답의 traceId 필드는 X-Trace-Id 헤더와 동일하다")
    void errorResponse_whenFilterInjected_traceIdMatchesResponseHeader() throws Exception {
        // when
        MvcResult result = mockMvc.perform(get("/test/exception/room-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(header().exists("X-Trace-Id"))
                .andReturn();

        // then
        String headerTraceId = result.getResponse().getHeader("X-Trace-Id");
        String body = result.getResponse().getContentAsString();

        assertThat(body).contains("\"traceId\":\"" + headerTraceId + "\"");
    }

    @Test
    @DisplayName("ChatForYouException(ACCESS_DENIED) 발생 시 403 + A001 응답 반환")
    void accessDenied_returns403WithA001() throws Exception {
        // when & then
        mockMvc.perform(get("/test/exception/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A001"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("필수 요청 헤더 누락 시 401 + A002 응답 반환")
    void missingRequestHeader_returns401WithA002() throws Exception {
        // when & then
        mockMvc.perform(get("/test/exception/required-header"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("A002"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value(ErrorCode.UNAUTHORIZED.getMessage()))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("EXTERNAL_API_ERROR(I001) 발생 시 502 + I001 응답 반환")
    void externalApiError_returns502WithI001() throws Exception {
        // when & then
        mockMvc.perform(get("/test/exception/external-api-error"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("I001"))
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.message").value(ErrorCode.EXTERNAL_API_ERROR.getMessage()))
                .andExpect(jsonPath("$.detail").value("GET 502: upstream error"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("JSON_CONVERSION_ERROR(I002) 발생 시 500 + I002 응답 반환")
    void jsonConversionError_returns500WithI002() throws Exception {
        // when & then
        mockMvc.perform(get("/test/exception/json-conversion-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("I002"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value(ErrorCode.JSON_CONVERSION_ERROR.getMessage()))
                .andExpect(jsonPath("$.detail").value("object→json 변환 실패"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("KURENTO_COMPOSITE_NOT_FOUND(K005) 발생 시 500 + K005 응답 반환")
    void kurentoCompositeNotFound_returns500WithK005() throws Exception {
        // when & then
        mockMvc.perform(get("/test/exception/kurento-composite-not-found"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("K005"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value(ErrorCode.KURENTO_COMPOSITE_NOT_FOUND.getMessage()))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("RECORDING_START_FAILED(K006) 발생 시 500 + K006 응답 반환 + detail 포함")
    void recordingStartFailed_returns500WithK006() throws Exception {
        // when & then
        mockMvc.perform(get("/test/exception/recording-start-failed"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("K006"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value(ErrorCode.RECORDING_START_FAILED.getMessage()))
                .andExpect(jsonPath("$.detail").value("pipeline 초기화 중 실패"))
                .andExpect(jsonPath("$.traceId").exists());
    }
}
