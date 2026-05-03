package webChat.service.routing.fixture;

import webChat.model.kafka.KafkaServerEvent;

public final class CookieCheckEventFixture {

    public static final String LOCAL_INSTANCE_ID = "instance-local";
    public static final String PEER_INSTANCE_ID = "peer-instance";
    public static final String PEER_COOKIE = "peer-cookie|value";

    private CookieCheckEventFixture() {
    }

    public static String localCookie(String instanceId) {
        return "local_cookie|" + instanceId;
    }

    public static KafkaServerEvent cookieResponseForLocalRequester(String requesterId) {
        return KafkaServerEvent.createCookieResponse(PEER_INSTANCE_ID, requesterId, PEER_COOKIE);
    }

    public static KafkaServerEvent cookieDiscoveredFromPeer() {
        return KafkaServerEvent.createCookieDiscovered(PEER_INSTANCE_ID, PEER_COOKIE);
    }
}
