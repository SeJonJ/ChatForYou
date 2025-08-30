package webChat.model.room.out;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;
import webChat.model.chat.ChatType;
import webChat.model.room.ChatRoom;
import webChat.model.room.RoomState;
import java.util.Random;
import java.util.UUID;

// TODO 유저 리스트 추가
@Builder
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatRoomOutVo {
    private String roomId; // 채팅방 고유번호
    private String roomName; // 채팅방 이름
    private String userId; // 유저 고유값
    private String nickName; // 유저 닉네임
    private int userCount; // 채팅방 인원수
    private int maxUserCnt; // 채팅방 최대 인원 제한
    private String roomPwd; // 채팅방 삭제시 필요한 pwd
    private boolean secretChk; // 채팅방 잠금 여부
    private ChatType roomType; //  채팅 타입 여부
    private RoomState roomState;

    public static ChatRoomOutVo of(ChatRoom chatRoom) {
        return ChatRoomOutVo.builder()
                .roomId(chatRoom.getRoomId())
                .roomName(chatRoom.getRoomName())
                .userCount(chatRoom.getUserCount())
                .maxUserCnt(chatRoom.getMaxUserCnt())
                .roomPwd(chatRoom.getRoomPwd())
                .secretChk(chatRoom.isSecretChk())
                .roomType(chatRoom.getChatType())
                .roomState(chatRoom.getRoomState())
                .build();
    }

    public static ChatRoomOutVo ofJoin(ChatRoom chatRoom) {
        return ChatRoomOutVo.builder()
                .roomId(chatRoom.getRoomId())
                .roomName(chatRoom.getRoomName())
                .userId(UUID.randomUUID().toString().split("-")[0])
                .nickName("guest" + (new Random().nextInt(100)+1))
                .userCount(chatRoom.getUserCount())
                .maxUserCnt(chatRoom.getMaxUserCnt())
                .roomPwd(chatRoom.getRoomPwd())
                .secretChk(chatRoom.isSecretChk())
                .roomType(chatRoom.getChatType())
                .roomState(chatRoom.getRoomState())
                .build();
    }

    public static ChatRoomOutVo ofRedirect(ChatRoom chatRoom) {
        return ChatRoomOutVo.builder()
                .roomId(chatRoom.getRoomId())
                .roomName(chatRoom.getRoomName())
                .userCount(chatRoom.getUserCount())
                .maxUserCnt(chatRoom.getMaxUserCnt())
                .roomPwd(chatRoom.getRoomPwd())
                .secretChk(chatRoom.isSecretChk())
                .roomType(chatRoom.getChatType())
                .roomState(RoomState.REDIRECT)
                .build();
    }
}
