package webChat.model.game;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class GameStatusRequest {
    @NotBlank(message = "방 아이디는 필수입니다.")
    private String roomId;

    @NotBlank(message = "사용자 아이디는 필수입니다.")
    private String userId;

    @NotNull(message = "게임 상태는 필수입니다.")
    private GameStatus gameStatus;
}
