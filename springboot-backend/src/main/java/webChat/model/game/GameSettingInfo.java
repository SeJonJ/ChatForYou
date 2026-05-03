package webChat.model.game;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class GameSettingInfo {
    @NotBlank(message = "방 아이디는 필수입니다.")
    String roomId; // 방 아이디
    int totalGameRound; // 총 게임 라운드
    @Positive(message = "현재 게임 라운드는 1 이상이어야 합니다.")
    int currentGameRound; // 현재 게임 라운드
    @NotBlank(message = "현재 게임 진행자는 필수입니다.")
    String currentGameLeader; // 현재 게임 진행자
    @NotBlank(message = "현재 게임 주제는 필수입니다.")
    String currentGameSubject; // 현재 게임 주제(정답)
    String currentRoundWinnerId; // 현재 라운드에 이미 확정된 승리자 ID
    Map<String, List<String>> beforeSubjects; // 이전 게임 주제
    List<CatchMindUserDto> gameUserList; // 참여하는 유저
    boolean alreadyPlayedGame;

    @JsonIgnore
    public void newGameRound(){
        this.currentGameRound +=1;
    }
    @JsonIgnore
    public boolean hasCurrentRoundWinner() {
        return currentRoundWinnerId != null && !currentRoundWinnerId.isBlank();
    }
    @JsonIgnore
    public boolean isLastRound() {
        return currentGameRound >= totalGameRound;
    }
}
