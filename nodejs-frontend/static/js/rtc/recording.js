/*
 * Copyright 2025 SejonJang (wkdtpwhs@gmail.com)
 *
 * Licensed under the  GNU General Public License v3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.gnu.org/licenses/gpl-3.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * 녹화 관련 기능을 구현하기 위한 js
 * - 서버 측 녹화 (Kurento Media Server)
 * - 클라이언트 측 녹화 (MediaRecorder API)
 * - 오디오 믹싱 (AudioMixer)
 */

/**
 * UI 버튼 상태 설정
 */
const RECORDING_UI_STATES = {
    recording: {
        startBtn: { disabled: true, opacity: 0.5, cursor: 'not-allowed', image: 'disabled' },
        stopBtn: { disabled: false, opacity: 1, cursor: 'pointer', image: 'active' }
    },
    idle: {
        startBtn: { disabled: false, opacity: 1, cursor: 'pointer', image: 'active' },
        stopBtn: { disabled: true, opacity: 0.5, cursor: 'not-allowed', image: 'disabled' }
    },
    disabled: {
        startBtn: { disabled: true, opacity: 0.5, cursor: 'not-allowed', image: 'disabled' },
        stopBtn: { disabled: true, opacity: 0.5, cursor: 'not-allowed', image: 'disabled' }
    },
    enabled: {
        startBtn: { disabled: false, opacity: 1, cursor: 'pointer', image: 'active' },
        stopBtn: { disabled: true, opacity: 0.5, cursor: 'not-allowed', image: 'disabled' }
    },
    permanentlyDisabled: {
        startBtn: { disabled: true, opacity: 0.3, cursor: 'not-allowed', image: 'disabled', filter: 'grayscale(100%)' },
        stopBtn: { disabled: true, opacity: 0.3, cursor: 'not-allowed', image: 'disabled', filter: 'grayscale(100%)' }
    }
};

/**
 * Toast 메시지 테마 설정
 */
const TOAST_THEMES = {
    success: { background: "linear-gradient(to right, #00b09b, #96c93d)" },
    warning: { background: "linear-gradient(to right, #FF6B6B, #FFE66D)" },
    error: { background: "linear-gradient(to right, #ff5f6d, #ffc371)" },
    info: { background: "linear-gradient(to right, #4facfe, #00f2fe)" }
};

/**
 * Toast 기본 설정
 */
const TOAST_DEFAULT_CONFIG = {
    duration: 3000,
    newWindow: true,
    close: true,
    gravity: "top",
    position: "center",
    stopOnFocus: true
};

const recording = {
    // UI 요소
    $recordingStartBtn: $('#recordingStartBtn'),
    $recordingStopBtn: $('#recordingStopBtn'),

    // 녹화 상태
    isRecording: false,
    isOtherUserRecording: false,

    // 해당 방에서 녹화 사용 여부
    isReadyRecording: false,

    // 서버 측 녹화
    serverRecordingId: null,

    // 클라이언트 측 녹화 (선택적)
    clientRecorder: null,
    clientRecordedChunks: [],
    clientRecordingEnabled: false, // 클라이언트 녹화 활성화 여부

    // 오디오 믹서
    audioMixer: null,

    // 설정
    config: {
        mimeType: 'video/webm;codecs=vp8,opus',
        videoBitsPerSecond: 2500000, // 2.5 Mbps
        audioBitsPerSecond: 128000   // 128 kbps
    },

    /**
     * 초기화
     */
    init: function () {
        let self = this;

        // AudioMixer 초기화
        try {
            self.audioMixer = getAudioMixer();
            console.log('AudioMixer 초기화 완료');
        } catch (error) {
            console.error('AudioMixer 초기화 실패:', error);
        }

        // 이벤트 등록
        self.initEvent();

        // MediaRecorder 지원 여부 확인
        self.checkMediaRecorderSupport();
    },

    /**
     * 이벤트 등록
     */
    initEvent: function () {
        let self = this;

        // 녹화 시작 버튼
        self.$recordingStartBtn.click(function (e) {
            // 다른 사용자가 녹화 중이거나 이미 녹화 중이면 차단
            if (self.isOtherUserRecording || self.isRecording) {
                e.preventDefault();
                e.stopPropagation();
                e.stopImmediatePropagation();

                if (self.isOtherUserRecording) {
                    self.showToast('다른 사용자가 녹화 중입니다.');
                } else if (self.isRecording) {
                    self.showToast('이미 녹화 중입니다.');
                }
                return false;
            }
            self.startRecording();
        });

        // 녹화 중지 버튼
        self.$recordingStopBtn.click(function (e) {
            // 다른 사용자가 녹화 중이거나 녹화 중이 아니면 차단
            if (self.isOtherUserRecording || !self.isRecording) {
                e.preventDefault();
                e.stopPropagation();
                e.stopImmediatePropagation();

                if (self.isOtherUserRecording) {
                    self.showToast('다른 사용자가 녹화 중입니다. 녹화를 시작한 사용자만 중지할 수 있습니다.');
                } else if (!self.isRecording) {
                    self.showToast('녹화 중이 아닙니다.');
                }
                return false;
            }
            self.stopRecording();
        });
    },

    /**
     * MediaRecorder 지원 여부 확인
     */
    checkMediaRecorderSupport: function () {
        let self = this;

        if (typeof MediaRecorder === 'undefined') {
            console.warn('이 브라우저는 MediaRecorder API를 지원하지 않습니다.');
            self.clientRecordingEnabled = false;
            return;
        }

        // 지원하는 MIME 타입 확인
        const mimeTypes = [
            'video/webm;codecs=vp8,opus',
            'video/webm;codecs=vp9,opus',
            'video/webm',
            'video/mp4'
        ];

        for (const mimeType of mimeTypes) {
            if (MediaRecorder.isTypeSupported(mimeType)) {
                self.config.mimeType = mimeType;
                console.log('지원하는 MIME 타입:', mimeType);
                break;
            }
        }

        self.clientRecordingEnabled = true;
    },

    /**
     * Toast 메시지 표시
     * @param {string} text - 메시지 텍스트
     * @param {number} duration - 표시 시간 (ms), 기본값 3000
     * @param {string} theme - 테마 (success, warning, error, info), 기본값 'info'
     */
    showToast: function (text, duration, theme) {
        // 기본값 설정
        duration = duration || TOAST_DEFAULT_CONFIG.duration;
        theme = theme || 'info';
        
        if (typeof Toastify !== 'undefined') {
            Toastify({
                text: text,
                duration: duration,
                newWindow: TOAST_DEFAULT_CONFIG.newWindow,
                close: TOAST_DEFAULT_CONFIG.close,
                gravity: TOAST_DEFAULT_CONFIG.gravity,
                position: TOAST_DEFAULT_CONFIG.position,
                stopOnFocus: TOAST_DEFAULT_CONFIG.stopOnFocus,
                style: TOAST_THEMES[theme] || TOAST_THEMES.info
            }).showToast();
        } else {
            console.log('[TOAST]', text);
        }
    },

    /**
     * 녹화 시작
     */
    startRecording: function () {
        let self = this;
        if(self.isReadyRecording) {
            self.showToast('해당 방은 이미 녹화 파일이 있습니다. 녹화를 시작할 수 없습니다.', 3000, 'warning');
            return;
        }

        try {
            self.isRecording = true;
            self.isReadyRecording = true;

            // 1. 서버에 녹화 시작 요청
            self.startServerRecording();

            // 2. 오디오 믹싱 시작
            self.startAudioMixing();

            // 3. UI 업데이트
            self.updateUI('recording');

            // 4. DataChannel로 녹화 시작 알림
            dataChannel?.sendMessage?.('recordingStarted', 'recording');

            // 5. 자막 기능 비활성화
            if (typeof speechRecognitionUtils !== 'undefined' && speechRecognitionUtils.handlingSubtitleByRecording) {
                console.log('[RECORDING] Disabling subtitle function on recording start');
                speechRecognitionUtils.handlingSubtitleByRecording(true);
            } else {
                console.warn('[RECORDING] speechRecognitionUtils not available');
            }

            console.log('녹화 시작 완료, isReadyRecording:', self.isReadyRecording);
            self.showToast('녹화를 시작합니다.');

        } catch (error) {
            console.error('녹화 시작 실패:', error);
            self.showToast('녹화 시작에 실패했습니다: ' + error.message);
            self.isRecording = false;
            self.updateUI('idle');
        }
    },

    /**
     * 서버 측 녹화 시작
     */
    startServerRecording: function () {
        let self = this;

        // WebSocket을 통해 서버로 녹화 시작 메시지 전송
        if (typeof sendMessageToServer === 'function') {
            sendMessageToServer({
                event: 'RECORDING_START',
                roomId: roomId,
                senderId: userId,
                senderNickName: nickName,
            });
            console.log('서버 녹화 시작 요청 전송');
        } else {
            throw new Error('sendMessageToServer 함수를 찾을 수 없습니다.');
        }
    },

    /**
     * 오디오 스트림 유효성 검사
     * @private
     * @param {MediaStream} stream - 검사할 스트림
     * @returns {boolean}
     */
    _isValidAudioStream: function(stream) {
        if (!stream || !stream.getAudioTracks || stream.getAudioTracks().length === 0) {
            return false;
        }
        
        let audioTracks = stream.getAudioTracks();
        return audioTracks.some(function(track) {
            return track.enabled && track.readyState === 'live';
        });
    },

    /**
     * 로컬 오디오 추가 가능 여부 확인
     * @private
     * @returns {boolean}
     */
    _shouldAddLocalAudio: function() {
        let self = this;
        
        if (typeof localStream === 'undefined' || !localStream) {
            return false;
        }
        
        let localUserId = typeof ParticipantUtils !== 'undefined'
            ? ParticipantUtils.getLocalUserId()
            : null;
        
        if (localUserId && typeof ParticipantUtils !== 'undefined') {
            let audioState = ParticipantUtils.getAudioState(localUserId);
            return audioState.enabled && self._isValidAudioStream(localStream);
        }
        
        return self._isValidAudioStream(localStream);
    },

    /**
     * 오디오 믹싱 시작
     */
    startAudioMixing: function () {
        let self = this;

        // 서버가 Composite으로 녹화 중이면 클라이언트 AudioMixer 비활성화
        // 오디오 중복 방지
        if (self.serverRecordingId) {
            console.log('[Recording] 서버 Composite 녹화 중 - 클라이언트 AudioMixer 비활성화 (오디오 중복 방지)');
            return;
        }

        if (!self.audioMixer) {
            console.warn('AudioMixer가 초기화되지 않았습니다.');
            return;
        }

        try {
            // 클라이언트 전용 녹화인 경우에만 AudioMixer 사용
            // 모든 참가자의 오디오를 믹서에 추가
            if (typeof participants !== 'undefined') {
                for (const [userId, participant] of Object.entries(participants)) {
                    const stream = participant.rtcPeer?.getRemoteStream?.();
                    if (self._isValidAudioStream(stream)) {
                        self.audioMixer.addAudioStream(userId, stream);
                        console.log('AudioMixer에 추가: ' + userId);
                    }
                }
            }

            // 로컬 오디오 추가
            if (self._shouldAddLocalAudio()) {
                self.audioMixer.addAudioStream('local', localStream);
                console.log('로컬 오디오를 AudioMixer에 추가 (활성화됨)');
            } else {
                console.warn('로컬 오디오가 비활성화되어 있어서 녹화에서 제외됨');
            }

            console.log('오디오 믹싱 시작 완료, 총 소스:', self.audioMixer.getSourceCount());

        } catch (error) {
            console.error('오디오 믹싱 시작 실패:', error);
        }
    },

    /**
     * 클라이언트 측 녹화 시작 (선택적)
     */
    startClientRecording: function () {
        let self = this;

        if (!self.clientRecordingEnabled) {
            console.warn('클라이언트 녹화가 비활성화되어 있습니다.');
            return;
        }

        try {
            // 믹싱된 오디오 스트림 가져오기
            const mixedAudioStream = self.audioMixer.getMixedStream();

            // 비디오 스트림 가져오기 (화면 공유 또는 캠)
            let videoStream = null;
            if (typeof localStream !== 'undefined' && localStream) {
                const videoTracks = localStream.getVideoTracks();
                if (videoTracks.length > 0) {
                    videoStream = new MediaStream(videoTracks);
                }
            }

            // 오디오와 비디오 결합
            const combinedStream = new MediaStream();

            if (mixedAudioStream) {
                mixedAudioStream.getAudioTracks().forEach(track => {
                    combinedStream.addTrack(track);
                });
            }

            if (videoStream) {
                videoStream.getVideoTracks().forEach(track => {
                    combinedStream.addTrack(track);
                });
            }

            // MediaRecorder 생성
            self.clientRecorder = new MediaRecorder(combinedStream, {
                mimeType: self.config.mimeType,
                videoBitsPerSecond: self.config.videoBitsPerSecond,
                audioBitsPerSecond: self.config.audioBitsPerSecond
            });

            // 이벤트 리스너 등록
            self.clientRecorder.ondataavailable = (event) => {
                if (event.data && event.data.size > 0) {
                    self.clientRecordedChunks.push(event.data);
                }
            };

            self.clientRecorder.onstop = () => {
                self.saveClientRecording();
            };

            self.clientRecorder.onerror = (event) => {
                console.error('MediaRecorder 오류:', event.error);
            };

            // 녹화 시작
            self.clientRecordedChunks = [];
            self.clientRecorder.start(1000); // 1초마다 데이터 수집

            console.log('클라이언트 측 녹화 시작');

        } catch (error) {
            console.error('클라이언트 녹화 시작 실패:', error);
        }
    },

    /**
     * 녹화 중지
     */
    stopRecording: function () {
        let self = this;

        try {
            self.isRecording = false;

            // 1. 서버에 녹화 중지 요청
            self.stopServerRecording();

            // 2. 오디오 믹싱 중지
            self.stopAudioMixing();

            // 3. UI 업데이트
            // TODO: 추후 각 사용자별 1회 녹화 허용 시 로직 변경 필요
            self.updateUI('permanentlyDisabled');

            // 4. DataChannel로 녹화 중지 알림
            dataChannel?.sendMessage?.('recordingStopped', 'recording');

            // 5. 자막 기능 재활성화
            if (speechRecognitionUtils?.handlingSubtitleByRecording) {
                console.log('[RECORDING] Disabling subtitle function on recording stop');
                speechRecognitionUtils.handlingSubtitleByRecording(false);
            } else {
                console.warn('[RECORDING] speechRecognitionUtils not available');
            }

            console.log('녹화 중지 완료, isReadyRecording:', self.isReadyRecording);
            self.showToast('녹화를 중지했습니다.');

        } catch (error) {
            console.error('녹화 중지 실패:', error);
            self.showToast('녹화 중지에 실패했습니다: ' + error.message);
        }
    },

    /**
     * 서버 측 녹화 중지
     */
    stopServerRecording: function () {
        let self = this;

        // WebSocket을 통해 서버로 녹화 중지 메시지 전송
        if (typeof sendMessageToServer === 'function') {
            sendMessageToServer({
                event: 'RECORDING_STOP',
                roomId: roomId,
                senderId: userId,
                senderNickName: nickName,
            });
            console.log('서버 녹화 중지 요청 전송');
        }
    },

    /**
     * 오디오 믹싱 중지
     */
    stopAudioMixing: function () {
        let self = this;

        if (self.audioMixer) {
            self.audioMixer.clear();
            console.log('오디오 믹싱 중지');
        }
    },

    /**
     * 클라이언트 녹화 저장
     */
    saveClientRecording: function () {
        let self = this;

        if (self.clientRecordedChunks.length === 0) {
            console.warn('녹화된 데이터가 없습니다.');
            return;
        }

        try {
            // Blob 생성
            const blob = new Blob(self.clientRecordedChunks, {
                type: self.config.mimeType
            });

            // 다운로드 링크 생성
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.style.display = 'none';
            a.href = url;
            a.download = `recording_${new Date().getTime()}.webm`;

            document.body.appendChild(a);
            a.click();

            // 정리
            setTimeout(() => {
                document.body.removeChild(a);
                URL.revokeObjectURL(url);
            }, 100);

            console.log('녹화 파일 저장 완료:', a.download);
            self.showToast('녹화 파일이 다운로드되었습니다.');

        } catch (error) {
            console.error('녹화 파일 저장 실패:', error);
            self.showToast('녹화 파일 저장에 실패했습니다.');
        }
    },

    /**
     * 버튼 상태 적용
     * @private
     * @param {jQuery} $button - 버튼 jQuery 객체
     * @param {Object} config - 버튼 설정 객체
     * @param {string} buttonType - 버튼 타입 ('start' 또는 'stop')
     */
    _applyButtonState: function($button, config, buttonType) {
        $button.prop('disabled', config.disabled);

        // 파일명 규칙: active 상태는 접미사 없음, disabled 상태만 '-disabled' 추가
        // - active: record-start.svg, record-stop.svg
        // - disabled: record-start-disabled.svg, record-stop-disabled.svg
        const imageSuffix = config.image === 'disabled' ? '-disabled' : '';
        $button.attr('src', 'images/webrtc/recording/record-' + buttonType + imageSuffix + '.svg');

        let styles = {
            'pointer-events': config.disabled ? 'none' : 'auto',
            'cursor': config.cursor,
            'opacity': config.opacity,
            'user-select': 'none'
        };

        if (config.filter) {
            styles.filter = config.filter;
        }

        $button.css(styles);
    },

    /**
     * UI 업데이트
     */
    updateUI: function (state) {
        let self = this;
        
        let stateConfig = RECORDING_UI_STATES[state];
        if (!stateConfig) {
            console.error('Unknown UI state:', state);
            return;
        }
        
        self._applyButtonState(self.$recordingStartBtn, stateConfig.startBtn, 'start');
        self._applyButtonState(self.$recordingStopBtn, stateConfig.stopBtn, 'stop');
        
        if (state === 'permanentlyDisabled') {
            console.log('[RECORDING] UI permanently disabled - room already has recording');
        }
    },

    /**
     * 다른 사용자의 녹화 이벤트 처리
     */
    handlingRecordingEvent: function (recordingUser, eventType) {
        let self = this;
        let recordingEvent = eventType === 'recordingStarted';

        // [FIX] 녹화 시작 시에만 isReadyRecording을 true로 설정
        // TODO: 추후 각 사용자별 1회 녹화 허용 시 사용자별 녹화 횟수 추적 필요
        if (eventType === 'recordingStarted') {
            self.isReadyRecording = true;
            console.log('[RECORDING] Room recording started by:', recordingUser, '- isReadyRecording set to true');
        }

        // 녹화 알림
        let recordingToast = recordingUser + '님이 녹화를 ' + (recordingEvent ? '시작' : '중지') + '했습니다';
        self.showToast(recordingToast);

        // 다른 사용자의 이벤트인 경우
        if (nickName !== recordingUser) {
            if (eventType === 'recordingStarted') {
                self.isOtherUserRecording = true;
                self.updateUI('disabled');

                // 자막 기능 비활성화
                if (typeof speechRecognitionUtils !== 'undefined' && speechRecognitionUtils.handlingSubtitleByRecording) {
                    console.log('[RECORDING] Disabling subtitle function (other user recording)');
                    speechRecognitionUtils.handlingSubtitleByRecording(true);
                } else {
                    console.warn('[RECORDING] speechRecognitionUtils not available');
                }

            } else if (eventType === 'recordingStopped') {
                self.isOtherUserRecording = false;

                // 현재 정책: 동일한 방에서 1번이라도 녹화하면 모든 사용자 녹화 불가
                // TODO: 추후 각 사용자별 1회 녹화 허용으로 변경 시 조건문 수정 필요
                console.log('[RECORDING] Recording stopped by:', recordingUser, '- Permanently disabling for all users in room');
                self.updateUI('permanentlyDisabled');

                // 자막 기능 재활성화
                if (typeof speechRecognitionUtils !== 'undefined' && speechRecognitionUtils.handlingSubtitleByRecording) {
                    console.log('[RECORDING] Re-enabling subtitle function');
                    speechRecognitionUtils.handlingSubtitleByRecording(false);
                } else {
                    console.warn('[RECORDING] speechRecognitionUtils not available');
                }
            }
        }
    },

    /**
     * 참가자 추가 시 오디오 믹서에 추가
     */
    addParticipantAudio: function (userId, stream) {
        let self = this;

        if (self.isRecording && self.audioMixer && stream) {
            self.audioMixer.addAudioStream(userId, stream);
            console.log(`녹화 중 새 참가자 오디오 추가: ${userId}`);
        }
    },

    /**
     * 참가자 제거 시 오디오 믹서에서 제거
     */
    removeParticipantAudio: function (userId) {
        let self = this;

        if (self.audioMixer) {
            self.audioMixer.removeAudioStream(userId);
            console.log(`오디오 믹서에서 참가자 제거: ${userId}`);
        }
    },

    /**
     * 녹화 상태 정보 반환
     */
    getStatus: function () {
        let self = this;

        return {
            isRecording: self.isRecording,
            isOtherUserRecording: self.isOtherUserRecording,
            serverRecordingId: self.serverRecordingId,
            clientRecordingEnabled: self.clientRecordingEnabled,
            audioMixerStatus: self.audioMixer ? self.audioMixer.getStatus() : null
        };
    },

    /**
     * 디버그 정보 출력
     */
    debug: function () {
        let self = this;

        console.log('=== Recording Debug Info ===');
        console.log(self.getStatus());

        if (self.audioMixer) {
            self.audioMixer.debug();
        }

        console.log('===========================');
    },

    /**
     * 녹화 자동 중지 이벤트 처리
     * @param {Object} message - WebSocket 메시지 (recordingId, minutes, message)
     */
    handleAutoStopRecording: function(message) {
        let self = this;
        let minutes = message.minutes || 10;

        console.log('Recording auto-stopped:', message);

        // Toast 알림
        self.showToast(
            '녹화가 ' + minutes + '분 경과로 자동 종료되었습니다.\n업로드 중입니다...',
            5000,
            'warning'
        );

        // 녹화 상태 업데이트
        self.isRecording = false;
        // isOtherUserRecording는 다른 사용자의 녹화 상태이므로 여기서 변경하지 않음
        // 자동 중지는 '내가' 녹화를 시작한 경우에만 발생함

        // UI 업데이트
        self.updateUI('idle');

        // 자막 기능 처리
        if (typeof speechRecognitionUtils !== 'undefined') {
            speechRecognitionUtils.handlingSubtitleByRecording(false);
        }
    },

    /**
     * 녹화 업로드 완료 이벤트 처리
     * @param {Object} message - WebSocket 메시지 (recordingId, downloadUrl, fileName, filePath, fileSize, fileSizeMB)
     */
    handleUploadCompleted: function(message) {
        let self = this;
        let fileSizeMB = message.fileSizeMB || 0;
        let fileName = message.fileName || '';
        let filePath = message.minioFilePath || '';

        console.log('Recording upload completed:', message);

        // Toast 알림
        self.showToast(
            '녹화 파일 업로드가 완료되었습니다!\n파일 크기: ' + fileSizeMB + 'MB\n\n다운로드 링크를 확인하세요.',
            5000,
            'success'
        );

        // 다운로드 알림 UI 표시
        self.showDownloadNotification(fileName, filePath, fileSizeMB);

        // 채팅창에 녹화 링크 전송 (DataChannel)
        self.sendRecordingLinkToChat(fileName, filePath, fileSizeMB);
    },

    /**
     * 채팅창에 녹화 링크 전송 (DataChannel)
     * @param {string} fileName - 파일명
     * @param {string} filePath - 파일 경로
     * @param {number} fileSizeMB - 파일 크기 (MB)
     */
    sendRecordingLinkToChat: function(fileName, filePath, fileSizeMB) {
        try {
            const nickName = dataChannelChatting.user.nickName || 'System';

            // DataChannel을 통해 녹화 링크 메시지 전송
            const recordingLinkMessage = {
                type: 'recordingLink',
                userName: nickName,
                name: fileName,
                path: filePath,
                fileSizeMB: fileSizeMB,
                timestamp: new Date().getTime()
            };

            // dataChannel 을 link 전송
            dataChannel.sendMessage(recordingLinkMessage, recordingLinkMessage.type);

            // 로컬에서도 채팅창에 표시 (본인에게도 보이도록)
            dataChannelChatting.showNewRecordingLinkMessage({
                userName: nickName,
                name: fileName,
                path: filePath,
                fileSizeMB: fileSizeMB
            }, 'self');

        } catch (error) {
            console.error('[Recording] 채팅창에 녹화 링크 전송 실패:', error);
        }
    },

    /**
     * 녹화 업로드 실패 이벤트 처리
     * @param {Object} message - WebSocket 메시지 (recordingId, error, message)
     */
    handleUploadFailed: function(message) {
        let self = this;
        let errorMessage = message.error || '알 수 없는 오류';

        console.error('Recording upload failed:', message);

        // Toast 알림
        self.showToast(
            '녹화 파일 업로드에 실패했습니다.\n오류: ' + errorMessage,
            5000,
            'error'
        );
    },

    /**
     * 녹화 에러 핸들러
     * @param {Object} message - 에러 메시지 객체
     * @param {string} message.id - 에러 타입 (alreadyRecording, notRecording, 등)
     * @param {string} message.message - 에러 메시지
     */
    handleRecordingError: function(message) {
        let self = this;
        let errorType = message.id;
        let errorMessage = message.message || '녹화 중 오류가 발생했습니다.';

        console.error('[RECORDING ERROR]', errorType, errorMessage, message);

        //12. 특정 에러에 대해서는 UI를 permanentlyDisabled 상태로 변경
        const permanentlyDisabledErrors = [
            'alreadyRecording',
            'recordingEndpointNotFound', 
            'recordingFileExists',
            'recordingAutoStopFailed'
        ];

        if (permanentlyDisabledErrors.includes(errorType)) {
            console.log(`[RECORDING ERROR] Permanently disabling UI for error type: ${errorType}`);
            self.updateUI('permanentlyDisabled');
            
            // 녹화 상태 초기화
            self.isRecording = false;
            self.serverRecordingId = null;
            self.isReadyRecording = true;
        }
    },

    /**
     * 다운로드 알림 UI 표시
     * @param {string} fileName - 파일명
     * @param {string} filePath - 파일 경로
     * @param {number} fileSizeMB - 파일 크기 (MB)
     */
    showDownloadNotification: function(fileName, filePath, fileSizeMB) {
        let self = this;

        // 기존 알림이 있으면 제거
        $('#recording-download-notification').remove();

        // 다운로드 알림 HTML
        let notificationHTML = `
            <div id="recording-download-notification" style="
                position: fixed;
                top: 80px;
                right: 20px;
                background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                padding: 20px;
                border-radius: 12px;
                box-shadow: 0 8px 16px rgba(0,0,0,0.3);
                z-index: 10000;
                max-width: 350px;
                animation: slideInRight 0.3s ease-out;
            ">
                <div style="display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12px;">
                    <div style="display: flex; align-items: center;">
                        <i class="fas fa-video" style="color: white; font-size: 24px; margin-right: 10px;"></i>
                        <span style="color: white; font-weight: bold; font-size: 16px;">녹화 파일 준비 완료</span>
                    </div>
                    <button id="close-notification-btn" style="
                        background: transparent;
                        border: none;
                        color: white;
                        font-size: 20px;
                        cursor: pointer;
                        padding: 0;
                        line-height: 1;
                    ">×</button>
                </div>
                <div style="color: rgba(255,255,255,0.9); font-size: 14px; margin-bottom: 15px;">
                    파일 크기: ${fileSizeMB} MB
                </div>
                <button id="download-recording-btn"
                   data-file-name="${fileName}"
                   data-file-path="${filePath}"
                   style="
                       display: block;
                       width: 100%;
                       background: white;
                       color: #667eea;
                       padding: 12px 20px;
                       border-radius: 8px;
                       border: none;
                       font-weight: bold;
                       text-align: center;
                       transition: all 0.3s;
                       cursor: pointer;
                   ">
                    <i class="fas fa-download" style="margin-right: 8px;"></i>
                    녹화 파일 다운로드
                </button>
            </div>
            <style>
                @keyframes slideInRight {
                    from {
                        transform: translateX(400px);
                        opacity: 0;
                    }
                    to {
                        transform: translateX(0);
                        opacity: 1;
                    }
                }
                #download-recording-btn:hover {
                    background: #f0f0f0 !important;
                    transform: translateY(-2px);
                    box-shadow: 0 4px 8px rgba(0,0,0,0.2);
                }
            </style>
        `;

        // body에 추가
        $('body').append(notificationHTML);

        // 닫기 버튼 이벤트
        $('#close-notification-btn').on('click', function() {
            $('#recording-download-notification').fadeOut(300, function() {
                $(this).remove();
            });
        });

        // 다운로드 버튼 클릭 이벤트
        $('#download-recording-btn').on('click', function() {
            var name = $(this).data('file-name');
            var path = $(this).data('file-path');

            if (!name || !path) {
                console.error('[Recording] 다운로드 정보가 없습니다.');
                return;
            }

            console.log('[Recording] 녹화 파일 다운로드 시작:', name);
            dataChannelFileUtil.downloadFile({
                bucket: 'recording',
                name: name,
                path: path
            });
        });

        // 30초 후 자동 제거
        setTimeout(function() {
            $('#recording-download-notification').fadeOut(300, function() {
                $(this).remove();
            });
        }, 30000);
    }
};
