package webChat.model.kafka;

import lombok.Getter;

@Getter
public enum ServerEvent {
    SERVER_DISCOVERY_REQUEST, // 서버 확인 요청
    SERVER_DISCOVERY_RESPONSE, // 서버 확인 승인
    SERVER_STARTED, // 서버 시작
    SERVER_STOPPED, // 서버 중지
    SERVER_COOKIE_REQUEST, // 쿠키 발견 알림
    SERVER_COOKIE_RESPONSE, // 쿠키 정보 요청
    SERVER_COOKIE_DISCOVERED // 쿠키 정보 응답
    ;
}
