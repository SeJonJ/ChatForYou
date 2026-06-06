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

package webChat.model.room;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.SerializedName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.kurento.client.*;
import webChat.model.chat.ChatType;
import webChat.model.game.GameSettingInfo;
import webChat.model.record.RecordingInfo;
import webChat.repository.kurento.KurentoCompositeMap;
import webChat.repository.kurento.KurentoPipelineMap;
import webChat.repository.kurento.KurentoRecorderMap;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.service.chatroom.participant.KurentoParticipantService;

import javax.annotation.PreDestroy;
import java.io.Closeable;

/**
 * @modifyBy SeJon Jang (wkdtpwhs@gmail.com)
 * @desc 화상채팅을 위한 클래스 ChatRoomDto 를 상속받음
 */
@Getter
@Setter
@NoArgsConstructor
@Slf4j
public class KurentoRoom extends ChatRoom implements Closeable {

  @JsonIgnore
  private transient KurentoClient kurento;

  @JsonIgnore
  private KurentoParticipantService participantService;

  @JsonIgnore
  private boolean isKurentoInitialized = false;

  @SerializedName("game_setting_info")
  @JsonProperty("game_setting_info")
  private GameSettingInfo gameSettingInfo; // 방의 게임 정보 세팅

  // 녹화 객체
  @JsonIgnore
  private transient RecorderEndpoint roomRecorder;

  // 녹화를 위한 hubport
  @JsonIgnore
  private transient HubPort roomRecorderHubPort;

  private boolean hasRecordedOnce = false; // 방의 녹화 정보가 있는지 여부(한번이라도 녹화되었다면 true)
  private boolean isRecordingInProgress = false; // 현재 녹화 중 여부

  @SerializedName("recording_info")
  @JsonProperty("recording_info")
  private RecordingInfo recordingInfo; // 녹화 정보

  // 룸 정보 set
  public KurentoRoom(String roomId, String roomName, String creator, String roomPwd, boolean secretChk, int userCount, int maxUserCnt, ChatType chatType, String instanceId){
    super(roomId, roomName, creator, userCount, maxUserCnt, roomPwd, secretChk, chatType, RoomState.CREATED, instanceId);
  }

  // Kurento 리소스가 필요한 시점에 초기화
  private void kurentoInitialized() {
    if (!isKurentoInitialized && kurento != null) {
      try {
        this.isKurentoInitialized = true;
        log.info("Kurento 리소스 초기화 완료: {}", this.getRoomId());
      } catch (Exception e) {
        log.error("Kurento 리소스 초기화 실패: {}", this.getRoomId(), e);
      }
    }
  }

  public void activate() {
    if(this.getRoomState() != RoomState.ACTIVE){
      this.setRoomState(RoomState.ACTIVE);
      isKurentoInitialized = true;
    }
  }

  /**
   *  방 상태 변경 : deactive
   */
  public void deactivate() {
    isKurentoInitialized = false;
    this.setRoomState(RoomState.INACTIVE);
  }

  // roomId 가져오기
  @Override
  public String getRoomId() {
    return super.getRoomId();
  }

  /**
   * @desc 종료시 실행?
   * */
  @JsonIgnore
  @PreDestroy
  private void shutdown() {
    this.close();
  }

  @Override
  public void close() {
    // 방에서 녹화한 적이 있으면 중지 시도
    if (this.isRecordingInProgress) {
      this.stopRoomRecording(this.recordingInfo.getRecordingId());
    }

    isKurentoInitialized = false;
    this.setRoomState(RoomState.INACTIVE);
    this.kurento = null;
  }

  public void initUserHubPort(){
    if (KurentoCompositeMap.getComposite(this.getRoomId()) != null) {
      log.debug("Composite already exists for room: {}", this.getRoomId());
      return;
    }

    String roomId = this.getRoomId();
    
    // 기존에 생성된 파이프라인 사용 (사용자들과 동일한 파이프라인)
    MediaPipeline pipeline = KurentoPipelineMap.getInstance().get(roomId);
    if (pipeline == null) {
      // 파이프라인이 없으면 새로 생성
      pipeline = this.getKurento().createMediaPipeline();
      KurentoPipelineMap.getInstance().put(roomId, pipeline);
    }

    // Composite 생성 - 사용자들과 동일한 파이프라인 사용
    Composite composite = new Composite.Builder(pipeline).build();
    KurentoCompositeMap.setComposite(roomId, composite);
    log.info("KurentoRoom created with Composite for room: {} using shared pipeline", roomId);
  }

  /**
   * 방 전체 녹화 시작 - 모든 사용자의 비디오/오디오를 하나의 파일로 녹화
   * @param recordId 녹화 ID
   * @param recordingInfo 녹화 정보
   */
  public void startRoomRecording(String recordId, MediaProfileSpecType mediaProfileSpecType, RecordingInfo recordingInfo) {
    if (this.isHasRecordedOnce()) {
      log.warn("Room already has recorded once for room: {}", this.getRoomId());
      return;
    }

    try {
      String roomId = this.getRoomId();
      Composite composite = KurentoCompositeMap.getComposite(roomId);

      if (composite == null) {
        throw new ChatForYouException(ErrorCode.KURENTO_COMPOSITE_NOT_FOUND, "roomId=" + roomId);
      }

      // Composite와 같은 pipeline에서 RecorderEndpoint 생성
      MediaPipeline compositePipeline = composite.getMediaPipeline();
      RecorderEndpoint roomRecorder = new RecorderEndpoint.Builder(compositePipeline, recordingInfo.getRecordingFile().getFileFullPath())
              .withMediaProfile(mediaProfileSpecType)
              .build();

      // Composite의 통합된 출력을 RecorderEndpoint에 연결
      HubPort roomRecorderHubPort = new HubPort.Builder(composite).build();
      roomRecorderHubPort.connect(roomRecorder);

      // 녹화 시작
      roomRecorder.record();
      
      // Map에 저장 (메모리 관리)
      KurentoRecorderMap.setRecorderEndpoint(roomId, roomRecorder);
      KurentoRecorderMap.setRecorderHubPort(roomId, roomRecorderHubPort);
      
      // Redis 상태 업데이트
      this.hasRecordedOnce = true;
      this.isRecordingInProgress = true;
//      this.currentRecordId = recordId;

      log.info("Room recording started for room {} with recordId {} - all users will be recorded in single file", roomId, recordId);

    } catch (Exception e) {
      log.error("Failed to start room recording for room {}: {}", this.getRoomId(), e.getMessage());
      // 실패 시 리소스 정리 및 Redis 상태 초기화
      this.cleanupRoomRecording();
      throw new ChatForYouException(ErrorCode.RECORDING_START_FAILED, "roomId=" + this.getRoomId(), e);
    }
  }

  /**
   * 방 전체 녹화 중지
   */
  public void stopRoomRecording(String recordId) {
    String roomId = this.getRoomId();
    
    if (!this.hasRecordedOnce) {
      log.warn("No recording has been started for room: {}", roomId);
      return;
    }

    try {
      // Map 에서 RecorderEndpoint 조회
      RecorderEndpoint roomRecorder = KurentoRecorderMap.getRecorderEndpoint(roomId);

      if (roomRecorder != null) {
        // RecorderEndpoint가 있으면 녹화 중지
        roomRecorder.stopAndWait();
        log.info("Room recording stopped for room {} with recordId {}", roomId, recordId);
      } else {
        log.warn("RecorderEndpoint not found in map for room: {} (may be on different server)", roomId);
      }
        this.isRecordingInProgress = false;
    } catch (Exception e) {
      log.error("Error stopping room recording for room {}: {}", roomId, e.getMessage());
    } finally {
      // 리소스 정리 (Map에서 제거 및 상태 초기화)
      this.cleanupRoomRecording();
    }
  }

  /**
   * 녹화 리소스 정리
   */
  private void cleanupRoomRecording() {
    try {
      String roomId = this.getRoomId();
      
      // Map 에서 RecorderEndpoint 조회 및 해제
      RecorderEndpoint roomRecorder = KurentoRecorderMap.getRecorderEndpoint(roomId);
      if (roomRecorder != null) {
        roomRecorder.release();
      }

      // Map 에서 HubPort 조회 및 해제
      HubPort roomRecorderHubPort = KurentoRecorderMap.getRecorderHubPort(roomId);
      if (roomRecorderHubPort != null) {
        roomRecorderHubPort.release();
      }

      // Map 에서 Composite 조회 및 해제
      Composite composite = KurentoCompositeMap.getComposite(roomId);
      if (composite != null) {
        composite.release();
        KurentoCompositeMap.removeComposite(roomId);
      }
      
      // Map 에서 제거
      KurentoRecorderMap.removeRecorder(roomId);

    } catch (Exception e) {
      log.error("Error cleaning up room recording resources for room {}: {}", this.getRoomId(), e.getMessage());
    }
  }

}
