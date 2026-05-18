package webChat.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import webChat.exception.ChatForYouException;
import webChat.entity.DailyInfo;
import webChat.model.room.KurentoRoom;
import webChat.model.room.RoomState;
import webChat.repository.DailyInfoRepository;
import webChat.service.analysis.AnalysisService;
import webChat.service.chatroom.ChatRoomService;
import webChat.service.redis.RedisService;
import webChat.service.routing.RoutingInstanceProvider;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
@RequiredArgsConstructor
public class RoomBatchJob {
    private final AnalysisService analysisService;
    private final ChatRoomService chatRoomService;
    private final DailyInfoRepository dailyInfoRepository;
    private final RedisService redisService;
    private final int SEARCH_COUNT = 100;
    private final RoutingInstanceProvider instanceProvider;

    /**
     * 삭제 대상 방을 주기적으로 정리하고 인스턴스 방 개수를 보정한다.
     */
    @Scheduled(cron = "0 0,30 * * * *", zone = "Asia/Seoul") // 매 시간 30분에 실행 , 타임존 seoul 기준
//    @Scheduled(cron = "0/10 * * * * *", zone = "Asia/Seoul")
    @SchedulerLock(
            name = "checkDeleteRoomLock",
            lockAtLeastFor = "30s", // 최소 30초 동안은 Lock 유지 (빨리 끝나도 다른 서버가 못 들어오게 방어)
            lockAtMostFor = "3m"   // 최대 10분 후에는 Lock 해제 (서버가 중간에 죽었을 때를 대비한 안전장치)
    )
    public void checkDeleteRoom() {

        AtomicInteger totalDelRoomCnt = new AtomicInteger();
        AtomicInteger rtcRoomCnt = new AtomicInteger();

        try {
            List<KurentoRoom> chatRoomListForDelete = redisService.getChatRoomListForDelete(SEARCH_COUNT);
            for (KurentoRoom kurentoRoom : chatRoomListForDelete) {
                chatRoomService.delChatRoom(kurentoRoom);
                if (!RoomState.INACTIVE.equals(kurentoRoom.getRoomState()) && instanceProvider.getInstanceId().equals(kurentoRoom.getInstanceId())) {
                    instanceProvider.decrementInstanceRoomCount();
                }
                rtcRoomCnt.incrementAndGet();
                totalDelRoomCnt.incrementAndGet();
            }
        } catch (ChatForYouException e) {
            log.error("배치 방 삭제 실패: code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("배치 방 삭제 중 예기치 않은 오류 발생: {}", e.getMessage(), e);
        }

        LocalDateTime date = LocalDateTime.now();
        log.info("##########################");
        log.info("Deleted RTC Room Count : {}", rtcRoomCnt);
        log.info("Deleted Room Total Count : {}", totalDelRoomCnt);
        log.info(date.toString());
        log.info("##########################");
    }

//    @Scheduled(cron = "0 0 */6 * * *", zone = "Asia/Seoul") // 6시간 마다 , 타임존 seoul 기준
//    @Scheduled(cron = "0/10 * * * * *", zone = "Asia/Seoul")
    // Query did not return a unique result 이슈 확인 전까지 스케줄러를 비활성 상태로 유지한다.
    /**
     * 일일 방문자/방 통계를 집계한다.
     * 현재는 중복 결과 이슈 확인 전까지 스케줄러 활성화를 보류한다.
     */
    @SchedulerLock(
            name = "dailyInfoInsertLock",
            lockAtLeastFor = "30s",
            lockAtMostFor = "3m"
    )
    public void dailyInfoInsert() {
        LocalDate nowDate = LocalDate.now();
        DailyInfo findDailyInfo = dailyInfoRepository.findByDate(nowDate);
        int dailyVisitor = analysisService.getDailyVisitor();
        int dailyRoomCnt = analysisService.getDailyRoomCnt();

        if (Objects.nonNull(findDailyInfo)) {
            findDailyInfo.setDailyVisitor(dailyVisitor);
            findDailyInfo.setDailyRoomCnt(dailyRoomCnt);
            dailyInfoRepository.save(findDailyInfo);
        } else {
            analysisService.resetDailyInfo(); // dailyInfo 초기화

            DailyInfo dailyInfo = DailyInfo.builder()
                    .dailyVisitor(0)
                    .dailyRoomCnt(0)
                    .date(nowDate)
                    .build();
            dailyInfoRepository.save(dailyInfo);

            log.info("##########################");
            log.info("NEW DAY :: Reset Daily Info");
        }

        log.info("##########################");
        log.info("dailyVisitor : {}", dailyVisitor);
        log.info("dailyRoomCnt : {}", dailyRoomCnt);
        log.info("##########################");
    }
}
