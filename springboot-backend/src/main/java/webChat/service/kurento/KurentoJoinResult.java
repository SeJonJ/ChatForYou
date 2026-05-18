package webChat.service.kurento;

/**
 * Kurento 입장 처리 결과.
 *
 * participant 는 최종적으로 방에 등록된 세션이며,
 * replacedExistingParticipant 가 true 면 동일 userId의 기존 세션을 교체한 join 이다.
 */
public record KurentoJoinResult(
        KurentoUserSession participant,
        boolean replacedExistingParticipant
) {
}
