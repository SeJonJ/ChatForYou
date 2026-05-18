package webChat.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import webChat.model.game.GameSettingInfo;
import webChat.model.response.game.GameResultResponse;
import webChat.service.game.CatchMindService;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CatchMindControllerTest {

    @Mock
    private CatchMindService catchMindService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new CatchMindController(catchMindService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("game_result - 라운드 동기화 필요 시 정상 DTO 응답을 반환한다")
    void gameResult_whenSyncNeeded_returnsNormalDto() throws Exception {
        when(catchMindService.getGameResult("room-1"))
                .thenReturn(GameResultResponse.syncNeeded(2));

        mockMvc.perform(get("/chatforyou/api/catchmind/game_result")
                        .param("roomId", "room-1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.syncNeeded").value(true))
                .andExpect(jsonPath("$.data.currentGameRound").value(2))
                .andExpect(jsonPath("$.data.gameResult").doesNotExist());
    }

    @Test
    @DisplayName("game_result - 최종 결과 조회 시 결과 DTO를 success data 로 반환한다")
    void gameResult_whenCompleted_returnsGameResult() throws Exception {
        GameSettingInfo gameSettingInfo = new GameSettingInfo();
        gameSettingInfo.setRoomId("room-1");
        gameSettingInfo.setCurrentGameRound(3);
        gameSettingInfo.setTotalGameRound(3);

        when(catchMindService.getGameResult("room-1"))
                .thenReturn(GameResultResponse.completed(gameSettingInfo));

        mockMvc.perform(get("/chatforyou/api/catchmind/game_result")
                        .param("roomId", "room-1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.syncNeeded").value(false))
                .andExpect(jsonPath("$.data.gameResult.roomId").value("room-1"))
                .andExpect(jsonPath("$.data.gameResult.currentGameRound").value(3));
    }
}
