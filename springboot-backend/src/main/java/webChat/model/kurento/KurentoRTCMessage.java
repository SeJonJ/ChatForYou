package webChat.model.kurento;

import com.google.gson.JsonObject;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import webChat.utils.JsonUtils;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class KurentoRTCMessage extends KurentoMessage {
    private String sdpOffer;
    private JsonObject candidate;
    // PARTICIPANT_RECEIVE_FAILED 시그널에서 사용하는 필드
    private String targetUserId;
    private String phase;

    public static KurentoRTCMessage of(String jsonStr){
        return JsonUtils.jsonToObj(jsonStr, KurentoRTCMessage.class);
    }
}
