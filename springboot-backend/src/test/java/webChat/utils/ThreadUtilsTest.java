package webChat.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ThreadUtils 단위 테스트.
 *
 * ThreadPoolConfig 없이 소형 executor를 직접 주입하여 테스트한다.
 * 검증 항목: 재시도 성공/실패, MDC context 전파, RuntimeException 처리, onSuccess 콜백.
 */
class ThreadUtilsTest {

    private ExecutorService testExecutor;

    @BeforeEach
    void setUp() {
        testExecutor = Executors.newFixedThreadPool(4);
        ReflectionTestUtils.setField(ThreadUtils.class, "executor", testExecutor);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        MDC.clear();
        testExecutor.shutdown();
        testExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("task가 첫 번째 시도에 성공하면 future는 true로 완료된다")
    void runTask_firstAttemptSuccess_returnsTrueComplete() throws Exception {
        // given
        ThreadUtils.Task task = () -> true;

        // when
        CompletableFuture<Boolean> future = ThreadUtils.runTask(task, 3, 10L, "success-job");

        // then
        assertThat(future.get(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("task가 항상 false를 반환하면 최대 재시도 후 future는 false로 완료된다")
    void runTask_alwaysFails_returnsFalseAfterMaxRetries() throws Exception {
        // given
        ThreadUtils.Task task = () -> false;

        // when
        CompletableFuture<Boolean> future = ThreadUtils.runTask(task, 2, 10L, "failing-job");

        // then
        assertThat(future.get(3, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    @DisplayName("task가 첫 번째 실패 후 두 번째 시도에서 성공하면 future는 true로 완료된다")
    void runTask_succeedsOnSecondAttempt_returnsTrueComplete() throws Exception {
        // given
        AtomicBoolean firstCall = new AtomicBoolean(true);
        ThreadUtils.Task task = () -> {
            if (firstCall.getAndSet(false)) {
                return false; // 첫 번째 실패
            }
            return true;     // 두 번째 성공
        };

        // when
        CompletableFuture<Boolean> future = ThreadUtils.runTask(task, 3, 10L, "retry-job");

        // then
        assertThat(future.get(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("호출 스레드의 MDC traceId가 executor 스레드로 전파된다")
    void runTask_mdcContextPropagatedToExecutorThread() throws Exception {
        // given
        MDC.put("traceId", "test-trace-99999");
        AtomicReference<String> capturedTraceId = new AtomicReference<>();

        ThreadUtils.Task task = () -> {
            capturedTraceId.set(MDC.get("traceId"));
            return true;
        };

        // when
        CompletableFuture<Boolean> future = ThreadUtils.runTask(task, 1, 0L, "mdc-test-job");
        future.get(3, TimeUnit.SECONDS);

        // then
        assertThat(capturedTraceId.get())
                .as("executor 스레드에서 호출 스레드의 MDC traceId가 읽혀야 한다")
                .isEqualTo("test-trace-99999");
    }

    @Test
    @DisplayName("MDC가 비어 있어도 task가 정상 실행된다 (null context 방어)")
    void runTask_emptyMdc_taskRunsNormally() throws Exception {
        // given — MDC 컨텍스트 없음
        MDC.clear();
        ThreadUtils.Task task = () -> true;

        // when
        CompletableFuture<Boolean> future = ThreadUtils.runTask(task, 1, 0L, "empty-mdc-job");

        // then
        assertThat(future.get(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("task가 RuntimeException을 던지고 재시도를 초과하면 completeExceptionally된다")
    void runTask_runtimeExceptionAfterRetries_completesExceptionally() {
        // given
        ThreadUtils.Task task = () -> {
            throw new RuntimeException("task error");
        };

        // when
        CompletableFuture<Boolean> future = ThreadUtils.runTask(task, 2, 10L, "exception-job");

        // then — completeExceptionally이므로 get()은 ExecutionException을 던진다
        assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("executeAsyncTask 성공 시 onSuccess 콜백이 호출된다")
    void executeAsyncTask_onSuccess_callbackInvoked() throws Exception {
        // given
        ThreadUtils.Task task = () -> true;
        AtomicBoolean callbackInvoked = new AtomicBoolean(false);

        // when
        CompletableFuture<Boolean> future = ThreadUtils.executeAsyncTask(
                task, 1, 10, "callback-job",
                result -> callbackInvoked.set(true)
        );
        future.get(3, TimeUnit.SECONDS);

        // then
        assertThat(callbackInvoked.get())
                .as("성공 결과에서 onSuccess 콜백이 호출되어야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("executeAsyncTask 실패 시 onSuccess 콜백이 호출되지 않는다")
    void executeAsyncTask_onFailure_callbackNotInvoked() throws Exception {
        // given
        ThreadUtils.Task task = () -> false;
        AtomicBoolean callbackInvoked = new AtomicBoolean(false);

        // when
        CompletableFuture<Boolean> future = ThreadUtils.executeAsyncTask(
                task, 1, 10, "failing-callback-job",
                result -> callbackInvoked.set(true)
        );
        future.get(3, TimeUnit.SECONDS);

        // then
        assertThat(callbackInvoked.get())
                .as("실패 결과에서 onSuccess 콜백은 호출되지 않아야 한다")
                .isFalse();
    }
}
