package webChat.model.game;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class GameSettingInfo {
    String roomId; // 방 아이디
    int totalGameRound; // 총 게임 라운드
    int currentGameRound; // 현재 게임 라운드
    String currentGameLeader; // 현재 게임 진행자
    String currentGameSubject; // 현재 게임 주제(정답)
    Map<String, List<String>> beforeSubjects; // 이전 게임 주제
    List<CatchMindUserDto> gameUserList; // 참여하는 유저
    boolean alreadyPlayedGame;

    public void newGameRound(){
        this.currentGameRound +=1;
    }
}
