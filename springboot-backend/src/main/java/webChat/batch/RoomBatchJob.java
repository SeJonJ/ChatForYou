package webChat.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.BadRequestException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import webChat.controller.ExceptionController;
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


    @Scheduled(cron = "0 0,30 * * * *", zone = "Asia/Seoul") // 매 시간 30분에 실행 , 타임존 seoul 기준
//    @Scheduled(cron = "0/10 * * * * *", zone = "Asia/Seoul")
    public void checkDeleteRoom() throws ExceptionController.DelRoomException {

        AtomicInteger totalDelRoomCnt = new AtomicInteger();
        AtomicInteger rtcRoomCnt = new AtomicInteger();

        try {
            List<KurentoRoom> chatRoomListForDelete = redisService.getChatRoomListForDelete(SEARCH_COUNT);
            for (KurentoRoom kurentoRoom : chatRoomListForDelete) {
                chatRoomService.delChatRoom(kurentoRoom);
                if (!RoomState.INACTIVE.equals(kurentoRoom.getRoomState())) {
                    instanceProvider.decrementInstanceRoomCount();
                }
                rtcRoomCnt.incrementAndGet();
                totalDelRoomCnt.incrementAndGet();
            }
        } catch (BadRequestException e) {
            throw new RuntimeException("Batch Job Failed", e);
        }

        LocalDateTime date = LocalDateTime.now();
        log.info("##########################");
        log.info("Deleted RTC Room Count : {}", rtcRoomCnt);
        log.info("Deleted Room Total Count : {}", totalDelRoomCnt);
        log.info(date.toString());
        log.info("##########################");
    }

    @Scheduled(cron = "0 0 */6 * * *", zone = "Asia/Seoul") // 6시간 마다 , 타임존 seoul 기준
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
