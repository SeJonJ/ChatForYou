package webChat.service.game;

import webChat.model.game.*;
import webChat.model.response.game.GameResultResponse;

public interface CatchMindService {
    /**
     * 방에서 게임이 플레이 된 적이 있는지 확인
     * @param roomId
     * @return 게임 플레이 여부
     */
    boolean chkAlreadyPlayedGame(String roomId);

    /**
     * python server 에 게임 대주제를 요청
     * @return 5개의 대주제를 return
     */
    GameTitles getTitles();

    /**
     * 대주제에 맞는 게임 소주제 요청
     * @param roomId
     * @param gameSubjects
     * @return 5개의 게임 소주제 return
     */
    GameSubjects getSubjects(String roomId, GameSubjects gameSubjects);

    /**
     * 게임 전체 정보 세팅
     * @param requestSettingInfo
     */
    void setGameSettingInfo(GameSettingInfo requestSettingInfo);

    /**
     * 유저 정보 업데이트
     * @param gameStatus
     * @param roomId
     * @param userId
     * @return 유저 정보
     */
    CatchMindUserDto updateUser(GameStatus gameStatus, String roomId, String userId);

    /**
     * 게임 결과 정보 return
     * @param roomId
     * @return 게임 결과 또는 라운드 동기화 정보
     */
    GameResultResponse getGameResult(String roomId);

    /**
     * 게임 정답 확인
     * @return 정답 여부
     */
    AnswerResp checkAnswer(AnswerReq answerReq);
}
