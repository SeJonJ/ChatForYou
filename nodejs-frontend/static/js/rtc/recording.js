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

const recording = {
    // UI 요소
    $recordingStartBtn: $('#recordingStartBtn'),
    $recordingStopBtn: $('#recordingStopBtn'),

    // 녹화 상태
    isRecording: false,
    isOtherUserRecording: false,

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
     */
    showToast: function (text, duration = 3000) {
        if (typeof Toastify !== 'undefined') {
            Toastify({
                text: text,
                duration: duration,
                newWindow: true,
                close: true,
                gravity: "top",
                position: "center",
                stopOnFocus: true,
                style: {
                    background: "linear-gradient(to right, #00b09b, #96c93d)",
                },
            }).showToast();
        } else {
            console.log(text);
        }
    },

    /**
     * 녹화 시작
     */
    startRecording: function () {
        let self = this;

        try {
            self.isRecording = true;

            // 1. 서버에 녹화 시작 요청
            self.startServerRecording();

            // 2. 오디오 믹싱 시작
            self.startAudioMixing();

            // 3. UI 업데이트
            self.updateUI('recording');

            // 4. DataChannel로 녹화 시작 알림
            dataChannel?.sendMessage?.('recordingStarted', 'recording');

            // 5. 자막 기능 비활성화
            speechRecognitionUtils?.handlingSubtitleByRecording?.(true);

            console.log('녹화 시작 완료');
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
     * 오디오 믹싱 시작
     */
    startAudioMixing: function () {
        let self = this;

        if (!self.audioMixer) {
            console.warn('AudioMixer가 초기화되지 않았습니다.');
            return;
        }

        try {
            // 모든 참가자의 오디오를 믹서에 추가
            // participants는 전역 변수로 가정 (kurento-service.js에서 관리)
            if (typeof participants !== 'undefined') {
                // participants는 객체이므로 Object.entries()로 변환
                for (const [userId, participant] of Object.entries(participants)) {
                    const stream = participant.rtcPeer?.getRemoteStream?.();
                    if (stream && stream.getAudioTracks().length > 0) {
                        // 트랙 활성화 상태 확인
                        const audioTracks = stream.getAudioTracks();
                        const hasActiveTracks = audioTracks.some(track =>
                            track.enabled && track.readyState === 'live'
                        );

                        if (hasActiveTracks) {
                            self.audioMixer.addAudioStream(userId, stream);
                            console.log(`AudioMixer에 추가: ${userId}`);
                        } else {
                            console.log(`AudioMixer 제외 (비활성화): ${userId}`);
                        }
                    }
                }
            }

            // 로컬 오디오도 추가
            if (typeof localStream !== 'undefined' && localStream) {
                // 로컬 오디오 활성화 상태 확인
                const audioTracks = localStream.getAudioTracks();
                const localUserId = typeof ParticipantUtils !== 'undefined'
                    ? ParticipantUtils.getLocalUserId()
                    : null;

                // ParticipantUtils가 있으면 상태를 확인, 없으면 트랙만 확인
                let shouldAddLocal = false;

                if (localUserId && typeof ParticipantUtils !== 'undefined') {
                    const audioState = ParticipantUtils.getAudioState(localUserId);
                    shouldAddLocal = audioState.enabled && audioTracks.some(track =>
                        track.enabled && track.readyState === 'live'
                    );
                } else {
                    // ParticipantUtils가 없으면 트랙 상태만 확인
                    shouldAddLocal = audioTracks.some(track =>
                        track.enabled && track.readyState === 'live'
                    );
                }

                if (shouldAddLocal) {
                    self.audioMixer.addAudioStream('local', localStream);
                    console.log('로컬 오디오를 AudioMixer에 추가 (활성화됨)');
                } else {
                    console.warn('로컬 오디오가 비활성화되어 있어서 녹화에서 제외됨');
                }
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
            self.updateUI('idle');

            // 4. DataChannel로 녹화 중지 알림
            dataChannel?.sendMessage?.('recordingStopped', 'recording');

            // 5. 자막 기능 재활성화
            speechRecognitionUtils?.handlingSubtitleByRecording?.(false);

            console.log('녹화 중지 완료');
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
     * UI 업데이트
     */
    updateUI: function (state) {
        let self = this;

        switch (state) {
            case 'recording':
                // 녹화 시작 버튼 비활성화
                self.$recordingStartBtn.prop('disabled', true);
                self.$recordingStartBtn.attr('src', 'images/webrtc/recording/record-start-disabled.svg');
                self.$recordingStartBtn.css({
                    'pointer-events': 'none',
                    'cursor': 'not-allowed',
                    'opacity': '0.5',
                    'user-select': 'none'
                });

                // 녹화 중지 버튼 활성화
                self.$recordingStopBtn.prop('disabled', false);
                self.$recordingStopBtn.attr('src', 'images/webrtc/recording/record-stop.svg');
                self.$recordingStopBtn.css({
                    'pointer-events': 'auto',
                    'cursor': 'pointer',
                    'opacity': '1',
                    'user-select': 'none'
                });
                break;

            case 'idle':
                // 녹화 시작 버튼 활성화
                self.$recordingStartBtn.prop('disabled', false);
                self.$recordingStartBtn.attr('src', 'images/webrtc/recording/record-start.svg');
                self.$recordingStartBtn.css({
                    'pointer-events': 'auto',
                    'cursor': 'pointer',
                    'opacity': '1',
                    'user-select': 'none'
                });

                // 녹화 중지 버튼 비활성화
                self.$recordingStopBtn.prop('disabled', true);
                self.$recordingStopBtn.attr('src', 'images/webrtc/recording/record-stop-disabled.svg');
                self.$recordingStopBtn.css({
                    'pointer-events': 'none',
                    'cursor': 'not-allowed',
                    'opacity': '0.5',
                    'user-select': 'none'
                });
                break;

            case 'disabled':
                // 다른 사용자가 녹화 중일 때: 모든 버튼 완전 비활성화
                self.$recordingStartBtn.prop('disabled', true);
                self.$recordingStartBtn.attr('src', 'images/webrtc/recording/record-start-disabled.svg');
                self.$recordingStartBtn.css({
                    'pointer-events': 'none',
                    'cursor': 'not-allowed',
                    'opacity': '0.5',
                    'user-select': 'none'
                });

                self.$recordingStopBtn.prop('disabled', true);
                self.$recordingStopBtn.attr('src', 'images/webrtc/recording/record-stop-disabled.svg');
                self.$recordingStopBtn.css({
                    'pointer-events': 'none',
                    'cursor': 'not-allowed',
                    'opacity': '0.5',
                    'user-select': 'none'
                });
                break;

            case 'enabled':
                // 녹화 시작 버튼 활성화
                self.$recordingStartBtn.prop('disabled', false);
                self.$recordingStartBtn.attr('src', 'images/webrtc/recording/record-start.svg');
                self.$recordingStartBtn.css({
                    'pointer-events': 'auto',
                    'cursor': 'pointer',
                    'opacity': '1',
                    'user-select': 'none'
                });

                // 녹화 중지 버튼 비활성화
                self.$recordingStopBtn.prop('disabled', true);
                self.$recordingStopBtn.attr('src', 'images/webrtc/recording/record-stop-disabled.svg');
                self.$recordingStopBtn.css({
                    'pointer-events': 'none',
                    'cursor': 'not-allowed',
                    'opacity': '0.5',
                    'user-select': 'none'
                });
                break;
        }
    },

    /**
     * 다른 사용자의 녹화 이벤트 처리
     */
    handlingRecordingEvent: function (recordingUser, eventType) {
        let self = this;
        let recordingEvent = eventType === 'recordingStarted';

        // 녹화 알림
        let recordingToast = recordingUser + '님이 녹화를 ' + (recordingEvent ? '시작' : '중지') + '했습니다';
        self.showToast(recordingToast);

        // 다른 사용자의 이벤트인 경우
        if (nickName !== recordingUser) {
            if (eventType === 'recordingStarted') {
                self.isOtherUserRecording = true;
                self.updateUI('disabled');

                // 자막 기능 비활성화
                speechRecognitionUtils?.handlingSubtitleByRecording?.(true);

            } else if (eventType === 'recordingStopped') {
                self.isOtherUserRecording = false;
                self.updateUI('enabled');

                // 자막 기능 재활성화
                speechRecognitionUtils?.handlingSubtitleByRecording?.(false);
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
    }
};
