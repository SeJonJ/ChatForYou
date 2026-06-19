package webChat.model.turn.in;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class TurnCredentialInVo {

    @NotBlank
    private String roomId;
}
