package webChat.model.kafka;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import webChat.model.routing.RoutingCookieInfo;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class KafkaServerEvent extends KafkaEvent {

    private ServerEvent eventType;  // SERVER_STARTED, SERVER_STOPPED, SERVER_COOKIE_REQUEST 등
    private String instanceId;
    private RoutingCookieInfo cookieInfo;  // 쿠키 관련 정보

    /**
     * 기본 서버 이벤트용 팩토리 메서드 (쿠키 정보 없음)
     */
    public static KafkaServerEvent of(ServerEvent eventType, String instanceId, long publishedAt){
        return KafkaServerEvent.builder()
                .eventType(eventType)
                .instanceId(instanceId)
                .publishedAt(publishedAt)
                .build();
    }

    /**
     * 쿠키 정보가 포함된 서버 이벤트용 팩토리 메서드
     */
    public static KafkaServerEvent of(ServerEvent eventType, String instanceId, RoutingCookieInfo cookieInfo, long publishedAt){
        return KafkaServerEvent.builder()
                .eventType(eventType)
                .instanceId(instanceId)
                .cookieInfo(cookieInfo)
                .publishedAt(publishedAt)
                .build();
    }

    /**
     * 쿠키 요청 이벤트 생성
     * '나' 의 쿠키를 요청
     */
    public static KafkaServerEvent createCookieRequest(String instanceId) {
        return KafkaServerEvent.builder()
                .eventType(ServerEvent.SERVER_COOKIE_REQUEST)
                .instanceId(instanceId)
                .cookieInfo(RoutingCookieInfo.forRequest(instanceId))
                .publishedAt(System.currentTimeMillis())
                .build();
    }

    /**
     * 쿠키 응답 이벤트 생성
     */
    public static KafkaServerEvent createCookieResponse(String instanceId, String requesterId, String cookie) {
        return KafkaServerEvent.builder()
                .eventType(ServerEvent.SERVER_COOKIE_RESPONSE)
                .instanceId(instanceId)
                .cookieInfo(RoutingCookieInfo.forResponse(requesterId, cookie, instanceId))
                .publishedAt(System.currentTimeMillis())
                .build();
    }

    /**
     * 쿠키 발견 이벤트 생성
     */
    public static KafkaServerEvent createCookieDiscovered(String instanceId, String cookie) {
        return KafkaServerEvent.builder()
                .eventType(ServerEvent.SERVER_COOKIE_DISCOVERED)
                .instanceId(instanceId)
                .cookieInfo(RoutingCookieInfo.forDiscovery(cookie))
                .publishedAt(System.currentTimeMillis())
                .build();
    }

    /**
     * 쿠키 관련 이벤트인지 확인
     */
    public boolean isCookieEvent() {
        return eventType == ServerEvent.SERVER_COOKIE_REQUEST
                || eventType == ServerEvent.SERVER_COOKIE_RESPONSE
                || eventType == ServerEvent.SERVER_COOKIE_DISCOVERED;
    }

    /**
     * 쿠키 정보 유효성 검사
     */
    public boolean isValidCookieEvent() {
        if (!isCookieEvent() || cookieInfo == null) {
            return false;
        }

        return switch (eventType) {
            case SERVER_COOKIE_REQUEST -> cookieInfo.isValidForRequest();
            case SERVER_COOKIE_RESPONSE -> cookieInfo.isValidForResponse();
            case SERVER_COOKIE_DISCOVERED -> cookieInfo.isValidForDiscovery();
            default -> false;
        };
    }
}