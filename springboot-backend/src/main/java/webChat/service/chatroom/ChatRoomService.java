package webChat.service.chatroom;

import com.google.common.collect.Lists;
import io.github.dengliming.redismodule.redisearch.index.Document;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import webChat.model.redis.RedisKeyPrefix;
import webChat.model.routing.RoomRoutingInfo;
import webChat.controller.ExceptionController;
import webChat.model.chat.ChatType;
import webChat.model.redis.DataType;
import webChat.model.redis.RedisIndex;
import webChat.model.redis.RoomSearchCriteria;
import webChat.model.room.ChatRoom;
import webChat.model.room.KurentoRoom;
import webChat.model.room.RoomState;
import webChat.model.room.in.ChatRoomInVo;
import webChat.service.analysis.AnalysisService;
import webChat.service.file.FileService;
import webChat.service.kafka.ChatKafkaProducer;
import webChat.service.kurento.KurentoRoomManager;
import webChat.service.redis.RedisService;
import webChat.service.routing.RoutingInstanceProvider;
import webChat.service.routing.RoutingService;
import webChat.utils.StringUtil;

import java.util.*;


@Service
@RequiredArgsConstructor
@Slf4j
public class ChatRoomService {

    // 채팅방 삭제에 따른 채팅방의 사진 삭제를 위한 fileService 선언
    private final FileService fileService;

    private final RedisService redisService;

    private final KurentoRoomManager kurentoRoomManager;

    private final AnalysisService analysisService;

    private final SseService sseService;
    private final RoutingInstanceProvider instanceProvider;
    private final RoutingService routingService;

    private final ChatKafkaProducer chatKafkaProducer;

    @Value("${chatforyou.room.max_user_count}")
    private int MAX_USER_COUNT;

    private final List<RoomState> ROOM_STATES = Lists.newArrayList(RoomState.ACTIVE, RoomState.CREATED);

    // roomName 로 채팅방 만들기
    public ChatRoom createChatRoom(ChatRoomInVo chatRoomInVo, String roomId) throws BadRequestException {

        this.validateRoomInfo(chatRoomInVo.getRoomName(), chatRoomInVo.getMaxUserCnt());

        if(ChatType.RTC.equals(chatRoomInVo.getRoomType())) {
            // 1. roomId 확인
            roomId = StringUtil.isNullOrEmpty(roomId) ? UUID.randomUUID().toString() : roomId;
            // 2. roomId 로 저장된 routing 정보 확인
            RoomRoutingInfo roomRoutingInfo = redisService.getRedisDataByDataType(RedisKeyPrefix.ROOM_ROUTING_PREFIX.getPrefix() + roomId, DataType.ROOM_ROUTING, RoomRoutingInfo.class);
            // 3. 최적의 instanceId 확인
            String selectedInstanceId = instanceProvider.getServerForRoom(roomId, roomRoutingInfo);
            if(roomRoutingInfo != null) {
                selectedInstanceId = roomRoutingInfo.getInstanceId();
            }

            // 현재 서버가 선택된 서버가 아니면 리다이렉트
            if(!instanceProvider.getInstanceId().equals(selectedInstanceId)) {
                return ChatRoom.ofRedirect(chatRoomInVo, roomId, selectedInstanceId);
            }

            ChatRoom chatRoom = kurentoRoomManager.createKurentoRoom(roomId, selectedInstanceId, chatRoomInVo);

            // 새로운 방 생성 시 모든 클라이언트에 이벤트 전송
            sseService.sendRoomCreatedEvent(chatRoom);
            // 방 생성 후 방 개수 증가
            instanceProvider.incrementInstanceRoomCount();
            analysisService.increaseDailyRoomCnt();
            // 새로운 채팅방 생성 이벤트를 Kafka에 발행
            chatKafkaProducer.sendCreateRoomEvent(chatRoom);
            return chatRoom;
        } else {
            throw new BadRequestException("room type is not exist : " + chatRoomInVo.getRoomType());
        }
    }

    // 전체 채팅방 조회
    public List<ChatRoom> getRoomList(String keyword, int pageNum, int pageSize, boolean isAdmin) {
        // 채팅방 생성 순서를 최근순으로 반환
        List<ChatRoom> chatRoomList = new ArrayList<>();
        pageNum = pageNum !=0 ? pageNum - 1 : pageNum;

        RoomSearchCriteria searchCriteria = RoomSearchCriteria.builder()
                .redisIndex(RedisIndex.CHATROOM)
                .keyword(keyword)
                .roomStates(isAdmin ? Collections.emptyList() : ROOM_STATES)
                .pageNum(pageNum)
                .pageSize(pageSize)
                .build();
        List<Document> roomList = redisService.searchRoomListByOptions(searchCriteria);
        for (Document document : roomList) {
            if (document.getFields().get("roomId") == null) {
                continue;
            }
            String roomId = document.getFields().get("roomId").toString().replace("\"", "");
            Map<Object, Object> allChatRoomData = redisService.getAllChatRoomData(roomId);
            if (allChatRoomData.isEmpty() || allChatRoomData.get(DataType.CHATROOM.getType()) == null) {
                continue;
            }

            ChatRoom chatRoom = (ChatRoom) allChatRoomData.get("chatroom");
            chatRoomList.add(chatRoom);

        }

        return chatRoomList;
    }

    /**
     *
     * @Desc room 정보 가져오기
     * @param roomId room 이름
     * @return 만약에 room 이 있다면 해당 room 객체 return
     */
    public ChatRoom findRoomById(String roomId) throws BadRequestException {
        return redisService.getRedisDataByDataType(roomId, DataType.CHATROOM, KurentoRoom.class);
    }

    // 채팅방 비밀번호 조회
    public boolean validatePwd(String roomId, String roomPwd) throws BadRequestException {
        // TODO 방정보 찾을 수 없는 경우 예외처리
        ChatRoom chatRoom = redisService.getRedisDataByDataType(roomId, DataType.CHATROOM, KurentoRoom.class);
        return chatRoom.getRoomPwd().equals(roomPwd);
    }

    // maxUserCnt 에 따른 채팅방 입장 여부
    public boolean chkRoomUserCnt(String roomId) throws BadRequestException {
        ChatRoom chatRoom = redisService.getRedisDataByDataType(roomId, DataType.CHATROOM, KurentoRoom.class);

        if (chatRoom == null || chatRoom.getUserCount() + 1 > chatRoom.getMaxUserCnt()) {
            return false;
        }

        return true;
    }


    /**
     * 방 영구 삭제
     * @param kurentoRoom
     * @return
     * @throws BadRequestException
     */
    public void delChatRoom(KurentoRoom kurentoRoom) throws BadRequestException, ExceptionController.DelRoomException {
        try {
            kurentoRoomManager.deleteKurentoRoom(kurentoRoom);
            redisService.deleteAllChatRoomData(kurentoRoom.getRoomId());

            // 채팅방 안에 있는 파일 삭제
            fileService.deleteFileDir(kurentoRoom.getRoomId());

            log.info("Room {} deleted permanently", kurentoRoom.getRoomId());
        } catch (Exception e) {
            throw new ExceptionController.DelRoomException("Hard Delete Room Exception");
        }

    }

    /**
     * roomId 만 받아서 방을 inactive 상태로 변경
     * @param roomId
     * @return
     * @throws BadRequestException
     */
    public boolean delChatRoom(String roomId) throws BadRequestException {
        KurentoRoom kurentoRoom = redisService.getRedisDataByDataType(roomId, DataType.CHATROOM, KurentoRoom.class);

        if(kurentoRoom.getUserCount() <= 0) {
            // 채팅방 state 를 deactive 로 업데이트 -> batch 로 삭제
            kurentoRoom.deactivate();
            redisService.updateChatRoom(kurentoRoom);
        } else {
            throw new ExceptionController.DelRoomException("Soft Delete Room Exception");
        }
        // 채팅방 삭제 이벤트를 Kafka에 발행
        chatKafkaProducer.sendDeleteRoomEvent(kurentoRoom);
        // 방 삭제 시 해당 instance 에서 방 제거
        instanceProvider.decrementInstanceRoomCount();

        log.info("Room {} state changed {}", kurentoRoom.getRoomId(), RoomState.INACTIVE.getType());
        return true;
    }

    // 채팅방 수정
    public ChatRoom updateRoom(String roomId, String roomName, String roomPwd, int maxUserCnt) throws BadRequestException {
        ChatRoom chatRoom = redisService.getRedisDataByDataType(roomId, DataType.CHATROOM, KurentoRoom.class);
        // 방 이름 혹은 최대 인원 수 변경 시에 kafka -> sse를 통해 실시간 방 업데이트
        if (!chatRoom.getRoomName().equals(roomName) || chatRoom.getMaxUserCnt() != maxUserCnt) {
            chatKafkaProducer.sendChangedRoomSettingEvent(chatRoom);
        }
        chatRoom.setRoomName(roomName);
        chatRoom.setRoomPwd(roomPwd);
        chatRoom.setMaxUserCnt(maxUserCnt);
        redisService.updateChatRoom(chatRoom);

        return chatRoom;
    }

    public void validateRoomInfo(String roomName, int maxUserCnt) throws BadRequestException {
        if(maxUserCnt > MAX_USER_COUNT) {
            throw new BadRequestException("can not over max user count : " + maxUserCnt);
        }

        boolean hasRoomName = redisService.checkRoomName(roomName);
        if(hasRoomName) {
            throw new ExceptionController.AlreadyExistRoomNameException("room name is already exist : " + roomName);
        }
    }
}
