package webChat.model.game;

public enum GameStatus {
    // MORE_TIME — 추후 캔버스 초기화 시 점수 가감 기능 예정
    MORE_TIME,
    // TIMEOUT — 활성: 타이머 만료 시 출제자 자동 승리
    TIMEOUT,
    // TOO_MANY_FAIL — 너무 많은 정답 재시도 시 점수 가감 기능 예정
    TOO_MANY_FAIL,
    // TOO_MANY_CLEAR - 너무 많은 캔버스 초기화 시 점수 가감 예정
    TOO_MANY_CLEAR
}