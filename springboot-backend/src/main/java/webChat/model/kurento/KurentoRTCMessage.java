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

    public static KurentoRTCMessage of(String jsonStr){
        return JsonUtils.jsonToObj(jsonStr, KurentoRTCMessage.class);
    }
}
