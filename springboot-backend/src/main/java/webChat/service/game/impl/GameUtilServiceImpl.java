package webChat.service.game.impl;

import kr.co.shineware.nlp.komoran.core.Komoran;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import webChat.model.game.AnswerReq;
import webChat.model.game.GameHint;
import webChat.model.redis.DataType;
import webChat.model.room.KurentoRoom;
import webChat.service.game.GameUtilService;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.service.redis.RedisService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class GameUtilServiceImpl implements GameUtilService {
    private final Komoran komoran; // 형태소 분석기
    private final RedisService redisService;

    private static final char[] CHOSUNG = {
            'ㄱ','ㄲ','ㄴ','ㄷ','ㄸ','ㄹ','ㅁ','ㅂ','ㅃ',
            'ㅅ','ㅆ','ㅇ','ㅈ','ㅉ','ㅊ','ㅋ','ㅌ','ㅍ','ㅎ'
    };

    @Override
    public GameHint getChosungHint(AnswerReq answerReq) {
        KurentoRoom kurentoRoom = redisService.getRedisDataByDataType(answerReq.getRoomId(), DataType.CHATROOM, KurentoRoom.class);
        if (Objects.isNull(kurentoRoom)) {
            throw new ChatForYouException(ErrorCode.ROOM_NOT_FOUND);
        }

        String subject = kurentoRoom.getGameSettingInfo().getCurrentGameSubject();
        // 초성 추출
        List<Character> subjectCho = extractChosung(subject);

        // answer 중 가장 초성이 많이 일치하는 단어 확인
        int bestMatchedCho = 0;
        // 기본은 전부 마스킹
        String bestHintStr = "*".repeat(subjectCho.size());
        for(String answer : answerReq.getAnswers()) {
            List<Character> answerCho = extractChosung(answer);
            int matched = 0;
            StringBuilder hintBuilder =  new StringBuilder();

            // 위치별 초성 비교 -> 일치하면 초성, 불일치하면 *
            for(int i = 0; i < subjectCho.size(); i++) {
                if (i < answerCho.size() && subjectCho.get(i).equals(answerCho.get(i))) {
                    hintBuilder.append(subjectCho.get(i));
                    matched++;
                } else {
                    hintBuilder.append('*');
                }
            }

            // 가장 많이 맞은 answer 기준으로 힌트 선택
            if(matched > bestMatchedCho) {
                bestMatchedCho = matched;
                bestHintStr = hintBuilder.toString();
            }
        }

        return GameHint.of(subjectCho.size(), bestMatchedCho, bestHintStr);
    }

    @Override
    public boolean matchAnswer(String subject, String answer) {
        // 1. 정규식 이용해서 매칭 확인
        String normSubject = normalize(subject);
        String normAnswer = normalize(answer);
        if (normSubject.equals(normAnswer)) return true;

        // 2. KOMORAN 명사 추출 비교
        List<String> subjectNouns = komoran.analyze(subject).getNouns();
        List<String> answerNouns = komoran.analyze(answer).getNouns();

        // 명사를 이어붙인 결과 비교 (복합어/고유명사 대응)
        String joinedSubject = String.join("", subjectNouns);
        String joinedAnswer = String.join("", answerNouns);
        if (!joinedSubject.isEmpty() && joinedSubject.equals(joinedAnswer)) return true;

        // 정답의 명사가 입력의 명사에 모두 포함되는지 비교
        if (!subjectNouns.isEmpty() && answerNouns.containsAll(subjectNouns)) return true;

        return false;
    }

    private String normalize(String text) {
        return text.trim().replaceAll("[\\s\\p{Punct}]", "").toLowerCase();
    }


    /**
     * 초성추출 정규식
     * @param text
     * @return
     */
    private List<Character> extractChosung(String text) {
        List<Character> result = new ArrayList<>();
        for (char c : text.toCharArray()) {
            if (c >= 0xAC00 && c <= 0xD7A3) {
                int index = (c - 0xAC00) / 588;
                result.add(CHOSUNG[index]);
            }
        }
        return result;
    }
}
