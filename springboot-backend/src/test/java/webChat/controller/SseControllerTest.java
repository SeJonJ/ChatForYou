package webChat.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import webChat.filter.TraceIdFilter;
import webChat.service.chatroom.SseService;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SseControllerTest {

    private MockMvc mockMvc;

    @Mock
    private SseService sseService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SseController(sseService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    @DisplayName("SSE 구독 요청 시 비동기 emitter 응답을 시작한다")
    void connect_whenGetRequest_returnsAsyncEmitterResponse() throws Exception {
        // given
        given(sseService.createEmitter()).willReturn(new SseEmitter());

        // when & then
        mockMvc.perform(get("/chatforyou/api/sse/room-events")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(header().exists("X-Trace-Id"));

        then(sseService).should().createEmitter();
    }

    @Test
    @DisplayName("SSE 엔드포인트에 POST 요청 시 405 + C002 + traceId 를 반환한다")
    void connect_whenPostRequest_returns405WithTraceId() throws Exception {
        // when & then
        mockMvc.perform(post("/chatforyou/api/sse/room-events"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("C002"))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.traceId").exists());
    }
}
