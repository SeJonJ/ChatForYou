package webChat.model.game;

public enum GameStatus {
    WINNER,
    MORE_TIME,
    TIMEOUT, // 타이머 만료 시 출제자 자동 승리
    TOO_MANY_FAIL, // 너무 많이 시도해서 점수가 깍이는 경우
    TOO_MANY_CLEAR // max canvas clear 횟수 이후 추가로 clear 를 시도하려고 하는 경우
}