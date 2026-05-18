package webChat.service.game;

import webChat.model.game.AnswerReq;
import webChat.model.game.GameHint;

public interface GameUtilService {
    /**
     * 초성 힌드 응답
     * @param answerReq
     * @return
     */
    GameHint getChosungHint(AnswerReq answerReq);

    /**
     * 정답 비교
     * @param subject
     * @param answer
     * @return
     */
    boolean matchAnswer(String subject, String answer);
}
