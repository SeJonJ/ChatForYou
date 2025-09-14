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
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import webChat.model.user.UserDto;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 *
 * @modifyBy SeJon Jang (wkdtpwhs@gmail.com)
 * @desc 결국엔 여기서 중요한 것은 현재의 '나' 의 webRtcEndPoint 객체와 다른 사람들의 webRtcEndPoint 객체를 저장한 map 을 확인하고
 * 새로운 유저가 들어왔을 때 이를 나의 map 에 저장하고, 다른 사람들과 이를 동기화 해서 일치 시키는 것?
 */
@RequiredArgsConstructor
@Getter
@Slf4j
public class KurentoUserSession extends UserDto implements Closeable {

  private final WebSocketSession session;

  private final MediaPipeline pipeline;

  private final String roomId;

  /**
   * @desc 현재 '나' 의 webRtcEndPoint 객체
   * 나의 것이니까 밖으로 내보낸다는 의미의 outgoingMedia
   * */
  private final WebRtcEndpoint outgoingMedia;

  /**
   * @desc '나'와 연결된 다른 사람의 webRtcEndPoint 객체 => map 형태로 유저명 : webRtcEndPoint 로 저장됨
   * 다른 사람꺼니까 받는다는 의미의 incomingMedia
   * */
  private final ConcurrentMap<String, WebRtcEndpoint> incomingMedia = new ConcurrentHashMap<>();

  /**
   * @desc 텍스트 오버레이를 위한 GStreamerFilter
   * */
  private GStreamerFilter textOverlayFilter;

  /**
   * @Param String 유저명, String 방이름, WebSocketSession 세션객체, MediaPipline (kurento)mediaPipeline 객체
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
    this.textOverlayFilter = new GStreamerFilter.Builder(pipeline, 
        "textoverlay text='' font-desc='Noto Sans CJK KR' halignment=center valignment=top deltay=50")
        .build();

    log.debug("TextOverlay filter created for user: {}", userId);

    // 미디어 파이프라인에 textoverlay 필터를 연결
    // outgoingMedia의 비디오를 textoverlay 필터를 거쳐서 다시 outgoingMedia로 연결
    try {
      log.debug("Connecting textOverlay filter to media pipeline for user: {}", userId);
    } catch (Exception e) {
      log.error("Failed to connect textOverlay filter for user {}: {}", userId, e.getMessage());
    }

    // iceCandidateFounder 이벤트 리스너 등록
    // 이벤트가 발생했을 때 다른 유저들에게 새로운 iceCnadidate 후보를 알림
    this.outgoingMedia.addIceCandidateFoundListener(new EventListener<IceCandidateFoundEvent>() {


      @Override
      public void onEvent(IceCandidateFoundEvent event) {
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
          /** synchronized 안에는 동기화 필요한 부분 지정*/
          // 먼저 동기화는 프로세스(스레드)가 수행되는 시점을 조절하여 서로가 알고 있는 정보가 일치하는 것
          // 여기서는 쉽게 말해 onEvent 를 통해서 넘어오는 모든 session 객체에게 앞에서 생성한 response json 을
          // 넘겨주게되고 이를 통해서 iceCandidate 상태를 '일치' 시킨다? ==> 여긴 잘 모르겟어요...
          synchronized (session) {
            session.sendMessage(new TextMessage(response.toString()));
          }
        } catch (IOException e) {
          log.debug(e.getMessage());
        }
      }
    });
  }

  /**
   * @desc
   * @Param userSession, String
   * */
  public void receiveVideoFrom(KurentoUserSession sender, String sdpOffer) throws IOException {
    // 유저가 room 에 들어왓음을 알림
    log.info("USER {}: connecting with {} in room {}", this.getUserId(), sender.getUserId(), this.roomId);

    // 들어온 유저가 Sdp 제안
    log.trace("USER {}: SdpOffer for {} is {}", this.getUserId(), sender.getUserId(), sdpOffer);

    /**
     *
     *  @Desc sdpOffer 에 대한 결과 String
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
   * @Desc userSession 을 통해서 해당 유저의 WebRtcEndPoint 객체를 가져옴
   * @Param UserSession : 보내는 유저의 userSession 객체
   * @return WebRtcEndPoint
   * */
  private WebRtcEndpoint getEndpointForUser(final KurentoUserSession sender) {
    // 만약 sender 명이 현재 user명과 일치한다면, 즉 sdpOffer 제안을 보내는 쪽과 받는 쪽이 동일하다면?
    // loopback 임을 찍고, textoverlay 필터를 연결한 후 outgoingMedia 를 return
    if (sender.getUserId().equals(this.getUserId())) {
      log.debug("PARTICIPANT {}: configuring loopback with textoverlay", this.getUserId());

      // textoverlay 필터를 outgoingMedia에 연결
      if (this.textOverlayFilter != null) {
        try {
          // outgoingMedia → textOverlayFilter → outgoingMedia : 자신의 비디오에 text overlay 기능 활성화
          // 이렇게 하면 자신의 비디오에 텍스트 오버레이가 적용
          this.outgoingMedia.connect(this.textOverlayFilter);
          this.textOverlayFilter.connect(this.outgoingMedia);
          log.debug("Connecting textoverlay filter for loopback user: {}", this.getUserId());
        } catch (Exception e) {
          log.error("Failed to connect textoverlay for loopback user {}: {}", this.getUserId(), e.getMessage());
        }
      }

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

      // sender의 textoverlay 필터를 이 incomingMedia에 연결
      if (sender.textOverlayFilter != null) {
        try {
          log.debug("Connecting sender's textoverlay filter from {} to {}", sender.getUserId(), this.getUserId());
          // sender의 outgoingMedia → sender의 textOverlayFilter → 이 incomingMedia
          sender.outgoingMedia.connect(sender.textOverlayFilter);
          sender.textOverlayFilter.connect(incomingMedia);
          log.debug("Successfully connected textoverlay filter from {} to {}", sender.getUserId(), this.getUserId());
        } catch (Exception e) {
          log.error("Failed to connect textoverlay filter from {} to {}: {}", sender.getUserId(), this.getUserId(), e.getMessage());
          // 실패 시 직접 연결
          sender.outgoingMedia.connect(incomingMedia);
        }
      } else {
        // textoverlay 필터가 없으면 직접 연결
        sender.outgoingMedia.connect(incomingMedia);
      }

      // incomingMedia 객체의 addIceCandidateFoundListener 메서드 실행
      incomingMedia.addIceCandidateFoundListener(new EventListener<IceCandidateFoundEvent>() {

        @Override
        public void onEvent(IceCandidateFoundEvent event) {
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
        }
      });

      // incomingMedia 에 유저명과 새로 생성된 incomingMedia - webrtcEndPoint 객체 - 을 넣어준다
      this.incomingMedia.put(sender.getUserId(), incomingMedia);
    }

    log.debug("PARTICIPANT {}: obtained endpoint for {}", this.getUserId(), sender.getUserId());

    return incomingMedia;
  }

  public void cancelVideoFrom(final KurentoUserSession sender) {
    this.cancelVideoFrom(sender.getUserId());
  }

  public void cancelVideoFrom(final String senderName) {
    log.debug("PARTICIPANT {}: canceling video reception from {}", this.getUserId(), senderName);
    final WebRtcEndpoint incoming = incomingMedia.remove(senderName);

    log.debug("PARTICIPANT {}: removing endpoint for {}", this.getUserId(), senderName);
    if (Objects.nonNull(incoming)) {
      incoming.release(new Continuation<Void>() {
        @Override
        public void onSuccess(Void result) throws Exception {
          log.trace("PARTICIPANT {}: Released successfully incoming EP for {}",
                  KurentoUserSession.this.getUserId(), senderName);
        }

        @Override
        public void onError(Throwable cause) throws Exception {
          log.warn("PARTICIPANT {}: Could not release incoming EP for {}", KurentoUserSession.this.getUserId(),
                  senderName);
        }
      });
    }
  }

  @Override
  public void close() throws IOException {
    log.debug("PARTICIPANT {}: Releasing resources", this.getUserId());
    for (final String remoteParticipantName : incomingMedia.keySet()) {

      log.trace("PARTICIPANT {}: Released incoming EP for {}", this.getUserId(), remoteParticipantName);

      final WebRtcEndpoint ep = this.incomingMedia.get(remoteParticipantName);

      ep.release(new Continuation<Void>() {

        @Override
        public void onSuccess(Void result) throws Exception {
          log.trace("PARTICIPANT {}: Released successfully incoming EP for {}",
                  KurentoUserSession.this.getUserId(), remoteParticipantName);
        }

        @Override
        public void onError(Throwable cause) throws Exception {
          log.warn("PARTICIPANT {}: Could not release incoming EP for {}", KurentoUserSession.this.getUserId(),
                  remoteParticipantName);
        }
      });
    }

    outgoingMedia.release(new Continuation<Void>() {

      @Override
      public void onSuccess(Void result) throws Exception {
        log.trace("PARTICIPANT {}: Released outgoing EP", KurentoUserSession.this.getUserId());
      }

      @Override
      public void onError(Throwable cause) throws Exception {
        log.warn("USER {}: Could not release outgoing EP", KurentoUserSession.this.getUserId());
      }
    });
  }

  public void sendMessage(JsonObject message) throws IOException {
    log.debug("USER {}: Sending message {}", getUserId(), message);
    synchronized (session) {
      try {
        session.sendMessage(new TextMessage(message.toString()));
      } catch (Exception e) {
        e.printStackTrace();
        message.addProperty("id", "ConnectionFail");
        message.addProperty("data", e.getMessage());
        this.sendMessage(message);
      }
    }
  }

  public void addCandidate(IceCandidate candidate, String name) {
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
   * @desc 텍스트 오버레이 표시
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
}