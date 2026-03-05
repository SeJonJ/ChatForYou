package webChat.service.monitoring;

import webChat.entity.DownloadLog;

public interface DownloadLogService {
    void saveDownloadLog(DownloadLog downloadLog);
    void deleteDownloadLog();
}
