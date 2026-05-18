/*
 * Copyright 2023 SejonJang (wkdtpwhs@gmail.com)
 *
 * Licensed under the  GNU General Public License v3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package webChat.service.kurento;

import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kurento.client.*;
import org.kurento.jsonrpc.JsonUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import webChat.model.user.UserDto;
import webChat.repository.kurento.KurentoHubPortMap;
import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Kurento WebRTC 참가자 세션을 표현하는 도메인 객체.
 *
 * 각 참가자는 하나의 WebRtcEndpoint(outgoingMedia)를 소유하며,
 * 다른 참가자로부터 수신하는 스트림은 incomingMedia 맵에 userId 키로 관리된다.
 *
 * 미디어 파이프라인 구조:
 *   outgoingMedia - textOverlayFilter - 각 피어의 incomingMedia
 *                                     - compositeScaler - compositeHubPort (녹화 시)
 *
 * textOverlayFilter를 통해 자막이 P2P 경로와 녹화 경로 모두에 반영된다.
 * compositeScaler(videoscale 필터)는 Composite의 caps renegotiation이 P2P 해상도에
 * 영향을 주지 않도록 두 경로를 격리한다.
 *
 * @author SeJon Jang (wkdtpwhs@gmail.com)
 */
@RequiredArgsConstructor
@Getter
@Slf4j
public class KurentoUserSession extends UserDto implements Closeable {

  private final WebSocketSession session;

  private final MediaPipeline pipeline;

  private final String roomId;

  /**
   * 현재 참가자가 송출하는 WebRtcEndpoint.
   *
   * 자신의 카메라/마이크 스트림을 외부로 내보내는 엔드포인트다.
   * 다른 참가자들은 이 엔드포인트(또는 textOverlayFilter)에 연결하여 스트림을 수신한다.
   */
  private final WebRtcEndpoint outgoingMedia;

  /**
   * 다른 참가자들로부터 수신하는 WebRtcEndpoint 맵.
   *
   * 키는 발신자의 userId이며, 값은 해당 발신자로부터 미디어를 수신하는 엔드포인트다.
   * ConcurrentHashMap으로 다중 스레드 접근을 안전하게 처리한다.
   */
  private final ConcurrentMap<String, WebRtcEndpoint> incomingMedia = new ConcurrentHashMap<>();

  /**
   * 실시간 텍스트 오버레이(자막)를 위한 GStreamer 필터.
   *
   * outgoingMedia 다음에 연결되어 비디오 스트림에 텍스트를 합성한다.
   * P2P 경로와 녹화 경로 모두 이 필터를 통과하므로 자막이 동시에 반영된다.
   */
  private GStreamerFilter textOverlayFilter;

  /**
   * Composite 녹화를 위한 HubPort.
   *
   * null이면 Composite에 연결되지 않은 상태(isConnectedToComposite() == false)임을 의미한다.
   */
  private HubPort compositeHubPort;

  /**
   * Composite 연결 시 P2P 경로와 녹화 경로를 해상도 차원에서 격리하는 videoscale 필터.
   *
   * textOverlayFilter - compositeScaler - compositeHubPort 구조로 연결하여
   * Composite의 caps renegotiation이 P2P 경로의 해상도에 영향을 주지 않도록 한다.
   */
  private GStreamerFilter compositeScaler;

  /**
   * 녹화 자동 중단 등 시스템 내부 처리를 위한 더미 세션 생성자.
   *
   * WebSocket 세션과 MediaPipeline이 없는 경량 세션이며,
   * 미디어 연산이 수행되지 않는 컨텍스트(예: 녹화 종료 이벤트 처리)에서만 사용한다.
   * WS session, pipeline, outgoingMedia는 모두 null로 초기화된다.
   *
   * @param userId   사용자 ID
   * @param nickName 사용자 닉네임
   * @param roomId   방 ID
   */
  public KurentoUserSession(String userId, String nickName, String roomId) {
    super(userId, nickName);
    this.roomId = roomId;
    this.pipeline = null;
    this.session = null;
    this.outgoingMedia = null;
  }

  /**
   * 실제 WebRTC 미디어 세션을 초기화하는 메인 생성자.
   *
   * 생성 순서: outgoingMedia WebRtcEndpoint 생성(데이터 채널 포함) 후 textOverlayFilter를
   * 생성하여 outgoingMedia에 연결한다. 마지막으로 outgoingMedia에 IceCandidateFound 이벤트
   * 리스너를 등록하여 클라이언트에 ICE 후보를 전달한다.
   *
   * @param userId   사용자 ID
   * @param nickName 사용자 닉네임
   * @param roomId   방 ID
   * @param session  WebSocket 세션
   * @param pipeline 방 공유 MediaPipeline
   */
  public KurentoUserSession(String userId, String nickName, String roomId, WebSocketSession session,
      MediaPipeline pipeline) {

    super(userId, nickName);
    this.pipeline = pipeline;
    this.session = session;
    this.roomId = roomId;

    // 외부로 송신하는 미디어?
    this.outgoingMedia = new WebRtcEndpoint.Builder(pipeline)
        .useDataChannels()
        .build();

    // 텍스트 오버레이 필터 생성 (한글 지원 폰트 fallback 체인)
    // 실시간 비디오 스트림에 자막을 표시하기 위해 outgoingMedia에서 직접 연결
    this.textOverlayFilter = new GStreamerFilter.Builder(pipeline,
        "textoverlay text='' font-desc='Noto Sans CJK KR Bold 24' halignment=center valignment=top deltay=50")
        .build();

    log.debug("TextOverlay filter created for user: {}", userId);

    // 기본 미디어 파이프라인 설정: outgoingMedia → textOverlayFilter
    // textOverlayFilter에서 직접 다른 참여자들과 연결
    try {
      this.outgoingMedia.connect(this.textOverlayFilter);
      log.debug("Connected outgoingMedia → TextOverlay for user: {}", userId);
    } catch (Exception e) {
      log.error("Failed to setup media pipeline for user {}: {}", userId, e.getMessage());
    }

    // iceCandidateFounder 이벤트 리스너 등록
    // 이벤트가 발생했을 때 다른 유저들에게 새로운 iceCnadidate 후보를 알림
    this.outgoingMedia.addIceCandidateFoundListener(event -> {
      // JsonObject 생성
      JsonObject response = new JsonObject();
      // id : iceCnadidate, id 는 ice후보자 선정
      response.addProperty("id", "iceCandidate");
      // name : 유저명
      response.addProperty("name", userId);
      response.addProperty("nickName", nickName);

      // add 랑 addProperty 랑 차이점?
      // candidate 를 key 로 하고, IceCandidateFoundEvent 객체를 JsonUtils 를 이용해
      // json 형태로 변환시킨다 => toJsonObject 는 넘겨받은 Object 객체를 JsonObject 로 변환
      response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));

      try {
        /** synchronized 안에는 동기화 필요한 부분 지정 */
        // 먼저 동기화는 프로세스(스레드)가 수행되는 시점을 조절하여 서로가 알고 있는 정보가 일치하는 것
        // 여기서는 쉽게 말해 onEvent 를 통해서 넘어오는 모든 session 객체에게 앞에서 생성한 response json 을
        // 넘겨주게되고 이를 통해서 iceCandidate 상태를 '일치' 시킨다? ==> 여긴 잘 모르겟어요...
        synchronized (session) {
          session.sendMessage(new TextMessage(response.toString()));
        }
      } catch (IOException e) {
        log.debug(e.getMessage());
      }
    });
  }

  /**
   * 발신자(sender)로부터 비디오를 수신하기 위한 SDP 협상을 시작한다.
   *
   * 처리 순서: getEndpointForUser를 통해 발신자 전용 수신 엔드포인트를 가져오거나 생성한다.
   * SDP offer를 제출하여 SDP answer를 획득한 후 receiveVideoAnswer 메시지로 클라이언트에 전달하고,
   * gatherCandidates()를 호출하여 ICE 후보 수집을 트리거한다.
   *
   * @param sender   스트림을 송출하는 참가자 세션
   * @param sdpOffer 클라이언트에서 전달된 SDP offer 문자열
   * @throws IOException WebSocket 메시지 전송 실패 시
   */
  public void receiveVideoFrom(KurentoUserSession sender, String sdpOffer) throws IOException {
    // 유저가 room 에 들어왓음을 알림
    log.info("USER {}: connecting with {} in room {}", this.getUserId(), sender.getUserId(), this.roomId);

    // 들어온 유저가 Sdp 제안
    log.trace("USER {}: SdpOffer for {} is {}", this.getUserId(), sender.getUserId(), sdpOffer);

    /**
     *
     * @Desc sdpOffer 에 대한 결과 String
     */
    final String ipSdpAnswer = this.getEndpointForUser(sender).processOffer(sdpOffer);

    final JsonObject scParams = new JsonObject();
    scParams.addProperty("id", "receiveVideoAnswer");
    scParams.addProperty("name", sender.getUserId());
    scParams.addProperty("nickName", sender.getNickName());
    scParams.addProperty("sdpAnswer", ipSdpAnswer);

    log.trace("USER {}: SdpAnswer for {} is {}", this.getUserId(), sender.getUserId(), ipSdpAnswer);
    this.sendMessage(scParams);
    log.debug("gather candidates");
    this.getEndpointForUser(sender).gatherCandidates();
  }

  /**
   * 발신자에 대한 수신 엔드포인트를 반환한다.
   *
   * 발신자가 자신(loopback)이면 별도 엔드포인트를 생성하지 않고 outgoingMedia를 반환한다.
   * 발신자가 다른 참가자이면 incomingMedia 맵에서 엔드포인트를 조회하고, 없으면 신규
   * WebRtcEndpoint를 생성하여 발신자의 textOverlayFilter와 연결한다(P2P 자막 포함 경로).
   * textOverlayFilter 연결 실패 시 폴백으로 outgoingMedia에 직접 연결을 시도한다.
   *
   * @param sender 스트림을 송출하는 참가자 세션
   * @return 발신자로부터 미디어를 수신하는 WebRtcEndpoint
   */
  private WebRtcEndpoint getEndpointForUser(final KurentoUserSession sender) {
    // 자신 → 자신(loopback): 루프백은 별도 incomingMedia를 생성하지 않고 outgoingMedia를 반환한다
    if (sender.getUserId().equals(this.getUserId())) {
      log.debug("PARTICIPANT {}: configuring loopback", this.getUserId());
      // 루프백: 자신의 textOverlay를 통한 자신의 비디오를 받음
      return outgoingMedia;
    }

    // 참여자 name 이 sender 로부터 비디오를 받음을 확인
    log.debug("PARTICIPANT {}: receiving video from {}", this.getUserId(), sender.getUserId());

    // sender 의 이름으로 나의 incomingMedia 에서 sender 의 webrtcEndpoint 객체를 가져옴
    WebRtcEndpoint incomingMedia = this.incomingMedia.get(sender.getUserId());

    // 만약 가져온 incomingMedia 이 null 이라면
    // 즉 현재 내가 갖고 있는 incomingMedia 에 sender 의 webrtcEndPoint 객체가 없다면
    if (incomingMedia == null) {
      // 새로운 endpoint 가 만들어졌음을 확인
      log.debug("PARTICIPANT {}: creating new endpoint for {}", this.getUserId(), sender.getUserId());

      // 새로 incomingMedia , 즉 webRtcEndpoint 를 만들고
      incomingMedia = new WebRtcEndpoint.Builder(pipeline)
          .useDataChannels()
          .build();

      // P2P 연결 구현: sender.textOverlayFilter → incomingMedia (화상채팅용)
      // sender의 textOverlayFilter를 통해 자막이 적용된 비디오를 받음
      try {
        sender.textOverlayFilter.connect(incomingMedia);
        log.debug("P2P connection via TextOverlay from {} to {}", sender.getUserId(), this.getUserId());
      } catch (Exception e) {
        log.error("Failed to connect TextOverlay from {} to {}: {}", sender.getUserId(), this.getUserId(),
            e.getMessage());
        // 폴백: 직접 outgoingMedia 연결 시도
        try {
          sender.outgoingMedia.connect(incomingMedia);
          log.warn("Fallback: Direct connection from {} to {}", sender.getUserId(), this.getUserId());
        } catch (Exception fallbackE) {
          log.error("All connection methods failed from {} to {}", sender.getUserId(), this.getUserId());
        }
      }

      // incomingMedia 객체의 addIceCandidateFoundListener 메서드 실행
      incomingMedia.addIceCandidateFoundListener(event -> {
        // json 오브젝트 생성
        JsonObject response = new JsonObject();

        // { id : "iceCandidate"}
        response.addProperty("id", "iceCandidate");
        // { name : sender 의 유저명}
        response.addProperty("name", sender.getUserId());
        // {candidate : { event.getCandidate 를 json 으로 만든 형태 }
        response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
        try {
          // 새로 webRtcEndpoint 가 만들어 졌기 때문에 모든 UserSession 이 이것을 동일하게 공유해야 할 필요가 있다.
          // 즉 모든 UserSession 의 정보를 일치시키기 위해 동기화 - synchronized - 실행
          // 이를 통해서 모든 user 의 incomingMedia 가 동일하게 일치 - 동기화 - 됨
          synchronized (session) {
            session.sendMessage(new TextMessage(response.toString()));
          }
        } catch (IOException e) {
          log.debug(e.getMessage());
        }
      });

      // incomingMedia 에 유저명과 새로 생성된 incomingMedia - webrtcEndPoint 객체 - 을 넣어준다
      this.incomingMedia.put(sender.getUserId(), incomingMedia);
    }

    log.debug("PARTICIPANT {}: obtained endpoint for {}", this.getUserId(), sender.getUserId());

    return incomingMedia;
  }

  /**
   * 특정 발신자로부터의 수신 미디어를 중단하고 Kurento 엔드포인트를 비동기로 해제한다.
   *
   * incomingMedia 맵에서 해당 발신자 항목을 원자적으로 제거한 후 Kurento의 비동기 release
   * 콜백으로 서버 측 자원을 정리한다. 이미 제거된 경우(null)에는 아무 작업도 수행하지 않는다.
   *
   * @param senderName 수신을 중단할 발신자의 userId
   */
  public void cancelVideoFrom(final String senderName) {
    log.debug("PARTICIPANT {}: canceling video reception from {}", this.getUserId(), senderName);
    // 맵에서 원자적으로 제거하여 중복 해제를 방지한다
    final WebRtcEndpoint incoming = incomingMedia.remove(senderName);

    log.debug("PARTICIPANT {}: removing endpoint for {}", this.getUserId(), senderName);
    if (Objects.nonNull(incoming)) {
      incoming.release(new Continuation<>() {
        @Override
        public void onSuccess(Void result) {
          log.trace("PARTICIPANT {}: Released successfully incoming EP for {}",
              KurentoUserSession.this.getUserId(), senderName);
        }

        @Override
        public void onError(Throwable cause) {
          log.warn("PARTICIPANT {}: Could not release incoming EP for {}", KurentoUserSession.this.getUserId(),
              senderName);
        }
      });
    }
  }

  /**
   * 이 세션의 모든 Kurento 미디어 자원과 WebSocket 연결을 해제한다.
   *
   * 해제 순서: incomingMedia, textOverlayFilter, Composite 경로, outgoingMedia, 마지막 WebSocket 순서다.
   * WebSocket close 가 실패해도 Kurento 자원 해제가 먼저 완료되도록 session.close()는 마지막에 실행한다.
   *
   * Kurento 자원 해제는 비동기 콜백(Continuation)으로 처리되므로
   * 이 메서드가 반환된 후에도 실제 해제가 진행될 수 있다.
   *
   * @throws IOException WebSocket 세션 종료 실패 시
   */
  @Override
  public void close() throws IOException {
    log.debug("PARTICIPANT {}: Releasing resources", this.getUserId());

    for (final String remoteParticipantName : incomingMedia.keySet()) {

      log.trace("PARTICIPANT {}: Released incoming EP for {}", this.getUserId(), remoteParticipantName);

      final WebRtcEndpoint ep = this.incomingMedia.get(remoteParticipantName);

      ep.release(new Continuation<>() {

        @Override
        public void onSuccess(Void result) {
          log.trace("PARTICIPANT {}: Released successfully incoming EP for {}",
              KurentoUserSession.this.getUserId(), remoteParticipantName);
        }

        @Override
        public void onError(Throwable cause) {
          log.warn("PARTICIPANT {}: Could not release incoming EP for {}", KurentoUserSession.this.getUserId(),
              remoteParticipantName);
        }
      });
    }

    // TextOverlay 필터 해제
    if (textOverlayFilter != null) {
      textOverlayFilter.release(new Continuation<>() {
        @Override
        public void onSuccess(Void result) {
          log.trace("PARTICIPANT {}: Released TextOverlay filter", KurentoUserSession.this.getUserId());
        }

        @Override
        public void onError(Throwable cause) {
          log.warn("USER {}: Could not release TextOverlay filter", KurentoUserSession.this.getUserId());
        }
      });
    }

    // Composite 연결 해제 (녹화 중이면)
    if (compositeHubPort != null) {
      log.debug("PARTICIPANT {}: Disconnecting from Composite", this.getUserId());
      disconnectFromComposite();
    }

    if (outgoingMedia != null) {
      outgoingMedia.release(new Continuation<>() {

        @Override
        public void onSuccess(Void result) {
          log.trace("PARTICIPANT {}: Released outgoing EP", KurentoUserSession.this.getUserId());
        }

        @Override
        public void onError(Throwable cause) {
          log.warn("USER {}: Could not release outgoing EP", KurentoUserSession.this.getUserId());
        }
      });
    }

    if (session != null && session.isOpen()) {
      try {
        session.close();
      } catch (IOException e) {
        log.warn("Failed to close WebSocket session after Kurento resource release: userId={}, sessionId={}",
            getUserId(), session.getId(), e);
        throw e;
      }
    }
  }

  /**
   * WebSocket 세션을 통해 JSON 메시지를 전송한다.
   *
   * synchronized(session)으로 동일 세션에 대한 동시 쓰기 경합을 방지한다.
   * Spring WebSocket은 단일 세션에 대해 동시 전송을 허용하지 않으므로
   * ICE 후보 이벤트 리스너(별도 스레드)와 핸들러 스레드가 동시에 전송하는
   * 경쟁 조건을 이 동기화 블록이 막아준다.
   * 전송 실패 시 IOException을 전파한다.
   *
   * @param message 전송할 JSON 메시지
   * @throws IOException WebSocket 전송 실패 시
   */
  public void sendMessage(JsonObject message) throws IOException {
    log.debug("USER {}: Sending message {}", getUserId(), message);
    // 동시 쓰기 경합 방지: 같은 WebSocket 세션에 복수 스레드가 동시에 write하지 않도록 직렬화한다
    synchronized (session) {
      try {
        session.sendMessage(new TextMessage(message.toString()));
      } catch (Exception e) {
        if (isClientDisconnectException(e)) {
          log.debug("Client disconnected during send: userId={}, sessionId={}", getUserId(), session.getId());
        } else {
          log.error("Failed to send WebSocket message: userId={}, sessionId={}", getUserId(), session.getId(), e);
        }
        throw new IOException("Failed to send WebSocket message", e);
      }
    }
  }

  private boolean isClientDisconnectException(Throwable e) {
    if (e == null) return false;
    String msg = e.getMessage();
    if (msg != null) {
      String lower = msg.toLowerCase();
      if (lower.contains("broken pipe") || lower.contains("connection reset")
          || lower.contains("closed channel") || lower.contains("connection aborted")) {
        return true;
      }
    }
    return isClientDisconnectException(e.getCause());
  }

  /**
   * 클라이언트에서 수집한 ICE 후보를 적합한 WebRtcEndpoint에 등록한다.
   *
   * name이 자신의 userId(outgoing 경로)이면 outgoingMedia에 추가한다.
   * 다른 참가자의 userId이면 incomingMedia에서 해당 엔드포인트를 찾아 추가한다.
   * 해당 엔드포인트가 아직 생성되지 않은 경우(null)에는 후보를 무시한다.
   *
   * @param candidate Trickle ICE 방식으로 수집된 ICE 후보
   * @param name      ICE 후보가 속하는 발신자의 userId
   */
  public void addCandidate(IceCandidate candidate, String name) {
    // name이 자신이면 outgoing 경로, 아니면 해당 발신자의 incoming 경로에 추가한다
    if (this.getUserId().compareTo(name) == 0) {
      outgoingMedia.addIceCandidate(candidate);
    } else {
      WebRtcEndpoint webRtc = incomingMedia.get(name);
      if (webRtc != null) {
        webRtc.addIceCandidate(candidate);
      }
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see java.lang.Object#equals(java.lang.Object)
   */
  @Override
  public boolean equals(Object obj) {

    if (this == obj) {
      return true;
    }
    if (obj == null || !(obj instanceof KurentoUserSession)) {
      return false;
    }
    KurentoUserSession other = (KurentoUserSession) obj;
    String userId = this.getUserId();
    boolean eq = userId.equals(other.getUserId());
    eq &= roomId.equals(other.roomId);
    return eq;
  }

  /*
   * (non-Javadoc)
   *
   * @see java.lang.Object#hashCode()
   */
  @Override
  public int hashCode() {
    int result = 1;
    result = 31 * result + this.getUserId().hashCode();
    result = 31 * result + roomId.hashCode();
    return result;
  }

  /**
   * 실시간 비디오 스트림에 텍스트 오버레이를 3초간 표시한다.
   *
   * GStreamer textoverlay 필터의 text 속성을 업데이트한 후 3초 뒤 별도 스레드에서 초기화한다.
   * textOverlayFilter가 null이거나 text가 비어 있으면 아무 작업도 수행하지 않는다.
   *
   * @param text 오버레이할 텍스트
   */
  public void showTextOverlay(String text) {
    try {
      if (textOverlayFilter != null && text != null && !text.trim().isEmpty()) {
        log.debug("Showing text overlay for user {}: {}", this.getUserId(), text);

        // GStreamer textoverlay 필터의 text 속성 업데이트
        textOverlayFilter.setElementProperty("text", text);

        log.debug("Text overlay updated successfully: {}", text);

        // 3초 후 텍스트 제거
        new Thread(() -> {
          try {
            Thread.sleep(3000);
            textOverlayFilter.setElementProperty("text", "");
            log.debug("Text overlay cleared for user: {}", this.getUserId());
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }).start();

      } else {
        log.warn("Cannot show text overlay - filter not initialized or text is empty for user: {}", this.getUserId());
      }
    } catch (Exception e) {
      log.error("Error showing text overlay for user {}: {}", this.getUserId(), e.getMessage());
    }
  }

  /**
   * Composite에 사용자 미디어를 연결한다 (녹화용).
   *
   * textOverlayFilter - compositeScaler - compositeHubPort 경로로 연결하여
   * 자막이 포함된 미디어를 Composite에 전달한다.
   * composite가 null이거나 이미 연결된 경우에는 아무 작업도 수행하지 않는다.
   * 연결 실패 시 생성된 리소스를 정리하고 null로 초기화한다.
   *
   * @param composite 연결할 Composite 객체
   */
  public void connectToComposite(Composite composite) {
    if (composite == null) {
      log.warn("Cannot connect to null Composite for user: {}", this.getUserId());
      return;
    }

    // 이미 연결되어 있으면 무시
    if (compositeHubPort != null) {
      log.debug("User {} already connected to Composite", this.getUserId());
      return;
    }

    try {
      // Composite와 동일한 파이프라인인지 확인
      MediaPipeline compositePipeline = composite.getMediaPipeline();
      if (!compositePipeline.equals(this.pipeline)) {
        log.error("Cannot connect to Composite - different pipeline for user: {}", this.getUserId());
        return;
      }

      // Composite용 HubPort 생성
      compositeHubPort = new HubPort.Builder(composite).build();

      // videoscale 중간 필터 생성 (해상도 격리)
      // Composite의 caps renegotiation이 P2P 경로에 전파되지 않도록 함
      compositeScaler = new GStreamerFilter.Builder(pipeline, "videoscale").build();

      // textOverlayFilter → compositeScaler → compositeHubPort 연결
      // videoscale이 caps 차이를 흡수하여 P2P 해상도 유지
      textOverlayFilter.connect(compositeScaler);
      compositeScaler.connect(compositeHubPort);

      // HubPortMap에 저장 (추후 관리용)
      KurentoHubPortMap.setUserHubPort(roomId, this.getUserId(), compositeHubPort);

      log.info("User {} connected to Composite via TextOverlay → videoscale for recording", this.getUserId());

    } catch (Exception e) {
      log.error("Failed to connect user {} to Composite: {}", this.getUserId(), e.getMessage());
      // 실패 시 생성된 리소스 정리
      if (compositeScaler != null) {
        try {
          compositeScaler.release();
        } catch (Exception releaseEx) {
          log.error("Failed to release compositeScaler after connection failure: {}", releaseEx.getMessage());
        }
        compositeScaler = null;
      }
      if (compositeHubPort != null) {
        try {
          compositeHubPort.release();
        } catch (Exception releaseEx) {
          log.error("Failed to release HubPort after connection failure: {}", releaseEx.getMessage());
        }
        compositeHubPort = null;
      }
    }
  }

  /**
   * Composite에서 사용자 미디어 연결을 해제한다.
   *
   * compositeScaler와 compositeHubPort를 비동기로 해제하고 KurentoHubPortMap에서 항목을 제거한다.
   * compositeHubPort가 null이면 아무 작업도 수행하지 않는다.
   */
  public void disconnectFromComposite() {
    if (compositeHubPort == null) {
      log.debug("User {} not connected to Composite", this.getUserId());
      return;
    }

    try {
      // compositeScaler 해제
      if (compositeScaler != null) {
        compositeScaler.release(new Continuation<>() {
          @Override
          public void onSuccess(Void result) {
            log.trace("PARTICIPANT {}: Released composite scaler", KurentoUserSession.this.getUserId());
          }

          @Override
          public void onError(Throwable cause) {
            log.warn("Failed to release composite scaler for user {}: {}",
                KurentoUserSession.this.getUserId(), cause.getMessage());
          }
        });
        compositeScaler = null;
      }

      // HubPort 해제
      compositeHubPort.release(new Continuation<>() {
        @Override
        public void onSuccess(Void result) {
          log.info("User {} disconnected from Composite successfully", KurentoUserSession.this.getUserId());
        }

        @Override
        public void onError(Throwable cause) {
          log.warn("Failed to release Composite HubPort for user {}: {}",
              KurentoUserSession.this.getUserId(), cause.getMessage());
        }
      });

      // HubPortMap에서 제거
      KurentoHubPortMap.removeUserHubPort(roomId, this.getUserId());

      compositeHubPort = null;

    } catch (Exception e) {
      log.error("Error disconnecting user {} from Composite: {}", this.getUserId(), e.getMessage());
    }
  }

  /**
   * Composite 연결 여부를 확인한다.
   *
   * @return compositeHubPort가 null이 아니면 true (Composite에 연결된 상태)
   */
  public boolean isConnectedToComposite() {
    return compositeHubPort != null;
  }
}
