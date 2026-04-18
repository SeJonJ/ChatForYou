package webChat.model.game;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnswerResp {
    @JsonProperty("isCorrect")
    private boolean isCorrect;
    private CatchMindUserDto catchMindUser;
    private GameHint hint;

    public static AnswerResp ofIncorrect(GameHint hint, boolean isCorrect) {
        return AnswerResp.builder()
                .isCorrect(isCorrect)
                .hint(hint)
                .build();
    }

    public static AnswerResp ofCorrect(CatchMindUserDto catchMindUser, boolean isCorrect) {
        return AnswerResp.builder()
                .isCorrect(isCorrect)
                .catchMindUser(catchMindUser)
                .build();
    }
}
