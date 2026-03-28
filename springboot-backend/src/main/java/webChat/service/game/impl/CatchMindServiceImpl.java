package webChat.service.game.impl;

import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import webChat.config.CatchMindConfig;
import webChat.controller.ExceptionController;
import webChat.model.game.*;
import webChat.model.redis.DataType;
import webChat.model.room.KurentoRoom;
import webChat.service.game.CatchMindService;
import webChat.service.game.GameUtilService;
import webChat.service.redis.RedisService;
import webChat.utils.HttpUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class CatchMindServiceImpl implements CatchMindService {

    private final int WINNER_SCORE = 100;
    private final int MORE_TIME_SCORE = 50;
    private final int TOO_MANY_FAIL_SCORE = -50;

    private final CatchMindConfig catchMindAPI;
    private final RedisService redisService;
    private final GameUtilService gameUtilService;

    @Value("${catchmind.python.api.titles}")
    private String gameTitleUrl;

    @Value("${catchmind.python.api.subjects}")
    private String gameSubjectUrl;

    // python 서버와 통신 후 예외가 발생하는 경우 titles 대체를 위한 list
    private final List<String> TITLES_EX = Lists.newArrayList("동물","식물","애니메이션","게임","영화");
    private final List<String> SUBJECTS_EX = Lists.newArrayList("히오스","롤","메이플스토리","슈퍼마리오","젤다의전설");

    // 정답 동시성 제어를 위한 map
    private Map<String, AtomicBoolean> winnerProcessMap = new ConcurrentHashMap<>();

    @Override
    public boolean chkAlreadyPlayedGame(String roomId) throws BadRequestException {
        KurentoRoom kurentoRoom = redisService.getRedisDataByDataType(roomId, DataType.CHATROOM, KurentoRoom.class);
        if (Objects.isNull(kurentoRoom)) {
            throw new BadRequestException("Room not found with ID: " + roomId);
        }
        if (Objects.nonNull(kurentoRoom.getGameSettingInfo()) && kurentoRoom.getGameSettingInfo().isAlreadyPlayedGame()) {
            return true;
        }
        return false;
    }

    @Override
    public GameTitles getTitles() {
        GameTitles titles = null;
        try{
            titles = HttpUtil.get(catchMindAPI.getUrl()+ gameTitleUrl, new HttpHeaders(), new ConcurrentHashMap<>(), GameTitles.class);
            log.info("titles :: {}",titles.toString());
            return titles;
        } catch (Exception e){ // 예외 발생 시 기본 리스트를 반환
            e.printStackTrace();
            titles = new GameTitles(TITLES_EX);
            return titles;
        }
    }

    @Override
    public GameSubjects getSubjects(String roomId, GameSubjects gameSubjects) {

        try{
            KurentoRoom kurentoRoom = redisService.getRedisDataByDataType(roomId, DataType.CHATROOM, KurentoRoom.class);
            GameSettingInfo gameSettingInfo = kurentoRoom.getGameSettingInfo();
            if (Objects.isNull(gameSettingInfo)) {
                gameSettingInfo = new GameSettingInfo();
                gameSettingInfo.setRoomId(roomId);
                kurentoRoom.setGameSettingInfo(gameSettingInfo);
            }
            setBeforeSubjects(gameSettingInfo, gameSubjects);
            gameSubjects = HttpUtil.post(catchMindAPI.getUrl()+ gameSubjectUrl, new HttpHeaders(), new ConcurrentHashMap<>(), gameSubjects, GameSubjects.class);
            gameSubjects.getBeforeSubjects().addAll(gameSubjects.getSubjects());
            gameSettingInfo.getBeforeSubjects().put(gameSubjects.getTitle(), gameSubjects.getBeforeSubjects());
            log.info("subjects :: {}",gameSubjects.toString());

            redisService.updateChatRoom(kurentoRoom);
            return gameSubjects;
        } catch (Exception e){ // 예외 발생 시 기본 리스트를 반환
            e.printStackTrace();
            gameSubjects = GameSubjects.builder()
                    .title("게임")
                    .subjects(SUBJECTS_EX)
                    .build();
            return gameSubjects;
        }
    }

    @Override
    public void setGameSettingInfo(GameSettingInfo requestSettingInfo) {
        String roomId = requestSettingInfo.getRoomId();
        try {
            KurentoRoom kurentoRoom = redisService.getRedisDataByDataType(roomId, DataType.CHATROOM, KurentoRoom.class);
            GameSettingInfo gameInfo = kurentoRoom.getGameSettingInfo();
            if (requestSettingInfo.getGameUserList() != null) {
                gameInfo.setGameUserList(requestSettingInfo.getGameUserList());
            }

            // TODO 추후에는 선택할 수 있게 하지만 현재는 3 라운드로 고정
            gameInfo.setTotalGameRound(3);
            gameInfo.setCurrentGameRound(requestSettingInfo.getCurrentGameRound());
            gameInfo.setCurrentGameSubject(requestSettingInfo.getCurrentGameSubject());
            gameInfo.setCurrentGameLeader(requestSettingInfo.getCurrentGameLeader());
            redisService.updateChatRoom(kurentoRoom);

            winnerProcessMap.put(roomId, new AtomicBoolean(false));
            log.info(">>>> CatchMind Game is Ready To GO");
        } catch (Exception e) {
            // TODO 추후 예외처리 필요
            e.printStackTrace();
        }
    }

    @Override
    public CatchMindUserDto updateUser(GameStatus gameStatus, String roomId, String userId) throws BadRequestException {
        KurentoRoom kurentoRoom = redisService.getRedisDataByDataType(roomId, DataType.CHATROOM, KurentoRoom.class);
        // TODO 예외처리 필요
        if (Objects.isNull(kurentoRoom)) {
            throw new BadRequestException("Room not found with ID: " + roomId);
        }

        GameSettingInfo gameSettingInfo = kurentoRoom.getGameSettingInfo();
        List<CatchMindUserDto> catchMindUserList = gameSettingInfo.getGameUserList();
        if (CollectionUtils.isEmpty(catchMindUserList)) {
            // TODO 예외처리 필요
        }

        Optional<CatchMindUserDto> user = catchMindUserList.stream()
                .filter(u -> {
                    return u.getUserId().equals(userId);
                }).findFirst();

        if (user.isEmpty()) {
            // TODO 예외처리하기
            throw new BadRequestException("User not found with ID: " + userId);
        }

        // lock 이 필요한 게임 상태
        boolean needLockStatus = gameStatus == GameStatus.TIMEOUT;
        if (needLockStatus) {
            AtomicBoolean isProcess = winnerProcessMap.get(roomId);
            if (!isProcess.compareAndSet(false, true)) {
                throw new BadRequestException("WINNER_CHECK_PROCESSED");
            }
        }

        CatchMindUserDto catchMindUser = user.get();
        try {
            switch (gameStatus) {
                case TIMEOUT: // 타이머 만료 시 출제자 자동 승리
                    if(!gameSettingInfo.getCurrentGameLeader().equals(catchMindUser.getUserId())) {
                        throw new BadRequestException("게임 진행자가 아닙니다");
                    }
                    updateUserScore(catchMindUser, this.WINNER_SCORE);
                    catchMindUser.setWinCount(catchMindUser.getWinCount() + 1);
                    // 마지막 라운드가 아닐 때만 라운드 증가
                    if (gameSettingInfo.getCurrentGameRound() < gameSettingInfo.getTotalGameRound()) {
                        gameSettingInfo.newGameRound();
                    }
                    break;
                case MORE_TIME:
                    updateUserScore(catchMindUser, this.MORE_TIME_SCORE);
                    break;

                case TOO_MANY_FAIL:
                    updateUserScore(catchMindUser, this.TOO_MANY_FAIL_SCORE);
                    break;
                default:
                    throw new BadRequestException("지원하지 않는 게임 상태입니다 : " + gameStatus);
            }
            redisService.updateChatRoom(kurentoRoom);
        } finally {
            if(needLockStatus){
                winnerProcessMap.get(roomId).compareAndSet(true, false);
            }
        }
        return catchMindUser;
    }

    @Override
    public GameSettingInfo getGameResult(String roomId) throws BadRequestException {
        KurentoRoom kurentoRoom = redisService.getRedisDataByDataType(roomId, DataType.CHATROOM, KurentoRoom.class);

        // 게임 라운드 확인 및 결과 보내주기
        GameSettingInfo gameSettingInfo = kurentoRoom.getGameSettingInfo();
        if (CollectionUtils.isEmpty(gameSettingInfo.getGameUserList())) {
            // TODO 예외처리 필요
        }

        // 게임 라운드와 전체 라운드가 일치하지 않는 경우
        // 프론트와 서버 간 라운드 정보가 일치하지 않는 경우 일치를 위한  Exception
        if (gameSettingInfo.getCurrentGameRound() != gameSettingInfo.getTotalGameRound()) {
            throw new ExceptionController.SyncGameRound(gameSettingInfo.getCurrentGameRound());
        }

        // score 비교 로직 수행
        // score 와 wincount 에 가산해서 비교
        gameSettingInfo.getGameUserList().sort((u1, u2) -> {
            int score1 = u1.getScore() + u1.getWinCount() * 100;
            int score2 = u2.getScore() + u2.getWinCount() * 100;
            return Integer.compare(score2, score1); // 내림차순 정렬
        });

        gameSettingInfo.getGameUserList().get(0).setWiner(true);
        gameSettingInfo.setAlreadyPlayedGame(true);
        redisService.updateChatRoom(kurentoRoom);
        winnerProcessMap.remove(roomId);
        return gameSettingInfo;
    }

    @Override
    public AnswerResp checkAnswer(AnswerReq answerReq) throws BadRequestException {
        String roomId = answerReq.getRoomId();
        String userId = answerReq.getUserId();

        KurentoRoom kurentoRoom = redisService.getRedisDataByDataType(roomId, DataType.CHATROOM, KurentoRoom.class);
        // TODO 예외처리 필요
        if (Objects.isNull(kurentoRoom)) {
            throw new BadRequestException("Room not found with ID: " + roomId);
        }

        GameSettingInfo gameSettingInfo = kurentoRoom.getGameSettingInfo();
        List<CatchMindUserDto> catchMindUserList = gameSettingInfo.getGameUserList();
        if (CollectionUtils.isEmpty(catchMindUserList)) {
            // TODO 예외처리 필요
        }

        // 리더 본인은 정답 제출 불가
        if (userId.equals(gameSettingInfo.getCurrentGameLeader())) {
            throw new BadRequestException("게임 진행자는 정답을 제출할 수 없습니다");
        }

        Optional<CatchMindUserDto> user = catchMindUserList.stream()
                .filter(u -> {
                    return u.getUserId().equals(userId);
                }).findFirst();

        if (user.isEmpty()) {
            // TODO 예외처리하기
            throw new BadRequestException("User not found with ID: " + userId);
        }

        // matchAnswer 로 실제 정답과 요청한 정답 비교
        String currentGameSubject = gameSettingInfo.getCurrentGameSubject();
        boolean isCorrect = false;
        for (String answer : answerReq.getAnswers()) {
            if (gameUtilService.matchAnswer(currentGameSubject, answer)) {
                isCorrect = true;
                break;
            }
        }

        // 오답 -> hint 확인
        if (!isCorrect) {
            return AnswerResp.ofIncorrect(gameUtilService.getChosungHint(answerReq), isCorrect);
        } else {
            // 정답 -> lock 획득 후 점수 업데이트
            AtomicBoolean isProcess = winnerProcessMap.get(roomId);
            if (!isProcess.compareAndSet(false, true)) {
                throw new BadRequestException("WINNER_CHECK_PROCESSED");
            }

            CatchMindUserDto catchMindUser = user.get();
            try {
                updateUserScore(catchMindUser, this.WINNER_SCORE);
                catchMindUser.setWinCount(catchMindUser.getWinCount() + 1);
                if (gameSettingInfo.getCurrentGameRound() < gameSettingInfo.getTotalGameRound()) {
                    gameSettingInfo.newGameRound();
                }
                redisService.updateChatRoom(kurentoRoom);
            } finally {
                winnerProcessMap.get(roomId).compareAndSet(true, false);
            }

            return AnswerResp.ofCollect(catchMindUser, isCorrect);
        }
    }

    private void updateUserScore(CatchMindUserDto catchMindUser, int score){
        int updatedScore = catchMindUser.getScore()+score;
        catchMindUser.setScore(updatedScore);
        log.info(">>>> Round Winner and Get Score!! => {} :: {}", catchMindUser.getNickName(), catchMindUser.getScore());
    }

    private void setBeforeSubjects(GameSettingInfo gameSettingInfo, GameSubjects gameSubjects) {
        if (CollectionUtils.isEmpty(gameSettingInfo.getBeforeSubjects())) {
            Map<String, List<String>> beforeSubjects = new ConcurrentHashMap<>();
            beforeSubjects.put(gameSubjects.getTitle(), Collections.emptyList());
            gameSettingInfo.setBeforeSubjects(beforeSubjects);
            gameSubjects.setBeforeSubjects(Collections.emptyList());
        } else {
            List<String> beforeSubjects = gameSettingInfo.getBeforeSubjects()
                    .getOrDefault(gameSubjects.getTitle(), Collections.emptyList());
            gameSubjects.setBeforeSubjects(beforeSubjects);
        }
    }
}
