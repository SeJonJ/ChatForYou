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

/**
 * 참가자 관련 유틸리티 함수들을 관리하는 객체
 */
const ParticipantUtils = {
	// 참가자 클래스 상수들
	PARTICIPANT_MAIN_CLASS: 'participant main',
	PARTICIPANT_CLASS: 'participant',

	// 오디오 상태 추적 객체
	audioStates: {},

	/**
	 * 오디오 상태를 초기화합니다
	 * @param {string} userId - 사용자 ID
	 */
	initAudioState: function(userId) {
		this.audioStates[userId] = {
			enabled: true,
			volume: 0.5  // 기본 볼륨을 0.5 (중간값)로 설정
		};
	},

	/**
	 * 오디오 상태를 업데이트합니다
	 * @param {string} userId - 사용자 ID
	 * @param {boolean} enabled - 오디오 활성화 여부
	 * @param {number} volume - 볼륨 레벨 (선택사항)
	 */
	updateAudioState: function(userId, enabled, volume) {
		if (!this.audioStates[userId]) {
			this.initAudioState(userId);
		}
		this.audioStates[userId].enabled = enabled;
		if (volume !== undefined) {
			this.audioStates[userId].volume = volume;
		}
	},

	/**
	 * 오디오 상태를 가져옵니다
	 * @param {string} userId - 사용자 ID
	 * @return {Object} 오디오 상태 객체
	 */
	getAudioState: function(userId) {
		if (!this.audioStates[userId]) {
			this.initAudioState(userId);
		}
		return this.audioStates[userId];
	},

	/**
	 * 볼륨 슬라이더를 활성화/비활성화합니다
	 * @param {string} userId - 사용자 ID
	 * @param {boolean} enabled - 활성화 여부
	 */
	toggleVolumeSlider: function(userId, enabled) {
		const volumeSlider = document.getElementById('volumeControl_' + userId);
		if (volumeSlider) {
			volumeSlider.disabled = !enabled;
			volumeSlider.style.opacity = enabled ? '1' : '0.5';
		}

		// 모달 내 볼륨 슬라이더도 업데이트
		const modalVolumeSlider = document.querySelector('#participantsList #volumeControl_' + userId);
		if (modalVolumeSlider) {
			modalVolumeSlider.disabled = !enabled;
			modalVolumeSlider.style.opacity = enabled ? '1' : '0.5';
		}
	},

	/**
	 * 오디오 버튼들을 동기화합니다
	 * @param {string} userId - 사용자 ID
	 * @param {boolean} enabled - 오디오 활성화 여부
	 */
	syncAudioButtons: function(userId, enabled) {
		const state = this.getAudioState(userId);
		const localUser = this.getLocalUserId();
		
		if (userId === localUser) {
			// 내 오디오 버튼 동기화
			const localAudioBtn = $('.localAudioToggle');
			localAudioBtn.data('flag', enabled);
			localAudioBtn.attr('src', enabled ? '/images/webrtc/audio-speaker-on.svg' : '/images/webrtc/audio-speaker-off.svg');

			// 모달 내 내 오디오 버튼도 동기화
			const modalAudioBtn = $('#audioBtn_' + userId);
			if (modalAudioBtn.length) {
				modalAudioBtn.data('flag', enabled);
				modalAudioBtn.attr('src', enabled ? '/images/webrtc/audio-speaker-on.svg' : '/images/webrtc/audio-speaker-off.svg');
			}
		}

		// 볼륨 슬라이더 상태 업데이트
		this.toggleVolumeSlider(userId, enabled);
	},

	/**
	 * 로컬 사용자 ID를 가져옵니다
	 * @return {string} 로컬 사용자 ID
	 */
	getLocalUserId: function() {
		const mainParticipant = document.getElementsByClassName(this.PARTICIPANT_MAIN_CLASS)[0];
		return mainParticipant ? mainParticipant.id : null;
	},

	/**
	 * 참가자 컨테이너에 음량 조절 컨트롤을 추가합니다
	 * @param {HTMLElement} container - 참가자 컨테이너 요소
	 * @param {string} userId - 사용자 ID
	 */
	addVolumeControl: function(container, userId) {
		// 오디오 상태 초기화
		this.initAudioState(userId);

		// 복제하고자 하는 요소의 ID
		const originalElement = $('#volumeControl');

		// 요소를 복제합니다. jQuery 객체에서 DOM 엘리먼트를 가져옵니다.
		const volumeControl = originalElement[0].cloneNode(true); // 'true'를 추가하여 자식 노드도 복제합니다.

		volumeControl.type = 'range';
		volumeControl.value = 0.5; // 기본 볼륨을 0.5로 설정

		// 복제된 요소의 ID를 변경 :: 고유한 ID 부여
		volumeControl.id = 'volumeControl_' + userId;

		// 복제된 요소에 사용자 이름을 설정합니다.
		volumeControl.setAttribute('data-userId', userId);

		volumeControl.onchange = function(event) {
			let targetUserId = this.getAttribute('data-userId');
			const volumeLevel = parseFloat(this.value);
			
			// 오디오 상태 확인
			const audioState = ParticipantUtils.getAudioState(targetUserId);
			if (!audioState.enabled) {
				// 오디오가 꺼져있으면 볼륨 조절 불가
				this.value = audioState.volume;
				return;
			}

			// 볼륨 조절 적용
			ParticipantUtils.updateAudioState(targetUserId, true, volumeLevel);
			participants[targetUserId].setVolume(volumeLevel);

			// 녹화 중이면 AudioMixer도 동기화
			if (recording?.isRecording) {
				if (recording?.audioMixer?.gainNodes?.has(targetUserId)) {
					recording.audioMixer.setVolume(targetUserId, volumeLevel);
					console.log(`AudioMixer 볼륨 조절: ${targetUserId} = ${volumeLevel}`);
				}
			}

			// 모달 내 슬라이더와 동기화
			const modalSlider = document.querySelector('#participantsList #volumeControl_' + targetUserId);
			if (modalSlider && modalSlider !== this) {
				modalSlider.value = volumeLevel;
			}
		};

		// 복제된 요소를 해당 위치에 추가합니다.
		container.appendChild(volumeControl);
	},

	/**
	 * 참가자 수에 따라 그리드 레이아웃을 업데이트합니다
	 */
	updateGridLayout: function() {
		let participantsDiv = document.getElementById('participants');
		let totalParticipants = participantsDiv.childElementCount;

		// Remove all layout classes
		['one', 'two', 'three'].forEach(function(cls) {
			participantsDiv.classList.remove(cls);
		});

		// Assign the appropriate layout class
		if (totalParticipants === 1) {
			participantsDiv.classList.add('one');
		} else if (totalParticipants === 2) {
			participantsDiv.classList.add('two');
		} else if (totalParticipants === 3) {
			participantsDiv.classList.add('three');
		}
	}
};

/**
 * 새로운 참가자를 위한 비디오 요소를 생성합니다
 * 
 * @param {String} userId - 새 참가자의 userId, 비디오 요소의 태그 userId로 사용됩니다.
 *                        새 요소의 태그는 'video<userId>'가 됩니다.
 * @param {String} nickName - 참가자의 닉네임
 * @return {Participant} 생성된 참가자 객체
 */
function Participant(userId, nickName, roomId) {
	//console.log("참여자명 : "+userId)
	this.userId = userId;
	this.nickName = nickName;
	this.roomId = roomId;

	let rtcPeer = null;
	let localStream = null; // 유저의 로컬 스트림
	let container = document.createElement('div');

	/**
	 * 현재 참가자가 메인 참가자인지 확인합니다
	 * @return {boolean} 메인 참가자 여부
	 */
	let isMainParticipant = function(){
		return (($("#"+ParticipantUtils.PARTICIPANT_MAIN_CLASS)).length === 0);
	}
	container.className = isMainParticipant() ? ParticipantUtils.PARTICIPANT_MAIN_CLASS : ParticipantUtils.PARTICIPANT_CLASS;
	container.id = userId;

	let span = document.createElement('span');
	let video = document.createElement('video');
	let audio  = document.createElement("audio");

	container.appendChild(video);
	container.appendChild(span);
	container.appendChild(audio);
	ParticipantUtils.addVolumeControl(container, userId);

	// container.onclick = switchContainerClass;
	// document.getElementById('participants').appendChild(container);
	$('#participants').append(container);
	ParticipantUtils.updateGridLayout();

	span.appendChild(document.createTextNode(nickName));

	video.id = 'video-' + userId;
	video.autoplay = true;
	video.playsInline = true; // iOS에서 전체화면 방지

	// 로컬 사용자는 에코 방지를 위해 비디오 음소거, 원격은 음소거 해제
	video.muted = isMainParticipant(); // 로컬만 음소거

	// 비디오 요소 스타일 설정 (표시 보장)
	video.style.width = '100%';
	video.style.height = '100%';
	video.style.objectFit = 'cover';
	video.style.backgroundColor = '#000'; // 디버깅용

	// controls는 제거 - 자동 일시정지 방지
	audio.autoplay = true;
	// 로컬 사용자는 에코 방지를 위해 오디오 음소거, 원격은 음소거 해제
	audio.muted = isMainParticipant(); // 로컬만 음소거

	// 초기 볼륨 설정
	// 원격 참가자는 초기 볼륨 0.5로 설정 - kurento-service.js에서 1.0으로 덮어쓰임
	// 로컬 사용자는 에코 방지를 위해 0 유지
	if (!isMainParticipant()) {
		audio.volume = 0.5;
		video.volume = 0.5;
		// 원격 오디오는 자동재생 정책을 위해 초기에는 muted=false 유지
		// onaddstream 핸들러에서 srcObject 할당 후 음소거 해제
	} else {
		// 로컬 사용자는 에코 방지
		audio.volume = 0;
		video.volume = 0;
	}

	/**
	 * 사용자의 로컬 스트림을 설정합니다
	 * @param {MediaStream} stream - 설정할 미디어 스트림
	 */
	this.setLocalStream = function(stream){
		localStream = stream;
	}

	/**
	 * 사용자의 로컬 스트림을 반환합니다
	 * @return {MediaStream} 로컬 미디어 스트림
	 */
	this.getLocalStream = function(){
		return localStream;
	}

	/**
	 * 참가자 컨테이너 요소를 반환합니다
	 * @return {HTMLElement} 컨테이너 요소
	 */
	this.getElement = function() {
		return container;
	}

	/**
	 * 비디오 요소를 반환합니다
	 * @return {HTMLVideoElement} 비디오 요소
	 */
	this.getVideoElement = function() {
		return video;
	}

	/**
	 * 오디오 요소를 반환합니다
	 * @return {HTMLAudioElement} 오디오 요소
	 */
	this.getAudioElement = function() {
		return audio;
	}

	/**
	 * 비디오 수신을 위한 SDP offer를 처리합니다
	 * @param {Error} error - 에러 객체
	 * @param {string} offerSdp - SDP offer 문자열
	 * @param {Object} wp - WebRTC peer 객체
	 */
	this.offerToReceiveVideo = function(error, offerSdp, wp){
		if (error) return console.error ("sdp offer error")
		//console.log('Invoking SDP offer callback function');
		let msg =  {
			event : "RECEIVE_VIDEO_FROM",
			roomId : roomId,
			senderId : userId,
			senderNickName : nickName,
			sdpOffer : offerSdp
		};
		sendMessageToServer(msg);
	}

	/**
	 * ICE candidate 이벤트를 처리합니다
	 * @param {RTCIceCandidate} candidate - ICE candidate 객체
	 * @param {Object} wp - WebRTC peer 객체
	 */
	this.onIceCandidate = function (candidate, wp) {
		//console.log("Local candidate" + JSON.stringify(candidate));
		let message = {
			event: 'ON_ICE_CANDIDATE',
			roomId : roomId,
			candidate: candidate,
			senderId: userId,
			senderNickName : nickName
		};
		sendMessageToServer(message);
	}

	Object.defineProperty(this, 'rtcPeer', { writable: true});

	/**
	 * 참가자 객체를 정리하고 DOM에서 제거합니다
	 */
	this.dispose = function() {
		console.log('Disposing participant ' + this.userId);

		try {
			// 1. 미디어 요소의 srcObject 정리
			if (video) {
				try {
					video.pause();
					video.srcObject = null;  // 스트림 참조 해제
					video.load();  // 정리 신호
				} catch (e) {
					console.error('비디오 정리 중 에러:', e);
				}
			}

			if (audio) {
				try {
					audio.pause();
					audio.srcObject = null;  // 스트림 참조 해제
					audio.load();  // 정리 신호
				} catch (e) {
					console.error('오디오 정리 중 에러:', e);
				}
			}

			// 2. 로컬 스트림 정리
			if (localStream) {
				try {
					localStream.getTracks().forEach(function(track) {
						try {
							track.stop();
							track.enabled = false;
						} catch (trackError) {
							console.error('트랙 정리 중 에러:', trackError);
						}
					});
				} catch (streamError) {
					console.error('스트림 정리 중 에러:', streamError);
				}
			}

			// 3. RTC Peer 정리
			if (this.rtcPeer) {
				try {
					this.rtcPeer.dispose();
				} catch (peerError) {
					console.error('Peer 정리 중 에러:', peerError);
				}
			}

			// 4. 컨테이너 DOM 제거
			if (container && container.parentNode) {
				try {
					container.parentNode.removeChild(container);
				} catch (domError) {
					console.error('DOM 제거 중 에러:', domError);
				}
			}

			console.log('참가자 정리 완료: ' + this.userId);
		} catch (error) {
			console.error('참가자 정리 중 예상치 못한 에러:', error);
		}
	};

	/**
	 * 참가자의 음량을 설정합니다
	 * @param {number} volumeLevel - 0.0에서 1.0 사이의 음량 레벨
	 */
	this.setVolume = function(volumeLevel) {
		let audioElement = this.getAudioElement();
		let videoElement = this.getVideoElement();
		if (audioElement && videoElement) {
			audioElement.volume = volumeLevel;
			videoElement.volume = volumeLevel;
		}
	};

	/**
	 * 로컬 사용자의 ID를 반환합니다
	 * @return {string} 로컬 사용자 ID
	 */
	this.getLocalUser = function(){
		return document.getElementsByClassName(ParticipantUtils.PARTICIPANT_MAIN_CLASS)[0].id;
	}
}

// ==============================================
// jQuery 이벤트 리스너들 - 전역 변수 의존성으로 인해 수정하지 않음
// ==============================================

/**
 * 로컬 비디오 토글 기능 - 비디오 on/off
 * 주의: 전역 변수 participants, userId에 의존
 */
$(".localVideoToggle").on("click", function(){
	let videoBtn = $('.localVideoToggle');
	let isVideo = videoBtn.data("flag");
	let videoTrack = participants[userId].rtcPeer.getLocalStream().getTracks().filter(track => track.kind === 'video')[0];

	if (isVideo) { // 비디오가 사용중이라면 비디오 off
		videoTrack.enabled = false;
		// videoBtn.val("Video On");
		videoBtn.data("flag", false);
		videoBtn.attr("src", "/images/webrtc/video-off.svg")
	} else {
		videoTrack.enabled = true;
		// videoBtn.val("Video Off");
		videoBtn.data("flag", true);
		videoBtn.attr("src", "/images/webrtc/video-on.svg")
	}
});

/**
 * 로컬 오디오 토글 기능 - 오디오 on/off
 * 주의: 전역 변수 participants, userId에 의존
 */
$(".localAudioToggle").on("click", function(){
	let audioBtn = $(".localAudioToggle");
	let useAudio = audioBtn.data("flag");
	let audioTrack = participants[userId].rtcPeer.getLocalStream().getTracks().filter(track => track.kind === 'audio')[0];

	if (useAudio) { // 오디오가 사용중이라면 오디오 off
		audioTrack.enabled = false;
		ParticipantUtils.updateAudioState(userId, false);
		audioBtn.data("flag", false);
		audioBtn.attr("src", "/images/webrtc/audio-speaker-off.svg");
	} else {
		audioTrack.enabled = true;
		ParticipantUtils.updateAudioState(userId, true);
		audioBtn.data("flag", true);
		audioBtn.attr("src", "/images/webrtc/audio-speaker-on.svg");
	}

	// 오디오 버튼들과 볼륨 슬라이더 동기화
	ParticipantUtils.syncAudioButtons(userId, !useAudio);
});

/**
 * 사용자 설정 모달 기능 - 참가자 목록과 각 참가자의 비디오/오디오/음량 컨트롤
 * 주의: 전역 변수 participants에 의존
 */
$('#userSetting').on('click', function (e) {
	let participantsList = $('#participantsList');
	participantsList.empty(); // 기존 목록을 비웁니다.

	// participants 객체를 반복하여 각 참가자에 대한 정보를 목록에 추가합니다.
	$.each(participants, function (userId, participant) {
		let listItem = $('<li class="list-group-item d-flex justify-content-between align-items-center"></li>');
		let localUser = participant.getLocalUser(); // 로컬 user 의 id 확인

		// 볼륨 조절 슬라이더의 ID
		let volumeSliderId = 'volumeControl_' + userId;
		// 기존의 볼륨 컨트롤을 찾아서 복사합니다.
		let existingVolumeSlider = $('#' + volumeSliderId);
		// 기존의 볼륨 컨트롤이 있으면 복사하여 사용합니다.
		let volumeSlider = existingVolumeSlider.clone();

		// 현재 오디오 상태에 따라 볼륨 슬라이더 설정
		let audioState = ParticipantUtils.getAudioState(userId);
		volumeSlider[0].disabled = !audioState.enabled;
		volumeSlider[0].style.opacity = audioState.enabled ? '1' : '0.5';
		volumeSlider[0].value = audioState.volume;

		// 모달 내 볼륨 슬라이더 이벤트 추가
		volumeSlider.on('input change', function() {
			let targetUserId = userId;
			let volumeLevel = parseFloat(this.value);
			
			// 오디오 상태 확인
			let currentAudioState = ParticipantUtils.getAudioState(targetUserId);
			if (!currentAudioState.enabled) {
				// 오디오가 꺼져있으면 볼륨 조절 불가
				this.value = currentAudioState.volume;
				return;
			}

			// 볼륨 조절 적용
			ParticipantUtils.updateAudioState(targetUserId, true, volumeLevel);
			participants[targetUserId].setVolume(volumeLevel);

			// 녹화 중이면 AudioMixer도 동기화
			if (recording?.isRecording) {
				if (recording?.audioMixer?.gainNodes?.has(targetUserId)) {
					recording.audioMixer.setVolume(targetUserId, volumeLevel);
					console.log(`AudioMixer 볼륨 조절 (모달): ${targetUserId} = ${volumeLevel}`);
				}
			}

			// 메인 화면의 볼륨 슬라이더와 동기화
			let mainVolumeSlider = document.getElementById('volumeControl_' + targetUserId);
			if (mainVolumeSlider) {
				mainVolumeSlider.value = volumeLevel;
			}
		});

		// 비디오 및 오디오 컨트롤 버튼 복사 및 ID 수정
		let videoButtonId = 'videoBtn_' + userId;
		let audioButtonId = 'audioBtn_' + userId;

		if (localUser === userId) { // 사용자 본인의 video, audio 설정
			listItem.text('You');

			let videoButton = $('#videoBtn').clone(true).attr('id', videoButtonId);
			let audioButton = $('#audioBtn').clone(true).attr('id', audioButtonId);

			videoButton.removeClass('col-md-1');
			audioButton.removeClass('col-md-1');

			// 내 오디오 버튼 상태 동기화
			audioButton.data('flag', audioState.enabled);
			audioButton.attr('src', audioState.enabled ? '/images/webrtc/audio-speaker-on.svg' : '/images/webrtc/audio-speaker-off.svg');

			// 내 오디오 버튼 이벤트 추가
			audioButton.off('click').on('click', function() {
				let currentState = $(this).data('flag');
				let audioTrack = participants[userId].rtcPeer.getLocalStream().getTracks().filter(track => track.kind === 'audio')[0];

				if (currentState) {
					audioTrack.enabled = false;
					ParticipantUtils.updateAudioState(userId, false);
					$(this).data('flag', false);
					$(this).attr('src', '/images/webrtc/audio-speaker-off.svg');

					// 녹화 중이면 AudioMixer도 뮤트
					if (recording?.isRecording) {
						recording?.audioMixer?.setMuted('local', true);
						console.log('AudioMixer 로컬 오디오 뮤트');
					}
				} else {
					audioTrack.enabled = true;
					ParticipantUtils.updateAudioState(userId, true);
					$(this).data('flag', true);
					$(this).attr('src', '/images/webrtc/audio-speaker-on.svg');

					// 녹화 중이면 AudioMixer도 언뮤트
					if (recording?.isRecording) {
						recording?.audioMixer?.setMuted('local', false);
						console.log('AudioMixer 로컬 오디오 언뮤트');
					}
				}

				// 메인 화면 버튼과 볼륨 슬라이더 동기화
				ParticipantUtils.syncAudioButtons(userId, !currentState);

				// 모달 내 볼륨 슬라이더 상태도 업데이트
				volumeSlider[0].disabled = currentState;
				volumeSlider[0].style.opacity = currentState ? '0.5' : '1';
			});

			listItem.append(videoButton, audioButton, volumeSlider);
		} else { // 다른 유저의 video, audio 설정
			listItem.text(participant.nickName);

			// 비디오 컨트롤 버튼 clone 및 이벤트 할당
			let remoteVideoButton = $('#videoBtn').clone().attr('id', videoButtonId);
			// localVideoToggle class 삭제
			remoteVideoButton.removeClass('localVideoToggle');
			// col-md-1 삭제
			remoteVideoButton.removeClass('col-md-1')
			// 클릭 이벤트 할당
			remoteVideoButton.click(function(){
				let useRemoteVideo = remoteVideoButton.data('flag')
				// 비디오 트랙만 가져오기
				let videoTrack = participant.rtcPeer.getRemoteStream().getTracks().filter(track => track.kind === 'video')[0];

				if (useRemoteVideo) { // 비디오가 사용중이라면 비디오 off : enabled = false
					videoTrack.enabled = false;
					// remoteVideoButton.val("Video On");
					remoteVideoButton.data('flag', false);
					remoteVideoButton.attr('src', '/images/webrtc/video-off.svg')
				} else {
					videoTrack.enabled = true;
					// remoteVideoButton.val("Video Off");
					remoteVideoButton.data('flag', true);
					remoteVideoButton.attr('src', '/images/webrtc/video-on.svg')
				}
			})

			// 다른 참여자(유저)의 오디오 컨트롤 버튼 clone 및 이벤트 할당
			let remoteAudioButton = $('#audioBtn').clone().attr('id', audioButtonId);
			// localAudioToggle class 삭제
			remoteAudioButton.removeClass('localAudioToggle');
			// col-md-1 삭제
			remoteAudioButton.removeClass('col-md-1');

			// 상대방 오디오 버튼 상태 동기화
			remoteAudioButton.data('flag', audioState.enabled);
			remoteAudioButton.attr('src', audioState.enabled ? '/images/webrtc/audio-speaker-on.svg' : '/images/webrtc/audio-speaker-off.svg');

			// 클릭 이벤트 할당
			remoteAudioButton.click(function(){
				let useRemoteAudio = remoteAudioButton.data('flag')
				// 오디오 트랙만 가져오기
				let audioTrack = participant.rtcPeer.getRemoteStream().getTracks().filter(track => track.kind === 'audio')[0];

				if (useRemoteAudio) { // 오디오가 사용중이라면 오디오 off : enabled = false
					audioTrack.enabled = false;
					ParticipantUtils.updateAudioState(userId, false);
					remoteAudioButton.data('flag', false);
					remoteAudioButton.attr('src', '/images/webrtc/audio-speaker-off.svg');

					// Bug #3 수정: 녹화 중이면 AudioMixer도 뮤트
					if (recording?.isRecording && recording?.audioMixer?.gainNodes?.has(userId)) {
						recording.audioMixer.setMuted(userId, true);
						console.log(`AudioMixer 원격 오디오 뮤트: ${userId}`);
					}

					// 볼륨 슬라이더 비활성화
					volumeSlider[0].disabled = true;
					volumeSlider[0].style.opacity = '0.5';
				} else {
					audioTrack.enabled = true;
					ParticipantUtils.updateAudioState(userId, true);
					remoteAudioButton.data('flag', true);
					remoteAudioButton.attr('src', '/images/webrtc/audio-speaker-on.svg');

					// Bug #3 수정: 녹화 중이면 AudioMixer도 언뮤트
					if (recording?.isRecording && recording?.audioMixer?.gainNodes?.has(userId)) {
						recording.audioMixer.setMuted(userId, false);
						console.log(`AudioMixer 원격 오디오 언뮤트: ${userId}`);
					}

					// 볼륨 슬라이더 활성화
					volumeSlider[0].disabled = false;
					volumeSlider[0].style.opacity = '1';
				}

				// 메인 화면의 볼륨 슬라이더도 동기화
				ParticipantUtils.toggleVolumeSlider(userId, !useRemoteAudio);
			})

			listItem.append(remoteVideoButton, remoteAudioButton, volumeSlider);
		}

		participantsList.append(listItem);
	});
});