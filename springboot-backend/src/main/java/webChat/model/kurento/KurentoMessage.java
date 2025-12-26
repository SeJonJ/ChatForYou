package webChat.model.kurento;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class KurentoMessage {
    private KurentoEvent event;
    private String roomId;
    private String senderId;
    private String senderNickName;
}
