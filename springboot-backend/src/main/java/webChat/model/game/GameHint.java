package webChat.model.game;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(access = AccessLevel.PRIVATE)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
public class GameHint {
    private int totalChosung;
    private int matchedChosung;
    private String hintChosung; // 아메리카노 -> ㅇㅁ**ㄴ

    public static GameHint of(int totalChosung, int matchedChosung, String hintChosung) {
        boolean showChosungHint = (matchedChosung * 2) >= totalChosung;

        return GameHint.builder()
                .totalChosung(totalChosung)
                .matchedChosung(matchedChosung)
                .hintChosung(showChosungHint ? hintChosung : null)
                .build();
    }
}
