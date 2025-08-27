package webChat.model.response.common;

import lombok.Builder;
import lombok.Getter;
import webChat.model.room.ChatRoom;
import webChat.model.room.out.ChatRoomOutVo;

@Getter
@Builder
public class ChatForYouResponse {
    private String result;
    private String message;
    private Object data;
    private static final String SUCCESS_RESULT = "success";
    private static final String REDIRECT = "success";

    public static ChatForYouResponse ofSuccess(String data) {
        return ChatForYouResponse.builder()
                .result(SUCCESS_RESULT)
                .data(data)
                .build();
    }

    public static ChatForYouResponse ofCreateRoom(ChatRoom chatRoom) {
        return ChatForYouResponse.builder()
                .result(SUCCESS_RESULT)
                .data(chatRoom)
                .build();
    }

    public static ChatForYouResponse ofJoinRoom(ChatRoom chatRoom) {
        return ChatForYouResponse.builder()
                .result(SUCCESS_RESULT)
                .data(ChatRoomOutVo.ofJoin(chatRoom))
                .build();
    }
}
