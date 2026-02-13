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
 * AudioMixer - 여러 오디오 스트림을 믹싱하는 모듈
 * WebRTC 녹화 시 여러 참가자의 오디오를 하나로 합치는 역할
 */
const AudioMixer = {
    // 상태 속성
    audioContext: null,
    audioSources: null, // Map 객체 (userId -> MediaStreamAudioSourceNode)
    gainNodes: null, // Map 객체 (userId -> GainNode)
    destination: null,
    mixedStream: null,
    isActive: false,

    /**
     * 오디오 컨텍스트 초기화
     */
    init: function () {
        let self = this;

        try {
            // Map 초기화
            self.audioSources = new Map();
            self.gainNodes = new Map();

            // AudioContext 생성
            const AudioContext = window.AudioContext || window.webkitAudioContext;
            self.audioContext = new AudioContext();

            // Destination 노드 생성 (믹싱된 오디오 출력)
            self.destination = self.audioContext.createMediaStreamDestination();
            self.mixedStream = self.destination.stream;

            console.log('AudioMixer 초기화 완료');
            console.log('Sample Rate:', self.audioContext.sampleRate);

        } catch (error) {
            console.error('AudioMixer 초기화 실패:', error);
            throw error;
        }
    },

    /**
     * 오디오 스트림 추가
     * @param {string} userId - 사용자 ID
     * @param {MediaStream} stream - 오디오 스트림
     * @param {number} volume - 초기 볼륨 (0.0 ~ 1.0, 기본값 1.0)
     */
    addAudioStream: function (userId, stream, volume) {
        let self = this;
        volume = volume !== undefined ? volume : 1.0;

        if (!stream || !stream.getAudioTracks || stream.getAudioTracks().length === 0) {
            console.warn('유효한 오디오 트랙이 없습니다:', userId);
            return false;
        }

        try {
            // 이미 추가된 스트림이면 제거 후 재추가
            if (self.audioSources.has(userId)) {
                self.removeAudioStream(userId);
            }

            // AudioContext가 suspended 상태면 resume
            if (self.audioContext.state === 'suspended') {
                self.audioContext.resume();
            }

            // MediaStreamSource 생성
            const source = self.audioContext.createMediaStreamSource(stream);

            // GainNode 생성 (볼륨 조절용)
            const gainNode = self.audioContext.createGain();
            gainNode.gain.value = volume;

            // 노드 연결: Source -> Gain -> Destination
            source.connect(gainNode);
            gainNode.connect(self.destination);

            // 맵에 저장
            self.audioSources.set(userId, source);
            self.gainNodes.set(userId, gainNode);

            console.log(`오디오 스트림 추가: ${userId}, 볼륨: ${volume}`);
            self.isActive = true;

            return true;

        } catch (error) {
            console.error(`오디오 스트림 추가 실패 (${userId}):`, error);
            return false;
        }
    },

    /**
     * 오디오 스트림 제거
     * @param {string} userId - 사용자 ID
     */
    removeAudioStream: function (userId) {
        let self = this;

        try {
            // Source 노드 연결 해제
            if (self.audioSources.has(userId)) {
                const source = self.audioSources.get(userId);
                source.disconnect();
                self.audioSources.delete(userId);
            }

            // Gain 노드 연결 해제
            if (self.gainNodes.has(userId)) {
                const gainNode = self.gainNodes.get(userId);
                gainNode.disconnect();
                self.gainNodes.delete(userId);
            }

            console.log(`오디오 스트림 제거: ${userId}`);

            // 모든 스트림이 제거되면 비활성화
            if (self.audioSources.size === 0) {
                self.isActive = false;
            }

            return true;

        } catch (error) {
            console.error(`오디오 스트림 제거 실패 (${userId}):`, error);
            return false;
        }
    },

    /**
     * 특정 사용자의 볼륨 조절
     * @param {string} userId - 사용자 ID
     * @param {number} volume - 볼륨 값 (0.0 ~ 1.0)
     */
    setVolume: function (userId, volume) {
        let self = this;

        if (!self.gainNodes.has(userId)) {
            console.warn('해당 사용자의 오디오 스트림을 찾을 수 없습니다:', userId);
            return false;
        }

        try {
            const gainNode = self.gainNodes.get(userId);
            // 급격한 볼륨 변화 방지를 위해 ramp 사용
            gainNode.gain.setValueAtTime(gainNode.gain.value, self.audioContext.currentTime);
            gainNode.gain.linearRampToValueAtTime(volume, self.audioContext.currentTime + 0.1);

            console.log(`볼륨 조절: ${userId}, ${volume}`);
            return true;

        } catch (error) {
            console.error(`볼륨 조절 실패 (${userId}):`, error);
            return false;
        }
    },

    /**
     * 모든 오디오 볼륨 조절
     * @param {number} volume - 볼륨 값 (0.0 ~ 1.0)
     */
    setMasterVolume: function (volume) {
        let self = this;

        for (const userId of self.gainNodes.keys()) {
            self.setVolume(userId, volume);
        }
    },

    /**
     * 특정 사용자 음소거
     * @param {string} userId - 사용자 ID
     * @param {boolean} muted - 음소거 여부
     */
    setMuted: function (userId, muted) {
        let self = this;
        return self.setVolume(userId, muted ? 0 : 1);
    },

    /**
     * 믹싱된 오디오 스트림 반환
     * @returns {MediaStream} 믹싱된 오디오 스트림
     */
    getMixedStream: function () {
        let self = this;
        return self.mixedStream;
    },

    /**
     * 현재 믹싱 중인 오디오 소스 개수
     * @returns {number}
     */
    getSourceCount: function () {
        let self = this;
        return self.audioSources.size;
    },

    /**
     * 모든 오디오 스트림 제거 및 리소스 정리
     */
    clear: function () {
        let self = this;

        try {
            // 모든 소스 제거
            for (const userId of Array.from(self.audioSources.keys())) {
                self.removeAudioStream(userId);
            }

            console.log('AudioMixer 클리어 완료');
            self.isActive = false;

        } catch (error) {
            console.error('AudioMixer 클리어 실패:', error);
        }
    },

    /**
     * AudioContext 닫기 및 완전 종료
     */
    dispose: async function () {
        let self = this;

        try {
            self.clear();

            if (self.audioContext && self.audioContext.state !== 'closed') {
                await self.audioContext.close();
                console.log('AudioContext 종료됨');
            }

            self.audioContext = null;
            self.destination = null;
            self.mixedStream = null;

        } catch (error) {
            console.error('AudioMixer dispose 실패:', error);
        }
    },

    /**
     * 현재 상태 정보 반환
     */
    getStatus: function () {
        let self = this;

        return {
            isActive: self.isActive,
            sourceCount: self.audioSources.size,
            contextState: self.audioContext ? self.audioContext.state : 'closed',
            sampleRate: self.audioContext ? self.audioContext.sampleRate : 0,
            sources: Array.from(self.audioSources.keys())
        };
    },

    /**
     * 디버그 정보 출력
     */
    debug: function () {
        let self = this;

        console.log('=== AudioMixer Debug Info ===');
        console.log('Status:', self.getStatus());
        console.log('Audio Sources:', Array.from(self.audioSources.keys()));
        console.log('Gain Nodes:', Array.from(self.gainNodes.keys()));

        for (const [userId, gainNode] of self.gainNodes) {
            console.log(`- ${userId}: volume = ${gainNode.gain.value}`);
        }

        console.log('===========================');
    }
};

// 전역 인스턴스 (싱글톤 패턴)
let audioMixerInstance = null;

/**
 * AudioMixer 인스턴스 가져오기 (싱글톤)
 */
function getAudioMixer() {
    if (!audioMixerInstance) {
        // 객체를 복사하여 새 인스턴스 생성
        audioMixerInstance = Object.create(AudioMixer);
        // 초기화 호출
        audioMixerInstance.init();
    }
    return audioMixerInstance;
}

/**
 * AudioMixer 인스턴스 재생성
 */
function resetAudioMixer() {
    if (audioMixerInstance) {
        audioMixerInstance.dispose();
    }
    audioMixerInstance = Object.create(AudioMixer);
    // 초기화 호출
    audioMixerInstance.init();
    return audioMixerInstance;
}

// 전역 스코프에 노출
window.AudioMixer = AudioMixer;
window.getAudioMixer = getAudioMixer;
window.resetAudioMixer = resetAudioMixer;
