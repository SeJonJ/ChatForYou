package webChat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 비동기 작업 처리를 위한 설정
 * 녹화 파일 업로드 등 시간이 오래 걸리는 작업을 비동기로 처리
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 녹화 파일 업로드 전용 Executor
     * - 대용량 파일 업로드를 비동기로 처리하여 메인 스레드 블로킹 방지
     * - 최대 5개까지 동시 업로드 가능
     */
    @Bean(name = "recordingUploadExecutor")
    public Executor recordingUploadExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 기본 스레드 풀 크기
        executor.setCorePoolSize(2);

        // 최대 스레드 풀 크기
        executor.setMaxPoolSize(5);

        // 대기 큐 용량
        executor.setQueueCapacity(100);

        // 스레드 이름 접두사
        executor.setThreadNamePrefix("recording-upload-");

        // 스레드 풀 초기화
        executor.initialize();

        return executor;
    }
}
