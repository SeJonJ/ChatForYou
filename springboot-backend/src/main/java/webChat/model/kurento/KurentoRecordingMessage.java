package webChat.model.kurento;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import webChat.utils.JsonUtils;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class KurentoRecordingMessage extends KurentoMessage {
    private String recordingId;
    private long startAt;

    public static KurentoRecordingMessage of(String jsonStr){
        return JsonUtils.jsonToObj(jsonStr, KurentoRecordingMessage.class);
    }
}
