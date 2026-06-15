package webChat.service.chatroom.recovery;

import jakarta.servlet.http.HttpServletResponse;
import webChat.model.room.ChatRoom;
import webChat.model.room.recovery.PreShutdownResult;
import webChat.model.room.recovery.RecoveryDecision;
import webChat.model.room.recovery.RecoveryResult;

public interface ChatRoomRecoveryService {
    /**
     * 입장 시점의 방 상태가 배포 복구 대상인지 판정한다.
     */
    RecoveryDecision evaluateJoinRecovery(ChatRoom chatRoom);

    /**
     * 방 소유권과 라우팅 쿠키를 현재 인스턴스 기준으로 복구한다.
     */
    RecoveryResult recoverRoom(ChatRoom chatRoom, HttpServletResponse response);

    /**
     * 현재 인스턴스가 종료되기 전에 소유 중인 방을 복구 후보로 저장한다.
     */
    PreShutdownResult markOwnedRoomsRecoverable();
}
