package webChat.service.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.config.CatchMindConfig;
import webChat.model.game.AnswerReq;
import webChat.model.game.AnswerResp;
import webChat.model.game.CatchMindUserDto;
import webChat.model.game.GameStatus;
import webChat.model.game.GameSettingInfo;
import webChat.model.redis.DataType;
import webChat.model.response.game.GameResultResponse;
import webChat.model.room.KurentoRoom;
import webChat.service.game.impl.CatchMindServiceImpl;
import webChat.service.redis.RedisService;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatchMindServiceImplTest {

    @Mock
    private CatchMindConfig catchMindConfig;

    @Mock
    private RedisService redisService;

    @Mock
    private GameUtilService gameUtilService;

    @InjectMocks
    private CatchMindServiceImpl catchMindService;

    @Test
    @DisplayName("최종 라운드 이전이면 예외 대신 동기화 DTO를 반환한다")
    void getGameResult_whenSyncNeeded_returnsNormalSyncDto() {
        // given
        String roomId = "room-1";
        GameSettingInfo gameSettingInfo = createGameSettingInfo(roomId, 2, 3);
        KurentoRoom kurentoRoom = new KurentoRoom();
        kurentoRoom.setGameSettingInfo(gameSettingInfo);

        when(redisService.getRedisDataByDataType(eq(roomId), eq(DataType.CHATROOM), eq(KurentoRoom.class)))
                .thenReturn(kurentoRoom);

        // when
        GameResultResponse response = catchMindService.getGameResult(roomId);

        // then
        assertThat(response.isSyncNeeded()).isTrue();
        assertThat(response.isGameCompleted()).isFalse();
        assertThat(response.getCurrentGameRound()).isEqualTo(2);
        assertThat(response.getTotalGameRound()).isEqualTo(3);
        assertThat(response.getCurrentGameLeader()).isEqualTo("leader");
        assertThat(response.getCurrentRoundWinnerId()).isNull();
        assertThat(response.getGameResult()).isNull();
    }

    @Test
    @DisplayName("최종 라운드라도 현재 승리자가 없으면 authoritative state 로 재동기화 정보를 반환한다")
    void getGameResult_whenFinalRoundWinnerNotResolved_returnsSyncState() {
        // given
        String roomId = "room-1";
        GameSettingInfo gameSettingInfo = createGameSettingInfo(roomId, 3, 3);
        KurentoRoom kurentoRoom = new KurentoRoom();
        kurentoRoom.setGameSettingInfo(gameSettingInfo);

        given(redisService.getRedisDataByDataType(eq(roomId), eq(DataType.CHATROOM), eq(KurentoRoom.class)))
                .willReturn(kurentoRoom);

        // when
        GameResultResponse response = catchMindService.getGameResult(roomId);

        // then
        assertThat(response.isSyncNeeded()).isTrue();
        assertThat(response.isGameCompleted()).isFalse();
        assertThat(response.getCurrentGameRound()).isEqualTo(3);
        assertThat(response.getTotalGameRound()).isEqualTo(3);
        assertThat(response.getCurrentGameLeader()).isEqualTo("leader");
        assertThat(response.getCurrentRoundWinnerId()).isNull();
        assertThat(response.getGameResult()).isNull();
        verify(redisService, never()).updateChatRoom(kurentoRoom);
    }

    @Test
    @DisplayName("최종 라운드에서 승리자가 확정되면 정렬된 게임 결과 DTO를 반환하고 방 정보를 갱신한다")
    void getGameResult_whenFinalRound_returnsCompletedDto() {
        // given
        String roomId = "room-1";
        GameSettingInfo gameSettingInfo = createGameSettingInfo(roomId, 3, 3);
        CatchMindUserDto first = gameSettingInfo.getGameUserList().get(0);
        CatchMindUserDto second = gameSettingInfo.getGameUserList().get(1);
        first.setScore(100);
        second.setScore(20);
        gameSettingInfo.setCurrentRoundWinnerId(first.getUserId());

        KurentoRoom kurentoRoom = new KurentoRoom();
        kurentoRoom.setGameSettingInfo(gameSettingInfo);

        when(redisService.getRedisDataByDataType(eq(roomId), eq(DataType.CHATROOM), eq(KurentoRoom.class)))
                .thenReturn(kurentoRoom);

        // when
        GameResultResponse response = catchMindService.getGameResult(roomId);

        // then
        assertThat(response.isSyncNeeded()).isFalse();
        assertThat(response.isGameCompleted()).isTrue();
        assertThat(response.getCurrentGameRound()).isEqualTo(3);
        assertThat(response.getTotalGameRound()).isEqualTo(3);
        assertThat(response.getCurrentGameLeader()).isEqualTo("leader");
        assertThat(response.getCurrentRoundWinnerId()).isEqualTo(first.getUserId());
        assertThat(response.getGameResult()).isSameAs(gameSettingInfo);
        assertThat(response.getGameResult().isAlreadyPlayedGame()).isTrue();
        assertThat(response.getGameResult().getGameUserList().get(0).isWinner()).isTrue();
        verify(redisService).updateChatRoom(kurentoRoom);
    }

    @Test
    @DisplayName("updateUser - stale snapshot 이후 들어온 timeout 은 redis 최신 승리자를 유지한다")
    void updateUser_whenTimeoutArrivesAfterAnswerOnLastRound_preservesStoredWinner() {
        // given
        String roomId = "room-1";
        KurentoRoom staleKurentoRoom = createKurentoRoom(roomId, 3, 3);
        CatchMindUserDto staleLeader = staleKurentoRoom.getGameSettingInfo().getGameUserList().get(0);

        KurentoRoom latestKurentoRoom = createKurentoRoom(roomId, 3, 3);
        CatchMindUserDto latestLeader = latestKurentoRoom.getGameSettingInfo().getGameUserList().get(0);
        CatchMindUserDto latestParticipant = latestKurentoRoom.getGameSettingInfo().getGameUserList().get(1);
        latestParticipant.setScore(100);
        latestParticipant.setWinCount(1);
        latestKurentoRoom.getGameSettingInfo().setCurrentRoundWinnerId(latestParticipant.getUserId());

        given(redisService.getRedisDataByDataType(eq(roomId), eq(DataType.CHATROOM), eq(KurentoRoom.class)))
                .willReturn(staleKurentoRoom, latestKurentoRoom);

        // when
        CatchMindUserDto result = catchMindService.updateUser(GameStatus.TIMEOUT, roomId, staleLeader.getUserId());

        // then
        assertThat(result).isSameAs(latestParticipant);
        assertThat(staleLeader.getScore()).isZero();
        assertThat(staleLeader.getWinCount()).isZero();
        assertThat(latestLeader.getScore()).isZero();
        assertThat(latestLeader.getWinCount()).isZero();
        assertThat(latestParticipant.getScore()).isEqualTo(100);
        assertThat(latestParticipant.getWinCount()).isEqualTo(1);
        verify(redisService, never()).updateChatRoom(latestKurentoRoom);
    }

    @Test
    @DisplayName("updateUser - timeout 처리 시 최신 라운드와 리더가 바뀌었으면 재동기화가 필요하다고 실패한다")
    void updateUser_whenTimeoutTargetsStaleRound_throwsInvalidInputValue() {
        // given
        String roomId = "room-1";
        KurentoRoom staleKurentoRoom = createKurentoRoom(roomId, 2, 3);
        CatchMindUserDto staleLeader = staleKurentoRoom.getGameSettingInfo().getGameUserList().get(0);

        KurentoRoom latestKurentoRoom = createKurentoRoom(roomId, 3, 3);
        latestKurentoRoom.getGameSettingInfo().setCurrentGameLeader("next-leader");

        given(redisService.getRedisDataByDataType(eq(roomId), eq(DataType.CHATROOM), eq(KurentoRoom.class)))
                .willReturn(staleKurentoRoom, latestKurentoRoom);

        // when & then
        assertThatThrownBy(() -> catchMindService.updateUser(GameStatus.TIMEOUT, roomId, staleLeader.getUserId()))
                .isInstanceOf(ChatForYouException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    @DisplayName("checkAnswer - stale snapshot 이후 들어온 늦은 정답은 redis 최신 승리자를 반환한다")
    void checkAnswer_whenLateCorrectAnswerArrivesAfterWinnerResolved_returnsStoredWinner() {
        // given
        String roomId = "room-1";
        KurentoRoom staleKurentoRoom = createKurentoRoom(roomId, 3, 3);
        CatchMindUserDto lateParticipant = createUser("participant-2", "participant-2");
        staleKurentoRoom.getGameSettingInfo().getGameUserList().add(lateParticipant);

        KurentoRoom latestKurentoRoom = createKurentoRoom(roomId, 3, 3);
        CatchMindUserDto latestParticipant = latestKurentoRoom.getGameSettingInfo().getGameUserList().get(1);
        latestParticipant.setScore(100);
        latestParticipant.setWinCount(1);
        latestKurentoRoom.getGameSettingInfo().setCurrentRoundWinnerId(latestParticipant.getUserId());
        latestKurentoRoom.getGameSettingInfo().getGameUserList().add(createUser("participant-2", "participant-2"));

        given(redisService.getRedisDataByDataType(eq(roomId), eq(DataType.CHATROOM), eq(KurentoRoom.class)))
                .willReturn(staleKurentoRoom, latestKurentoRoom);
        given(gameUtilService.matchAnswer(eq(staleKurentoRoom.getGameSettingInfo().getCurrentGameSubject()), eq("subject")))
                .willReturn(true);

        AnswerReq request = new AnswerReq(roomId, lateParticipant.getUserId(), List.of("subject"));

        // when
        AnswerResp response = catchMindService.checkAnswer(request);

        // then
        assertThat(response.isCorrect()).isTrue();
        assertThat(response.getCatchMindUser()).isSameAs(latestParticipant);
        assertThat(lateParticipant.getScore()).isZero();
        assertThat(lateParticipant.getWinCount()).isZero();
        verify(redisService, never()).updateChatRoom(latestKurentoRoom);
    }

    @Test
    @DisplayName("checkAnswer - 검증 후 최신 라운드 주제가 바뀌었으면 다음 라운드를 임의로 확정하지 않고 실패한다")
    void checkAnswer_whenLatestRoundStateChanged_throwsInvalidInputValue() {
        // given
        String roomId = "room-1";
        KurentoRoom staleKurentoRoom = createKurentoRoom(roomId, 2, 3);
        CatchMindUserDto participant = staleKurentoRoom.getGameSettingInfo().getGameUserList().get(1);

        KurentoRoom latestKurentoRoom = createKurentoRoom(roomId, 3, 3);
        latestKurentoRoom.getGameSettingInfo().setCurrentGameSubject("new-subject");

        given(redisService.getRedisDataByDataType(eq(roomId), eq(DataType.CHATROOM), eq(KurentoRoom.class)))
                .willReturn(staleKurentoRoom, latestKurentoRoom);
        given(gameUtilService.matchAnswer(eq(staleKurentoRoom.getGameSettingInfo().getCurrentGameSubject()), eq("subject")))
                .willReturn(true);

        AnswerReq request = new AnswerReq(roomId, participant.getUserId(), List.of("subject"));

        // when & then
        assertThatThrownBy(() -> catchMindService.checkAnswer(request))
                .isInstanceOf(ChatForYouException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
    }

    private KurentoRoom createKurentoRoom(String roomId, int currentGameRound, int totalGameRound) {
        KurentoRoom kurentoRoom = new KurentoRoom();
        kurentoRoom.setRoomId(roomId);
        kurentoRoom.setGameSettingInfo(createGameSettingInfo(roomId, currentGameRound, totalGameRound));
        return kurentoRoom;
    }

    private GameSettingInfo createGameSettingInfo(String roomId, int currentGameRound, int totalGameRound) {
        GameSettingInfo gameSettingInfo = new GameSettingInfo();
        gameSettingInfo.setRoomId(roomId);
        gameSettingInfo.setCurrentGameRound(currentGameRound);
        gameSettingInfo.setTotalGameRound(totalGameRound);
        gameSettingInfo.setCurrentGameLeader("leader");
        gameSettingInfo.setCurrentGameSubject("subject");

        CatchMindUserDto user1 = createUser("leader", "leader");
        CatchMindUserDto user2 = createUser("participant", "participant");

        gameSettingInfo.setGameUserList(new ArrayList<>(List.of(user1, user2)));
        return gameSettingInfo;
    }

    private CatchMindUserDto createUser(String userId, String nickName) {
        CatchMindUserDto user = new CatchMindUserDto();
        ReflectionTestUtils.setField(user, "userId", userId);
        ReflectionTestUtils.setField(user, "nickName", nickName);
        user.setScore(0);
        user.setWinCount(0);
        return user;
    }
}
