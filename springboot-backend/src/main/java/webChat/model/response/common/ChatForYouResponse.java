package webChat.model.response.common;

import lombok.Builder;
import lombok.Getter;
import webChat.model.response.ChatForYouResponseResult;
import webChat.model.room.ChatRoom;
import webChat.model.room.out.ChatRoomOutVo;
import webChat.model.user.UserDto;

@Getter
@Builder
public class ChatForYouResponse {
    private String result;
    private String message;
    private Object data;

    public static ChatForYouResponse ofSuccess(String data) {
        return ChatForYouResponse.builder()
                .result(ChatForYouResponseResult.SUCCESS.name())
                .data(data)
                .build();
    }

    public static ChatForYouResponse ofCreateRoom(ChatRoom chatRoom) {
        return ChatForYouResponse.builder()
                .result(ChatForYouResponseResult.SUCCESS.name())
                .data(chatRoom)
                .build();
    }

    public static ChatForYouResponse ofJoinRoom(ChatRoom chatRoom, UserDto userDto) {
        return ChatForYouResponse.builder()
                .result(ChatForYouResponseResult.SUCCESS.name())
                .data(ChatRoomOutVo.ofJoin(chatRoom, userDto))
                .build();
    }

    public static ChatForYouResponse ofRedirectRoom(ChatRoom chatRoom, ChatForYouResponseResult result) {
        return ChatForYouResponse.builder()
                .result(result.name())
                .data(ChatRoomOutVo.ofRedirect(chatRoom))
                .build();
    }
}
