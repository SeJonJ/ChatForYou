package webChat.model.game;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class AnswerReq {
    @NotBlank(message = "방 아이디는 필수입니다.")
    private String roomId;
    @NotBlank(message = "사용자 아이디는 필수입니다.")
    private String userId;
    @NotEmpty(message = "정답 후보는 최소 1개 이상이어야 합니다.")
    private List<String> answers;
}
