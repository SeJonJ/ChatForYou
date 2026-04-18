package webChat.service.game;

import org.apache.coyote.BadRequestException;
import webChat.controller.ExceptionController;
import webChat.model.game.*;

import java.util.List;

public interface CatchMindService {
    /**
     * 방에서 게임이 플레이 된 적이 있는지 확인
     * @param roomId
     * @return 게임 플레이 여부
     */
    boolean chkAlreadyPlayedGame(String roomId) throws BadRequestException;

    /**
     * python server 에 게임 대주제를 요청
     * @return 5개의 대주제를 return
     * @throws Exception
     */
    GameTitles getTitles() throws Exception;

    /**
     * 대주제에 맞는 게임 소주제 요청
     * @param roomId
     * @param gameSubjects
     * @return 5개의 게임 소주제 return
     * @throws Exception
     */
    GameSubjects getSubjects(String roomId, GameSubjects gameSubjects) throws Exception;

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
    CatchMindUserDto updateUser(GameStatus gameStatus, String roomId, String userId) throws BadRequestException;

    /**
     * 게임 결과 정보 return
     * @param roomId
     * @return 방에서의 게임 결과
     */
    GameSettingInfo getGameResult(String roomId) throws BadRequestException;

    /**
     * 게임 정답 확인
     * @return 정답 여부
     * @throws BadRequestException
     */
    AnswerResp checkAnswer(AnswerReq answerReq) throws BadRequestException;
}
