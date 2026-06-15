package webChat.model.response;

import lombok.Getter;

@Getter
public enum ChatForYouResponseResult {
    SUCCESS,
    REDIRECT_ROOM,
    REDIRECT_RECOVER,
    REDIRECT_DASHBOARD,
    ;
}
