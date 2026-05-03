package webChat.service.game.impl;

import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import webChat.config.CatchMindConfig;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.model.game.*;
import webChat.model.redis.DataType;
import webChat.model.response.game.GameResultResponse;
import webChat.model.room.KurentoRoom;
import webChat.service.game.CatchMindService;
import webChat.service.game.GameUtilService;
import webChat.service.redis.RedisService;
import webChat.utils.HttpUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

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

    /**
     * 방에서 이미 게임이 완료되었는지 확인한다.
     *
     * @param roomId 채팅방 ID
     * @return 이미 플레이된 게임이면 true
     */
    @Override
    public boolean chkAlreadyPlayedGame(String roomId) {
        KurentoRoom kurentoRoom = redisService.getRedisDataByDataType(roomId, DataType.CHATROOM, KurentoRoom.class);
        if (Objects.isNull(kurentoRoom)) {
            throw new ChatForYouException(ErrorCode.ROOM_NOT_FOUND);
        }
        if (Objects.nonNull(kurentoRoom.getGameSettingInfo()) && kurentoRoom.getGameSettingInfo().isAlreadyPlayedGame()) {
            return true;
        }
        return false;
    }

    /**
     * Python 추천 서버에서 대주제 목록을 조회한다.
     * 실패 시 기본 대주제 목록으로 fallback 한다.
     *
     * @return CatchMind 대주제 목록
     */
    @Override
    public GameTitles getTitles() {
        GameTitles titles = null;
        try{
            titles = HttpUtil.get(catchMindAPI.getUrl()+ gameTitleUrl, new HttpHeaders(), new ConcurrentHashMap<>(), GameTitles.class);
            log.info("titles :: {}",titles.toString());
            return titles;
        } catch (Exception e){ // 예외 발생 시 기본 리스트를 반환
            log.warn("Failed to fetch catchmind titles. Falling back to defaults.", e);
            titles = new GameTitles(TITLES_EX);
            return titles;
        }
    }

    /**
     * 선택한 대주제를 기준으로 소주제 목록을 조회한다.
     * 실패 시 기본 소주제 목록으로 fallback 한다.
     *
     * @param roomId 채팅방 ID
     * @param gameSubjects 현재 대주제 정보
     * @return 소주제 목록
     */
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
            log.warn("Failed to fetch catchmind subjects. Falling back to defaults: roomId={}", roomId, e);
            gameSubjects = GameSubjects.builder()
                    .title("게임")
                    .subjects(SUBJECTS_EX)
                    .build();
            return gameSubjects;
        }
    }

    /**
     * 라운드 시작 전 서버 기준 게임 상태를 저장한다.
     *
     * @param requestSettingInfo 게임 설정 정보
     */
    @Override
    public void setGameSettingInfo(GameSettingInfo requestSettingInfo) {
        String roomId = requestSettingInfo.getRoomId();
        try {
            KurentoRoom kurentoRoom = redisService.getRedisDataByDataType(roomId, DataType.CHATROOM, KurentoRoom.class);
            GameSettingInfo gameInfo = kurentoRoom.getGameSettingInfo();
            if (requestSettingInfo.getGameUserList() != null) {
                gameInfo.setGameUserList(requestSettingInfo.getGameUserList());
            }

            // 현재 라운드는 서버에서 3회 고정으로 관리한다.
            gameInfo.setTotalGameRound(3);
            gameInfo.setCurrentGameRound(requestSettingInfo.getCurrentGameRound());
            gameInfo.setCurrentGameSubject(requestSettingInfo.getCurrentGameSubject());
            gameInfo.setCurrentGameLeader(requestSettingInfo.getCurrentGameLeader());
            gameInfo.setCurrentRoundWinnerId(null);
            redisService.updateChatRoom(kurentoRoom);

            winnerProcessMap.put(roomId, new AtomicBoolean(false));
            log.info(">>>> CatchMind Game is Ready To GO");
        } catch (Exception e) {
            log.error("Failed to initialize catchmind game settings: roomId={}", roomId, e);
            throw new ChatForYouException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 타임아웃, 추가 시간, 오답 패널티에 따른 사용자 상태를 갱신한다.
     *
     * @param gameStatus 상태 변경 유형
     * @param roomId 채팅방 ID
     * @param userId 사용자 ID
     * @return 갱신된 사용자 정보
     */
    @Override
    public CatchMindUserDto updateUser(GameStatus gameStatus, String roomId, String userId) {
        KurentoRoom kurentoRoom = redisService.getRedisDataByDataType(roomId, DataType.CHATROOM, KurentoRoom.class);
        if (Objects.isNull(kurentoRoom)) {
            throw new ChatForYouException(ErrorCode.ROOM_NOT_FOUND);
        }

        GameSettingInfo gameSettingInfo = kurentoRoom.getGameSettingInfo();
        List<CatchMindUserDto> catchMindUserList = gameSettingInfo.getGameUserList();
        if (CollectionUtils.isEmpty(catchMindUserList)) {
            throw new ChatForYouException(ErrorCode.INVALID_INPUT_VALUE);
        }

        Optional<CatchMindUserDto> user = catchMindUserList.stream()
                .filter(u -> {
                    return u.getUserId().equals(userId);
                }).findFirst();

        if (user.isEmpty()) {
            throw new ChatForYouException(ErrorCode.USER_NOT_FOUND);
        }

        CatchMindUserDto catchMindUser = user.get();
        switch (gameStatus) {
            case TIMEOUT: // 타이머 만료 시 출제자 자동 승리
                if (!gameSettingInfo.getCurrentGameLeader().equals(catchMindUser.getUserId())) {
                    throw new ChatForYouException(ErrorCode.ACCESS_DENIED);
                }
                return withWinnerLock(roomId, () -> resolveTimeoutWinner(roomId, catchMindUser.getUserId(),
                        gameSettingInfo.getCurrentGameRound()));
            case MORE_TIME:
                updateUserScore(catchMindUser, this.MORE_TIME_SCORE);
                redisService.updateChatRoom(kurentoRoom);
                break;
            case TOO_MANY_FAIL:
                updateUserScore(catchMindUser, this.TOO_MANY_FAIL_SCORE);
                redisService.updateChatRoom(kurentoRoom);
                break;
            default:
                throw new ChatForYouException(ErrorCode.INVALID_INPUT_VALUE);
        }
        return catchMindUser;
    }

    /**
     * 최종 게임 결과를 계산해 반환한다.
     *
     * @param roomId 채팅방 ID
     * @return 최종 게임 결과
     */
    @Override
    public GameResultResponse getGameResult(String roomId) {
        KurentoRoom kurentoRoom = redisService.getRedisDataByDataType(roomId, DataType.CHATROOM, KurentoRoom.class);

        // 게임 라운드 확인 및 결과 보내주기
        GameSettingInfo gameSettingInfo = kurentoRoom.getGameSettingInfo();
        if (CollectionUtils.isEmpty(gameSettingInfo.getGameUserList())) {
            throw new ChatForYouException(ErrorCode.INVALID_INPUT_VALUE);
        }

        // 현재 라운드 승리자가 아직 확정되지 않았거나, 마지막 라운드 종료 전이면
        // 프론트는 서버 authoritative state 로 재동기화해야 한다.
        if (!gameSettingInfo.hasCurrentRoundWinner() || !gameSettingInfo.isLastRound()) {
            return GameResultResponse.syncNeeded(gameSettingInfo);
        }

        // score 비교 로직 수행
        // score 와 wincount 에 가산해서 비교
        gameSettingInfo.getGameUserList().sort((u1, u2) -> {
            int score1 = u1.getScore() + u1.getWinCount() * 100;
            int score2 = u2.getScore() + u2.getWinCount() * 100;
            return Integer.compare(score2, score1); // 내림차순 정렬
        });

        gameSettingInfo.getGameUserList().get(0).setWinner(true);
        gameSettingInfo.setAlreadyPlayedGame(true);
        redisService.updateChatRoom(kurentoRoom);
        winnerProcessMap.remove(roomId);
        return GameResultResponse.completed(gameSettingInfo);
    }

    /**
     * 제출된 정답 후보를 검증하고 필요 시 승리 처리를 진행한다.
     *
     * @param answerReq 정답 요청 정보
     * @return 정답 판별 결과
     */
    @Override
    public AnswerResp checkAnswer(AnswerReq answerReq) {
        String roomId = answerReq.getRoomId();
        String userId = answerReq.getUserId();

        KurentoRoom kurentoRoom = redisService.getRedisDataByDataType(roomId, DataType.CHATROOM, KurentoRoom.class);
        if (Objects.isNull(kurentoRoom)) {
            throw new ChatForYouException(ErrorCode.ROOM_NOT_FOUND);
        }

        GameSettingInfo gameSettingInfo = kurentoRoom.getGameSettingInfo();
        List<CatchMindUserDto> catchMindUserList = gameSettingInfo.getGameUserList();
        if (CollectionUtils.isEmpty(catchMindUserList)) {
            throw new ChatForYouException(ErrorCode.INVALID_INPUT_VALUE);
        }

        // 리더 본인은 정답 제출 불가
        if (userId.equals(gameSettingInfo.getCurrentGameLeader())) {
            throw new ChatForYouException(ErrorCode.ACCESS_DENIED);
        }

        Optional<CatchMindUserDto> user = catchMindUserList.stream()
                .filter(u -> {
                    return u.getUserId().equals(userId);
                }).findFirst();

        if (user.isEmpty()) {
            throw new ChatForYouException(ErrorCode.USER_NOT_FOUND);
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
            CatchMindUserDto catchMindUser = user.get();
            CatchMindUserDto resolvedWinner = withWinnerLock(roomId, () -> declareWinner(
                    roomId,
                    catchMindUser.getUserId(),
                    gameSettingInfo.getCurrentGameRound(),
                    gameSettingInfo.getCurrentGameSubject()
            ));
            return AnswerResp.ofCorrect(resolvedWinner, isCorrect);
        }
    }

    /**
     * 동일 라운드에서 승리 처리 로직이 중복 실행되지 않도록 락을 건다.
     *
     * @param roomId 채팅방 ID
     * @param action 승리 처리 로직
     */
    private <T> T withWinnerLock(String roomId, Supplier<T> action) {
        AtomicBoolean isProcess = winnerProcessMap.computeIfAbsent(roomId, key -> new AtomicBoolean(false));
        if (!isProcess.compareAndSet(false, true)) {
            throw new ChatForYouException(ErrorCode.INVALID_INPUT_VALUE);
        }
        try {
            return action.get();
        } finally {
            isProcess.compareAndSet(true, false);
        }
    }

    private CatchMindUserDto resolveTimeoutWinner(String roomId, String leaderId, int expectedRound) {
        KurentoRoom latestKurentoRoom = getChatRoomOrThrow(roomId);
        GameSettingInfo latestGameSettingInfo = latestKurentoRoom.getGameSettingInfo();

        if (latestGameSettingInfo.hasCurrentRoundWinner()) {
            CatchMindUserDto resolvedWinner = findUserById(latestGameSettingInfo.getGameUserList(), latestGameSettingInfo.getCurrentRoundWinnerId());
            log.info("Ignoring timeout for resolved round: roomId={}, round={}, winnerId={}",
                    latestKurentoRoom.getRoomId(), latestGameSettingInfo.getCurrentGameRound(), resolvedWinner.getUserId());
            return resolvedWinner;
        }

        if (latestGameSettingInfo.getCurrentGameRound() != expectedRound
                || !leaderId.equals(latestGameSettingInfo.getCurrentGameLeader())) {
            throw new ChatForYouException(ErrorCode.INVALID_INPUT_VALUE);
        }

        return declareWinner(latestKurentoRoom, latestGameSettingInfo, leaderId);
    }

    private CatchMindUserDto declareWinner(String roomId, String winnerId, int expectedRound, String expectedSubject) {
        KurentoRoom latestKurentoRoom = getChatRoomOrThrow(roomId);
        GameSettingInfo latestGameSettingInfo = latestKurentoRoom.getGameSettingInfo();

        if (!latestGameSettingInfo.hasCurrentRoundWinner()
                && (latestGameSettingInfo.getCurrentGameRound() != expectedRound
                || !Objects.equals(latestGameSettingInfo.getCurrentGameSubject(), expectedSubject))) {
            throw new ChatForYouException(ErrorCode.INVALID_INPUT_VALUE);
        }

        return declareWinner(latestKurentoRoom, latestGameSettingInfo, winnerId);
    }

    private CatchMindUserDto declareWinner(KurentoRoom kurentoRoom, GameSettingInfo gameSettingInfo, String winnerId) {
        if (gameSettingInfo.hasCurrentRoundWinner()) {
            return findUserById(gameSettingInfo.getGameUserList(), gameSettingInfo.getCurrentRoundWinnerId());
        }

        CatchMindUserDto winner = findUserById(gameSettingInfo.getGameUserList(), winnerId);
        gameSettingInfo.setCurrentRoundWinnerId(winner.getUserId());
        updateUserScore(winner, this.WINNER_SCORE);
        winner.setWinCount(winner.getWinCount() + 1);
        // 마지막 라운드가 아닐 때만 라운드 증가
        if (!gameSettingInfo.isLastRound()) {
            gameSettingInfo.newGameRound();
        }
        redisService.updateChatRoom(kurentoRoom);
        return winner;
    }

    private KurentoRoom getChatRoomOrThrow(String roomId) {
        KurentoRoom kurentoRoom = redisService.getRedisDataByDataType(roomId, DataType.CHATROOM, KurentoRoom.class);
        if (Objects.isNull(kurentoRoom)) {
            throw new ChatForYouException(ErrorCode.ROOM_NOT_FOUND);
        }
        return kurentoRoom;
    }

    private CatchMindUserDto findUserById(List<CatchMindUserDto> users, String userId) {
        return users.stream()
                .filter(user -> user.getUserId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new ChatForYouException(ErrorCode.USER_NOT_FOUND));
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
