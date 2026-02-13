package webChat.model.kurento;

import lombok.AllArgsConstructor;
import lombok.Getter;
import webChat.utils.JsonUtils;

@Getter
@AllArgsConstructor
public class KurentoOverlayMessage extends KurentoMessage{
    private String text;

    public static KurentoOverlayMessage of(String jsonStr){
        return JsonUtils.jsonToObj(jsonStr, KurentoOverlayMessage.class);
    }
}
