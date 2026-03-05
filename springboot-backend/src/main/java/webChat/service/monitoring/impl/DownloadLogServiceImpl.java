package webChat.service.monitoring.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import webChat.entity.DownloadLog;
import webChat.repository.DownloadLogRepository;
import webChat.service.monitoring.DownloadLogService;

import java.time.ZoneId;
import java.time.ZonedDateTime;

@Service
@RequiredArgsConstructor
@EnableAsync
@Slf4j
public class DownloadLogServiceImpl implements DownloadLogService {
    private final DownloadLogRepository downloadLogRepository;

    @Override
    @Async("taskExecutor")
    public void saveDownloadLog(DownloadLog downloadLog) {
        log.debug("[DownloadLog] save download log {}", downloadLog);
        downloadLogRepository.save(downloadLog);
    }

    @Override
    @Transactional
    public void deleteDownloadLog() {
        // 1. 정확한 6개월 전 타임스탬프 계산 (밀리초 기준)
        long sixMonthsAgo = ZonedDateTime.now(ZoneId.systemDefault())
                .minusMonths(6)
                .toInstant()
                .toEpochMilli();

        try {
            // 2. 삭제 쿼리 실행
            int count = downloadLogRepository.deleteBulkByCreatedAtLessThan(sixMonthsAgo);
            log.info("[DownloadLog] Successfully deleted logs count :: {}", count);
        } catch (Exception e) {
            log.error("[DownloadLog] Error occurred while deleting old logs", e);
        }
    }
}
