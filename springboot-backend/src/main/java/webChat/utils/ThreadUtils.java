package webChat.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import webChat.config.ThreadPoolConfig;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * FunctionalInterface를 이용한 Thread Job 구현
 * ThreadUtils.runTask(() -> {job}, {retry}, {sleep}, {jobName});
 */
@Slf4j
@Component
public class ThreadUtils {
    private static ExecutorService executor;

    public ThreadUtils(ThreadPoolConfig threadPoolConfig) {
        executor = threadPoolConfig.scheduledExecutor();
    }

    @FunctionalInterface
    public interface Task {
        boolean execute();
    }

    /**
     * 지정된 작업을 비동기적으로 실행하고, 재시도 로직을 포함하는 CompletableFuture를 반환
     *
     * @param task 실행할 작업. Task 인터페이스를 구현해야함
     * @param retry 최대 재시도 횟수. 작업 실패 시 이 횟수만큼 재시도
     * @param sleep 재시도 간 대기 시간(밀리초). 각 재시도 전에 이 시간만큼 대기
     * @param jobName 작업의 이름. 로깅 목적으로 사용
     * @return CompletableFuture<Boolean> 작업의 완료를 나타내는 CompletableFuture.
     *         작업 성공 시 complete()되고, 모든 재시도 실패 시 completeExceptionally()
     *
     * @throws IllegalArgumentException retry가 0 미만이거나 sleep이 0 미만인 경우
     * @throws NullPointerException task 또는 jobName이 null인 경우
     *
     * @implNote 이 메서드는 내부적으로 ExecutorService를 사용하여 작업을 비동기적으로 실행
     *           작업 실행 중 발생하는 모든 예외는 캐치되어 로깅되며, 재시도 로직을 트리거
     *           마지막 시도까지 실패한 경우, 최종 예외가 CompletableFuture를 통해 전파
     *
     * @implSpec Task 인터페이스는 boolean execute() 메서드를 가져야 하며,
     *           작업 성공 시 true를, 실패 시 false를 반환해야함
     *
     * @see Task
     * @see CompletableFuture
     * @see ExecutorService
     */
    public static CompletableFuture<Boolean> runTask(Task task, int retry, long sleep, String jobName) {
        CompletableFuture<Boolean> future = new CompletableFuture<>(); // 작업 결과를 반환할 CompletableFuture 생성

        executor.submit(() -> { // 비동기 작업 실행
            int attempts = 0; // 현재 시도 횟수
            boolean isSuccess = false; // 작업 성공 여부

            while (attempts < retry && !isSuccess) { // 최대 재시도 횟수와 성공 여부 확인
                try {
                    // 스레드가 인터럽트 상태인지 확인 (필요 시 즉시 중단)
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Thread was interrupted before task execution");
                    }

                    // 작업 실행
                    isSuccess = task.execute();

                    if (isSuccess) { // 작업 성공 시
                        log.info("=== {} Job Success ===", jobName);
                        future.complete(true); // 성공 결과를 CompletableFuture에 전달
                    } else { // 작업 실패 시
                        attempts++; // 시도 횟수 증가
                        if (attempts < retry) { // 재시도 가능하면 대기 후 재실행
                            log.info("=== try {} Job :: {}", jobName, attempts);
                            Thread.sleep(sleep); // 재시도 전 대기
                        } else { // 최대 재시도 횟수 초과 시 실패 처리
                            log.warn("=== {} Job Failed after {} attempts ===", jobName, attempts);
                            future.complete(false); // 실패 결과를 CompletableFuture에 전달
                        }
                    }
                } catch (InterruptedException ie) { // 인터럽트 예외 처리
                    Thread.currentThread().interrupt(); // 인터럽트 상태 복구
                    log.warn("Interrupted during retry for job: {}", jobName, ie);
                    future.completeExceptionally(ie); // 예외를 CompletableFuture에 전달
                    break; // 루프 종료
                } catch (RuntimeException e) { // 일반 예외 처리
                    attempts++; // 시도 횟수 증가
                    if (attempts < retry) { // 재시도 가능하면 대기 후 재실행
                        log.info("=== try {} Job :: {}", jobName, attempts);
                        try {
                            Thread.sleep(sleep); // 재시도 전 대기
                        } catch (InterruptedException ie) { // 대기 중 인터럽트 발생 시 처리
                            Thread.currentThread().interrupt(); // 인터럽트 상태 복구
                            log.warn("Interrupted while sleeping between retries", ie);
                            future.completeExceptionally(ie); // 예외를 CompletableFuture에 전달
                            break; // 루프 종료
                        }
                    } else { // 최대 재시도 횟수 초과 시 실패 처리
                        log.warn("=== {} Job Failed :: {}", jobName, attempts);
                        future.completeExceptionally(e); // 최종 실패 예외를 CompletableFuture에 전달
                    }
                }
            }
        });

        return future; // CompletableFuture 반환
    }

    /**
     * 비동기 작업을 실행하고 결과에 따른 후속 작업을 처리하는 공통 메서드
     *
     * @param task 실행할 작업
     * @param maxRetries 최대 재시도 횟수
     * @param retryDelayMs 재시도 간격 (밀리초)
     * @param taskName 작업 이름 (로깅 용도)
     * @param onSuccess 작업 성공 시 실행할 후속 작업
     * @return 작업 완료 후 결과를 담은 CompletableFuture
     */
    public static CompletableFuture<Boolean> executeAsyncTask(
            ThreadUtils.Task task,
            int maxRetries,
            int retryDelayMs,
            String taskName,
            Consumer<Boolean> onSuccess
    ) {
        return ThreadUtils.runTask(task, maxRetries, retryDelayMs, taskName)
                .thenApplyAsync(result -> {
                    if (Boolean.TRUE.equals(result) && onSuccess != null) {
                        try {
                            onSuccess.accept(result);
                        } catch (Exception e) {
                            log.error("Unknown Runtime Exception | Message: {}, Details: {}",
                                    e.getMessage(), e.getStackTrace());
                        }
                    }
                    return result;
                }, executor)
                .exceptionally(ex -> {
                    log.error("Final failure after retries for task: {}", taskName, ex);
                    return false;
                });
    }
}
