package webChat.model.game;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class AnswerReq {
    private String roomId;
    private String userId;
    private List<String> answers;
}
