package webChat.model.response.game;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;
import webChat.model.game.GameSettingInfo;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GameResultResponse {

    private final boolean syncNeeded;
    private final boolean gameCompleted;
    private final Integer currentGameRound;
    private final Integer totalGameRound;
    private final String currentGameLeader;
    private final String currentRoundWinnerId;
    private final GameSettingInfo gameResult;

    public static GameResultResponse syncNeeded(int currentGameRound) {
        return GameResultResponse.builder()
                .syncNeeded(true)
                .gameCompleted(false)
                .currentGameRound(currentGameRound)
                .build();
    }

    public static GameResultResponse syncNeeded(GameSettingInfo gameSettingInfo) {
        return GameResultResponse.builder()
                .syncNeeded(true)
                .gameCompleted(false)
                .currentGameRound(gameSettingInfo.getCurrentGameRound())
                .totalGameRound(gameSettingInfo.getTotalGameRound())
                .currentGameLeader(gameSettingInfo.getCurrentGameLeader())
                .currentRoundWinnerId(gameSettingInfo.getCurrentRoundWinnerId())
                .build();
    }

    public static GameResultResponse completed(GameSettingInfo gameResult) {
        return GameResultResponse.builder()
                .syncNeeded(false)
                .gameCompleted(true)
                .currentGameRound(gameResult.getCurrentGameRound())
                .totalGameRound(gameResult.getTotalGameRound())
                .currentGameLeader(gameResult.getCurrentGameLeader())
                .currentRoundWinnerId(gameResult.getCurrentRoundWinnerId())
                .gameResult(gameResult)
                .build();
    }
}
