package webChat.model.user;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import webChat.entity.SocialUser;
import webChat.model.login.OauthRedis;

import java.util.Calendar;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserDto {
    private String userId; // 유저 고유값
    private String nickName; // 유저 닉네임
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String photoUrl;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String type; // 소셜 타입
    private Long loginDate; // 마지막 로그인 시간

    public UserDto(String userId, String nickName) {
        this.userId = userId;
        this.nickName = nickName;
    }

    public static UserDto of(SocialUser socialUser, OauthRedis oauthRedis) {
        return UserDto.builder()
                .userId(oauthRedis.getEmail())
                .nickName(oauthRedis.getNickname())
                .photoUrl(socialUser.getPhotoUrl())
                .type(socialUser.getType())
                .loginDate(socialUser.getLastLoginDate())
                .build();
    }

    public static UserDto ofAdmin() {
        return UserDto.builder()
                .userId("admin_user")
                .nickName("admin_user")
                .loginDate(Calendar.getInstance().getTimeInMillis())
                .build();
    }

}
