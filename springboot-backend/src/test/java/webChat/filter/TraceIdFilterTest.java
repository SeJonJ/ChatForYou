package webChat.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TraceIdFilter 단위 동작 테스트.
 *
 * Spring 컨텍스트 없이 Servlet Mock 객체만으로 필터 동작을 검증한다.
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    @DisplayName("요청 처리 중 MDC에 traceId 가 주입된다")
    void doFilterInternal_whenRequestProcessed_injectsMdcTraceId() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        final String[] capturedTraceId = {null};
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(
                    jakarta.servlet.ServletRequest req,
                    jakarta.servlet.ServletResponse res) {
                capturedTraceId[0] = MDC.get("traceId");
            }
        };

        // when
        filter.doFilter(request, response, chain);

        // then
        assertThat(capturedTraceId[0])
                .as("요청 처리 중 MDC traceId 가 주입되어야 한다")
                .isNotNull()
                .startsWith("req-");
    }

    @Test
    @DisplayName("응답 헤더 X-Trace-Id 가 포함된다")
    void doFilterInternal_whenRequestCompletes_setsXTraceIdHeader() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // when
        filter.doFilter(request, response, chain);

        // then
        assertThat(response.getHeader("X-Trace-Id"))
                .as("응답 헤더 X-Trace-Id 가 존재해야 한다")
                .isNotNull()
                .startsWith("req-");
    }

    @Test
    @DisplayName("X-Trace-Id 헤더 값과 요청 중 MDC traceId 값이 동일하다")
    void doFilterInternal_whenRequestCompletes_traceIdHeaderMatchesMdc() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        final String[] capturedMdcTraceId = {null};
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(
                    jakarta.servlet.ServletRequest req,
                    jakarta.servlet.ServletResponse res) {
                capturedMdcTraceId[0] = MDC.get("traceId");
            }
        };

        // when
        filter.doFilter(request, response, chain);

        // then
        assertThat(response.getHeader("X-Trace-Id"))
                .as("헤더 traceId 와 MDC traceId 가 동일해야 한다")
                .isEqualTo(capturedMdcTraceId[0]);
    }

    @Test
    @DisplayName("요청 처리 완료 후 MDC가 초기화된다")
    void doFilterInternal_whenRequestCompletes_clearsMdc() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // when
        filter.doFilter(request, response, chain);

        // then
        assertThat(MDC.get("traceId"))
                .as("요청 종료 후 MDC traceId 가 초기화되어야 한다")
                .isNull();
    }

    @Test
    @DisplayName("FilterChain에서 예외 발생해도 MDC가 초기화된다")
    void doFilterInternal_whenChainThrows_clearsMdc() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(
                    jakarta.servlet.ServletRequest req,
                    jakarta.servlet.ServletResponse res) throws jakarta.servlet.ServletException {
                throw new jakarta.servlet.ServletException("chain error");
            }
        };

        // when
        try {
            filter.doFilter(request, response, chain);
        } catch (Exception ignored) {
            // 예외는 무시 — MDC 초기화 여부만 확인
        }

        // then
        assertThat(MDC.get("traceId"))
                .as("예외 발생 후에도 MDC traceId 가 초기화되어야 한다")
                .isNull();
    }

    @Test
    @DisplayName("연속 요청 시 각 요청마다 고유한 traceId 가 생성된다")
    void doFilterInternal_whenMultipleRequests_generatesUniqueTraceIdPerRequest() throws Exception {
        // given
        MockHttpServletRequest request1 = new MockHttpServletRequest();
        MockHttpServletResponse response1 = new MockHttpServletResponse();
        MockHttpServletRequest request2 = new MockHttpServletRequest();
        MockHttpServletResponse response2 = new MockHttpServletResponse();

        // when
        filter.doFilter(request1, response1, new MockFilterChain());
        filter.doFilter(request2, response2, new MockFilterChain());

        // then
        String traceId1 = response1.getHeader("X-Trace-Id");
        String traceId2 = response2.getHeader("X-Trace-Id");
        assertThat(traceId1)
                .as("연속 요청의 traceId 가 서로 달라야 한다")
                .isNotEqualTo(traceId2);
    }

    @Test
    @DisplayName("멀티스레드 동시 요청에서도 traceId 가 요청별로 고유하고 서로 섞이지 않는다")
    void doFilterInternal_whenConcurrentRequests_generatesIsolatedTraceIds() throws Exception {
        // given
        int requestCount = 6;
        ExecutorService executorService = Executors.newFixedThreadPool(requestCount);
        Set<String> traceIds = ConcurrentHashMap.newKeySet();
        List<Callable<Void>> tasks = new ArrayList<>();

        for (int i = 0; i < requestCount; i++) {
            tasks.add(() -> {
                MockHttpServletRequest request = new MockHttpServletRequest();
                MockHttpServletResponse response = new MockHttpServletResponse();
                final String[] capturedTraceId = {null};
                MockFilterChain chain = new MockFilterChain() {
                    @Override
                    public void doFilter(
                            jakarta.servlet.ServletRequest req,
                            jakarta.servlet.ServletResponse res) {
                        capturedTraceId[0] = MDC.get("traceId");
                    }
                };

                filter.doFilter(request, response, chain);

                assertThat(capturedTraceId[0]).startsWith("req-");
                assertThat(response.getHeader("X-Trace-Id")).isEqualTo(capturedTraceId[0]);
                assertThat(MDC.get("traceId")).isNull();
                traceIds.add(capturedTraceId[0]);
                return null;
            });
        }

        // when
        List<Future<Void>> futures = executorService.invokeAll(tasks);
        executorService.shutdown();

        // then
        for (Future<Void> future : futures) {
            future.get();
        }
        assertThat(traceIds).hasSize(requestCount);
    }
}
