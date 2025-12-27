package webChat.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 비동기 작업 및 스레드 풀 통합 설정
 *
 * 1. ScheduledExecutorService: ThreadUtils 재시도 로직용
 * 2. TaskExecutor: Spring @Async 어노테이션용 (CookieCheckEvent 등)
 */
@Configuration
@EnableAsync
@Slf4j
@Getter
public class ThreadPoolConfig {

    @Value("${spring.thread.bound.multi:2}")
    private int ioBoundMultiplier;

    @Value("${recording.upload.max-retries:3}")
    private int maxRetries;

    @Value("${recording.upload.retry-delay-ms:5000}")
    private int retryDelayMs;

    /**
     * ScheduledExecutorService Bean 생성 (ThreadUtils용)
     * 스레드 풀 크기 = CPU 코어 수 × I/O bound multiplier
     *
     * @return ScheduledExecutorService 인스턴스
     */
    @Bean
    public ScheduledExecutorService scheduledExecutor() {
        int corePoolSize = Runtime.getRuntime().availableProcessors();
        int poolSize = corePoolSize * ioBoundMultiplier;

        log.info("############## ScheduledThreadPool Configuration :: Cores={}, Multiplier={}, PoolSize={}",
                corePoolSize, ioBoundMultiplier, poolSize);

        return Executors.newScheduledThreadPool(poolSize);
    }

    /**
     * Spring @Async 어노테이션용 기본 Executor
     * @return Executor 인스턴스
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        int corePoolSize = Runtime.getRuntime().availableProcessors();

        // 기본 스레드 풀 크기
        executor.setCorePoolSize(corePoolSize);

        // 최대 스레드 풀 크기
        executor.setMaxPoolSize(corePoolSize * ioBoundMultiplier);

        // 대기 큐 용량
        executor.setQueueCapacity(100);

        // 스레드 이름 접두사
        executor.setThreadNamePrefix("async-task-");

        // 스레드 풀 초기화
        executor.initialize();

        log.info("############## TaskExecutor Configuration :: CorePoolSize={}, MaxPoolSize={}, QueueCapacity=100",
                corePoolSize, corePoolSize * 2);

        return executor;
    }
}
