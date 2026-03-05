package webChat.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import webChat.entity.DownloadLog;

public interface DownloadLogRepository extends JpaRepository<DownloadLog, Long> {
    // 특정 시간(createdAt)보다 작은 데이터를 삭제
    @Transactional
    @Modifying(clearAutomatically = true) // 실행 후 영속성 컨텍스트 초기화
    @Query("DELETE FROM DownloadLog d WHERE d.createdAt < :currentTime")
    int deleteBulkByCreatedAtLessThan(@Param("currentTime") long currentTime);
}
