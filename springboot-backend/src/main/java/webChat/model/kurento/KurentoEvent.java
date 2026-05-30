package webChat.model.kurento;

import lombok.Getter;

@Getter
public enum KurentoEvent {
    JOIN_ROOM,
    LEAVE_ROOM,
    RECEIVE_VIDEO_FROM,
    ON_ICE_CANDIDATE,
    TEXT_OVERLAY,
    RECORDING_START,
    RECORDING_STOP,
    PARTICIPANT_RECEIVE_FAILED,
}
