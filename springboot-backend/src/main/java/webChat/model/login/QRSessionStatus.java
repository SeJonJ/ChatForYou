package webChat.model.login;

import lombok.Getter;

@Getter
public enum QRSessionStatus {
    PENDING, // 대기중
    AUTHENTICATED, // 인증완료
    EXPIRED // 만료
    ;
}
