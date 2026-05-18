package webChat.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.model.game.*;
import webChat.model.response.common.ChatForYouResponse;
import webChat.service.game.CatchMindService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/chatforyou/api/catchmind")
@Slf4j
public class CatchMindController {

    private final CatchMindService catchMindService;

    /**
     * CatchMind 대주제 목록을 조회한다.
     *
     * @param roomId 채팅방 ID
     * @return 게임 대주제 목록
     */
    @GetMapping(value = "/titles", produces = "application/json; charset=UTF-8")
    public ResponseEntity<ChatForYouResponse> getGameTitles(@RequestParam("roomId") String roomId) {
        log.info("CatchMind 제목 조회 요청: roomId={}", roomId);
        if (catchMindService.chkAlreadyPlayedGame(roomId)) {
            throw new ChatForYouException(ErrorCode.GAME_ALREADY_PLAYED);
        }
        return ResponseEntity.ok(ChatForYouResponse.ofSuccess(catchMindService.getTitles()));
    }

    /**
     * 선택한 대주제에 맞는 소주제 목록을 조회한다.
     *
     * @param roomId 채팅방 ID
     * @param gameSubjects 선택한 대주제 정보
     * @return 게임 소주제 목록
     */
    @PostMapping(value = "/subjects", produces = "application/json; charset=UTF-8")
    public ResponseEntity<ChatForYouResponse> getGameSubjects(@RequestParam("roomId") String roomId, @Valid @RequestBody GameSubjects gameSubjects) {
        log.info("CatchMind 주제 조회 요청: roomId={}", roomId);
        gameSubjects = catchMindService.getSubjects(roomId, gameSubjects);
        return ResponseEntity.ok(ChatForYouResponse.ofSuccess(gameSubjects));
    }

    /**
     * 게임 시작 전 서버 기준 라운드 정보를 저장한다.
     *
     * @param gameSettingInfo 게임 설정 정보
     * @return 저장된 게임 설정 정보
     */
    @PostMapping(value = "/game_setting", produces = "application/json; charset=UTF-8")
    public ResponseEntity<ChatForYouResponse> initGameEnv(
            @Valid @RequestBody GameSettingInfo gameSettingInfo) {
        catchMindService.setGameSettingInfo(gameSettingInfo);
        log.info("CatchMind 게임 설정 완료: roomId={}", gameSettingInfo.getRoomId());
        return ResponseEntity.ok(ChatForYouResponse.ofSuccess(gameSettingInfo));
    }

    /**
     * 제출된 정답 후보를 서버 기준 정답과 비교한다.
     *
     * @param answerReq 정답 확인 요청
     * @return 정답 판별 결과
     */
    @PostMapping(value = "/check_answer", produces = "application/json; charset=UTF-8")
    public ResponseEntity<ChatForYouResponse> checkAnswer(
            @Valid @RequestBody AnswerReq answerReq) {
        AnswerResp resp = catchMindService.checkAnswer(answerReq);
        return ResponseEntity.ok(ChatForYouResponse.ofSuccess(resp));
    }

    /**
     * 게임 진행 중 사용자 상태와 점수를 갱신한다.
     *
     * @param gameStatusRequest 게임 상태 변경 요청
     * @return 갱신된 사용자 정보
     */
    @PostMapping(value = "/update_game_status", produces = "application/json; charset=UTF-8")
    public ResponseEntity<ChatForYouResponse> updateGameStatus(
            @Valid @RequestBody GameStatusRequest gameStatusRequest) {
        CatchMindUserDto catchMindUser = catchMindService.updateUser(gameStatusRequest.getGameStatus(), gameStatusRequest.getRoomId(), gameStatusRequest.getUserId());
        return ResponseEntity.ok(ChatForYouResponse.ofSuccess(catchMindUser));
    }

    /**
     * 최종 게임 결과를 조회한다.
     *
     * @param roomId 채팅방 ID
     * @return 최종 게임 결과
     */
    @GetMapping(value = "/game_result", produces = "application/json; charset=UTF-8")
    public ResponseEntity<ChatForYouResponse> gameResult(@RequestParam("roomId") String roomId) {
        return ResponseEntity.ok(ChatForYouResponse.ofSuccess(catchMindService.getGameResult(roomId)));
    }
}
