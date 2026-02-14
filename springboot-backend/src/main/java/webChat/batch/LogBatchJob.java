package webChat.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import webChat.service.monitoring.DownloadLogService;

@Component
@Slf4j
@RequiredArgsConstructor
public class LogBatchJob {
    private final DownloadLogService downloadLogService;


    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul") // 매일 새벽 4시 동작 , 타임존 seoul 기준
//        @Scheduled(cron = "0/10 * * * * *", zone = "Asia/Seoul")
    public void cleanLogJob(){
        downloadLogService.deleteDownloadLog();
    }

}
