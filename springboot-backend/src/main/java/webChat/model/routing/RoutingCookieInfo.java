package webChat.model.routing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import static webChat.model.kafka.ServerEvent.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@ToString
public class RoutingCookieInfo {

    /**
     * 쿠키를 요청한 인스턴스 ID
     */
    private String requesterId;

    /**
     * 실제 쿠키 값
     */
    private String cookie;

    /**
     * 쿠키 응답을 보낸 인스턴스 ID
     */
    private String responseFrom;

    /**
     * 쿠키 이벤트 타입별 추가 정보
     */
    private String description;

    /**
     * 쿠키 요청 이벤트용 팩토리 메서드
     * requestId 에 해당하는 쿠키 요청
     */
    public static RoutingCookieInfo forRequest(String requesterId) {
        return RoutingCookieInfo.builder()
                .requesterId(requesterId)
                .description(SERVER_COOKIE_REQUEST.name())
                .build();
    }

    /**
     * 쿠키 응답 이벤트용 팩토리 메서드
     * responseFrom 가 requestId 에 해당하는 쿠키 응답
     */
    public static RoutingCookieInfo forResponse(String requesterId, String cookie, String responseFrom) {
        return RoutingCookieInfo.builder()
                .requesterId(requesterId)
                .cookie(cookie)
                .responseFrom(responseFrom)
                .description(SERVER_COOKIE_RESPONSE.name())
                .build();
    }

    /**
     * 쿠키 발견 이벤트용 팩토리 메서드
     */
    public static RoutingCookieInfo forDiscovery(String cookie) {
        return RoutingCookieInfo.builder()
                .cookie(cookie)
                .description(SERVER_COOKIE_DISCOVERED.name())
                .build();
    }

    /**
     * 유효성 검사
     */
    public boolean isValidForRequest() {
        return requesterId != null && !requesterId.trim().isEmpty();
    }

    public boolean isValidForResponse() {
        return requesterId != null && !requesterId.trim().isEmpty()
                && cookie != null && !cookie.trim().isEmpty()
                && responseFrom != null && !responseFrom.trim().isEmpty();
    }

    public boolean isValidForDiscovery() {
        return cookie != null && !cookie.trim().isEmpty();
    }
}