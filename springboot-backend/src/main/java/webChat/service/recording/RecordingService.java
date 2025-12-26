package webChat.service.recording;

import webChat.model.room.KurentoRoom;
import webChat.service.kurento.KurentoUserSession;

import java.io.IOException;

public interface RecordingService {
    String startRecording(KurentoRoom room, KurentoUserSession requestUser) throws IOException;
    void stopRecording(KurentoRoom room, KurentoUserSession requestUser) throws IOException;
}
