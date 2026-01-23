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
 * SystemAudioCapture - 화면 공유 시 시스템 오디오를 캡처하는 모듈
 * getDisplayMedia API를 사용하여 화면과 시스템 오디오를 함께 캡처
 */

/**
 * 미디어 에러 메시지 맵
 */
const MEDIA_ERROR_MESSAGES = {
    'NotAllowedError': '화면 공유 권한이 거부되었습니다.',
    'NotFoundError': '공유할 화면을 찾을 수 없습니다.',
    'NotReadableError': '화면을 캡처할 수 없습니다. 다른 애플리케이션에서 사용 중일 수 있습니다.'
};

const SystemAudioCapture = {
    // 상태 속성
    displayStream: null,
    systemAudioStream: null,
    isCapturing: false,
    onStopCallback: null,

    /**
     * 미디어 에러를 사용자 친화적 메시지로 변환 
     * @private
     * @param {Error} error - 원본 에러 객체
     * @throws {Error} 변환된 에러 메시지
     */
    _translateMediaError: function(error) {
        let message = MEDIA_ERROR_MESSAGES[error.name];
        if (message) {
            throw new Error(message);
        }
        throw error;
    },

    /**
     * 스트림의 모든 트랙 중지 및 정리
     * @private
     * @param {MediaStream} stream - 중지할 스트림
     */
    _stopStreamTracks: function(stream) {
        if (!stream) return;
        
        stream.getTracks().forEach(function(track) {
            track.stop();
        });
    },

    /**
     * 특정 타입의 트랙만 추출 
     * @private
     * @param {string} trackType - 'audio' 또는 'video'
     * @returns {MediaStream|null}
     */
    _extractTrackStream: function(trackType) {
        let self = this;
        
        if (!self.displayStream) {
            return null;
        }
        
        let tracks = trackType === 'audio' 
            ? self.displayStream.getAudioTracks()
            : self.displayStream.getVideoTracks();
        
        if (tracks.length === 0) {
            return null;
        }
        
        return new MediaStream(tracks);
    },

    /**
     * 시스템 오디오를 포함한 화면 공유 시작
     * @param {Object} options - 캡처 옵션
     * @param {boolean} options.audio - 오디오 캡처 여부 (기본값: true)
     * @param {boolean} options.video - 비디오 캡처 여부 (기본값: true)
     * @param {number} options.width - 비디오 너비
     * @param {number} options.height - 비디오 높이
     * @param {number} options.frameRate - 프레임 레이트
     * @returns {Promise<MediaStream>}
     */
    startCapture: async function (options = {}) {
        let self = this;

        if (!SystemAudioCapture.isSupported()) {
            throw new Error('이 브라우저는 화면 공유를 지원하지 않습니다.');
        }

        if (self.isCapturing) {
            console.warn('이미 화면 공유가 진행 중입니다.');
            return self.displayStream;
        }

        try {
            // 기본 옵션 설정
            const defaultOptions = {
                audio: {
                    echoCancellation: true,
                    noiseSuppression: true,
                    sampleRate: 44100
                },
                video: {
                    width: { ideal: 1920 },
                    height: { ideal: 1080 },
                    frameRate: { ideal: 30 }
                }
            };

            // 사용자 옵션 병합
            const captureOptions = {
                audio: options.audio !== false ? defaultOptions.audio : false,
                video: options.video !== false ? {
                    ...defaultOptions.video,
                    ...(options.width && { width: { ideal: options.width } }),
                    ...(options.height && { height: { ideal: options.height } }),
                    ...(options.frameRate && { frameRate: { ideal: options.frameRate } })
                } : false
            };

            // 화면 공유 시작
            self.displayStream = await navigator.mediaDevices.getDisplayMedia(captureOptions);

            // 시스템 오디오 트랙 추출
            const audioTracks = self.displayStream.getAudioTracks();
            if (audioTracks.length > 0) {
                self.systemAudioStream = new MediaStream(audioTracks);
                console.log('시스템 오디오 캡처 시작:', audioTracks[0].label);
            } else {
                console.warn('시스템 오디오를 캡처하지 못했습니다. 사용자가 오디오 공유를 허용하지 않았을 수 있습니다.');
            }

            // 화면 공유 중지 이벤트 리스너 등록
            self.displayStream.getVideoTracks().forEach(function(track) {
                track.addEventListener('ended', function() {
                    self.handleStreamEnded();
                });
            });

            self.isCapturing = true;
            console.log('화면 공유 시작:', self.displayStream.id);

            return self.displayStream;

        } catch (error) {
            console.error('화면 공유 시작 실패:', error);
            self._translateMediaError(error);
        }
    },

    /**
     * 화면 공유 중지
     */
    stopCapture: function () {
        let self = this;

        if (!self.isCapturing) {
            console.warn('현재 화면 공유 중이 아닙니다.');
            return;
        }

        try {
            self._stopStreamTracks(self.displayStream);
            self._stopStreamTracks(self.systemAudioStream);
            
            self.displayStream = null;
            self.systemAudioStream = null;
            self.isCapturing = false;
            
            console.log('화면 공유 중지됨');
        } catch (error) {
            console.error('화면 공유 중지 실패:', error);
        }
    },

    /**
     * 스트림 종료 이벤트 처리
     */
    handleStreamEnded: function () {
        let self = this;

        console.log('화면 공유가 사용자에 의해 중지되었습니다.');
        self.isCapturing = false;

        // 콜백 호출
        if (typeof self.onStopCallback === 'function') {
            self.onStopCallback();
        }
    },

    /**
     * 화면 공유 중지 시 콜백 등록
     * @param {Function} callback
     */
    onStop: function (callback) {
        let self = this;
        self.onStopCallback = callback;
    },

    /**
     * 화면 공유 스트림 반환
     * @returns {MediaStream|null}
     */
    getDisplayStream: function () {
        let self = this;
        return self.displayStream;
    },

    /**
     * 시스템 오디오 스트림 반환
     * @returns {MediaStream|null}
     */
    getSystemAudioStream: function () {
        let self = this;
        return self.systemAudioStream;
    },

    /**
     * 시스템 오디오만 반환 (오디오 트랙만 포함)
     * @returns {MediaStream|null}
     */
    getAudioOnlyStream: function () {
        let self = this;
        return self._extractTrackStream('audio');
    },

    /**
     * 비디오만 반환 (비디오 트랙만 포함)
     * @returns {MediaStream|null}
     */
    getVideoOnlyStream: function () {
        let self = this;
        return self._extractTrackStream('video');
    },

    /**
     * 현재 캡처 중인지 확인
     * @returns {boolean}
     */
    isActive: function () {
        let self = this;
        return self.isCapturing;
    },

    /**
     * 캡처 상태 정보 반환
     * @returns {Object}
     */
    getStatus: function () {
        let self = this;

        const status = {
            isCapturing: self.isCapturing,
            hasDisplayStream: !!self.displayStream,
            hasSystemAudio: !!self.systemAudioStream,
            videoTracks: 0,
            audioTracks: 0
        };

        if (self.displayStream) {
            status.videoTracks = self.displayStream.getVideoTracks().length;
            status.audioTracks = self.displayStream.getAudioTracks().length;

            // 트랙 상세 정보
            const videoTrack = self.displayStream.getVideoTracks()[0];
            const audioTrack = self.displayStream.getAudioTracks()[0];

            if (videoTrack) {
                status.videoTrackInfo = {
                    label: videoTrack.label,
                    enabled: videoTrack.enabled,
                    muted: videoTrack.muted,
                    readyState: videoTrack.readyState,
                    settings: videoTrack.getSettings()
                };
            }

            if (audioTrack) {
                status.audioTrackInfo = {
                    label: audioTrack.label,
                    enabled: audioTrack.enabled,
                    muted: audioTrack.muted,
                    readyState: audioTrack.readyState,
                    settings: audioTrack.getSettings()
                };
            }
        }

        return status;
    },

    /**
     * 디버그 정보 출력
     */
    debug: function () {
        let self = this;

        console.log('=== SystemAudioCapture Debug Info ===');
        console.log(self.getStatus());
        console.log('====================================');
    }
};

/**
 * 브라우저 지원 여부 확인 (static 메서드를 객체 외부 함수로)
 */
SystemAudioCapture.isSupported = function () {
    return navigator.mediaDevices &&
           typeof navigator.mediaDevices.getDisplayMedia === 'function';
};

// 전역 인스턴스 (싱글톤 패턴)
let systemAudioCaptureInstance = null;

/**
 * SystemAudioCapture 인스턴스 가져오기 (싱글톤)
 */
function getSystemAudioCapture() {
    if (!systemAudioCaptureInstance) {
        // 객체를 복사하여 새 인스턴스 생성
        systemAudioCaptureInstance = Object.create(SystemAudioCapture);
        // 속성 초기화
        systemAudioCaptureInstance.displayStream = null;
        systemAudioCaptureInstance.systemAudioStream = null;
        systemAudioCaptureInstance.isCapturing = false;
        systemAudioCaptureInstance.onStopCallback = null;
    }
    return systemAudioCaptureInstance;
}

/**
 * SystemAudioCapture 인스턴스 재생성
 */
function resetSystemAudioCapture() {
    if (systemAudioCaptureInstance) {
        systemAudioCaptureInstance.stopCapture();
    }
    systemAudioCaptureInstance = Object.create(SystemAudioCapture);
    // 속성 초기화
    systemAudioCaptureInstance.displayStream = null;
    systemAudioCaptureInstance.systemAudioStream = null;
    systemAudioCaptureInstance.isCapturing = false;
    systemAudioCaptureInstance.onStopCallback = null;
    return systemAudioCaptureInstance;
}

// 전역 스코프에 노출
window.SystemAudioCapture = SystemAudioCapture;
window.getSystemAudioCapture = getSystemAudioCapture;
window.resetSystemAudioCapture = resetSystemAudioCapture;
