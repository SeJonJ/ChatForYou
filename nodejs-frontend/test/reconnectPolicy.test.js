const test = require('node:test');
const assert = require('node:assert/strict');

const ReconnectPolicy = require('../static/js/rtc/reconnectPolicy.js');

function createTrack() {
    return { enabled: true };
}

function createStream(audioCount, videoCount) {
    const audioTracks = Array.from({ length: audioCount }, createTrack);
    const videoTracks = Array.from({ length: videoCount }, createTrack);
    return {
        getAudioTracks: () => audioTracks,
        getVideoTracks: () => videoTracks
    };
}

test('서버가 잠시 후 재시도하라고 보낸 사유는 재시도 대상이다', () => {
    ['CLAIM_IN_PROGRESS', 'CURRENT_COOKIE_UNAVAILABLE', 'OWNER_COOKIE_UNAVAILABLE', 'INSTANCE_SHUTTING_DOWN']
        .forEach((reason) => {
            assert.equal(ReconnectPolicy.isRetryableRecoveryReason(reason), true);
        });
});

test('복구 불가 사유와 빈 값은 재시도 대상이 아니다', () => {
    [undefined, null, '', 'NOT_RECOVERABLE', 'RECOVERY_EXPIRED', 'ROOM_NOT_FOUND']
        .forEach((reason) => {
            assert.equal(ReconnectPolicy.isRetryableRecoveryReason(reason), false);
        });
});

test('빠른 재시도는 상한 전까지만 계속하고 상한에 닿으면 재연결 루프로 넘긴다', () => {
    assert.equal(ReconnectPolicy.decideRecoveryRetry(0, 3), 'RETRY');
    assert.equal(ReconnectPolicy.decideRecoveryRetry(2, 3), 'RETRY');
    assert.equal(ReconnectPolicy.decideRecoveryRetry(3, 3), 'HAND_OFF');
    assert.equal(ReconnectPolicy.decideRecoveryRetry(4, 3), 'HAND_OFF');
});

test('다른 서버로 이동하라는 응답은 재연결 중이면 재연결, 첫 입장이면 새로고침이다', () => {
    assert.equal(ReconnectPolicy.decideRedirectRoomAction(true), 'RECONNECT');
    assert.equal(ReconnectPolicy.decideRedirectRoomAction(false), 'RELOAD');
});

test('방 정보 조회 실패는 재연결 중일 때만 재연결로 수렴한다', () => {
    assert.equal(ReconnectPolicy.decideRegisterFailureAction(true), 'RECONNECT');
    assert.equal(ReconnectPolicy.decideRegisterFailureAction(false), 'KEEP');
});

test('토글 상태가 없으면 켜짐으로 본다', () => {
    assert.equal(ReconnectPolicy.resolveMediaFlag(undefined), true);
    assert.equal(ReconnectPolicy.resolveMediaFlag(true), true);
    assert.equal(ReconnectPolicy.resolveMediaFlag(false), false);
});

test('음소거·카메라 끔 상태가 새 트랙에 그대로 적용된다', () => {
    const stream = createStream(1, 1);

    ReconnectPolicy.applyLocalMediaStates(stream, false, false);

    assert.equal(stream.getAudioTracks()[0].enabled, false);
    assert.equal(stream.getVideoTracks()[0].enabled, false);
});

test('오디오만 끈 상태에서는 비디오 트랙이 켜진 채로 남는다', () => {
    const stream = createStream(2, 1);

    ReconnectPolicy.applyLocalMediaStates(stream, false, true);

    stream.getAudioTracks().forEach((track) => assert.equal(track.enabled, false));
    assert.equal(stream.getVideoTracks()[0].enabled, true);
});

test('비디오 트랙이 없는 스트림과 빈 스트림에서도 예외가 나지 않는다', () => {
    const audioOnly = createStream(1, 0);

    ReconnectPolicy.applyLocalMediaStates(audioOnly, false, false);
    assert.equal(audioOnly.getAudioTracks()[0].enabled, false);

    assert.doesNotThrow(() => ReconnectPolicy.applyLocalMediaStates(null, false, false));
    assert.doesNotThrow(() => ReconnectPolicy.applyLocalMediaStates({}, false, false));
});
