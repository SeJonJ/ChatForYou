package webChat.model.room.in;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import webChat.model.chat.ChatType;

@Builder
@Getter
public class ChatRoomInVo {
    private String roomId; // 채팅방 고유번호
    @NotBlank(message = "방 이름은 필수입니다.")
    private String roomName; // 채팅방 이름
    private String creator;
    private int userCount; // 채팅방 인원수
    @Min(value = 2, message = "채팅방 최대 인원은 2명 이상이어야 합니다.")
    @Max(value = 6, message = "채팅방 최대 인원은 6명을 초과할 수 없습니다.")
    private int maxUserCnt; // 채팅방 최대 인원 제한
    @NotBlank(message = "비밀번호는 필수입니다.")
    private String roomPwd; // 채팅방 삭제시 필요한 pwd
    private boolean secretChk; // 채팅방 잠금 여부
    @NotNull(message = "채팅 타입은 필수입니다.")
    private ChatType roomType; //  채팅 타입 여부
}
