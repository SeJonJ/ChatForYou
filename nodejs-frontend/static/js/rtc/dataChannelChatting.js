/*
* dataChannel 채팅을 위한 js
* */
const dataChannelChatting = {
    $element: $('.floating-chat'),
    $sendMessageBtn : $('#sendMessageBtn'),
    $userTextInput :  $('#userTextInput'),
    $messagesContainer : $('.messages'),
    isCheckMinioPage : false,
    init: function() {
        const self = this; // 'self' 변수에 'this' 값을 할당
        var myStorage = localStorage;

        if (!myStorage.getItem('chatID')) {
            myStorage.setItem('chatID', self.createUUID());
        }

        setTimeout(function() {
            self.$element.addClass('enter');
        }, 1000);

        self.$element.click(self.openElement);

        self.$userTextInput.on('keydown', function(event) {
            if (event.shiftKey && event.which === 13) {
                // shift + enter 사용 시 한줄 띄우기
            } else if (event.which === 13 && self.$userTextInput.text().trim() !== '') {
                event.preventDefault(); // 기본 동작(한줄 띄우기)을 방지
                dataChannel.showNewMessage(self.parseMessage(self.$userTextInput), 'self');
            }
        });

        self.$sendMessageBtn.on("click", function(){
            if(self.$userTextInput.text().trim() === ''){
                return;
            }
            dataChannel.showNewMessage(self.parseMessage(self.$userTextInput), 'self');
        });

    },
    openElement: function() {
        const self = dataChannelChatting;
        if(!self.isCheckMinioPage){

        }
        var messages = self.$element.find('.messages');
        self.$element.find('>i').hide();
        self.$element.addClass('expand');
        self.$element.find('.chat').addClass('enter');
        self.$element.off('click', self.openElement);
        self.$element.find('.header button').click(self.closeElement);
        messages.scrollTop(messages.prop("scrollHeight"));
    },
    closeElement: function() {
        const self = dataChannelChatting;
        self.$element.find('.chat').removeClass('enter').hide();
        self.$element.find('>i').show();
        self.$element.removeClass('expand');
        self.$element.find('.header button').off('click', self.closeElement);
        setTimeout(function() {
            self.$element.find('.chat').removeClass('enter').show();
            self.$element.click(self.openElement);
        }, 500);
    },
    createUUID : function() {
        var s = [];
        var hexDigits = "0123456789abcdef";
        for (var i = 0; i < 36; i++) {
            s[i] = hexDigits.substr(Math.floor(Math.random() * 0x10), 1);
        }
        s[14] = "4"; // bits 12-15 of the time_hi_and_version field to 0010
        s[19] = hexDigits.substr((s[19] & 0x3) | 0x8, 1); // bits 6-7 of the clock_seq_hi_and_reserved to 01
        s[8] = s[13] = s[18] = s[23] = "-";

        var uuid = s.join("");
        return uuid;
    },
    parseMessage: function($userTextInput){
        return $userTextInput.html()
            .replace(/\<div\>|\<br.*?\>/ig, '\n').replace(/\<\/div\>/g, '')
            .trim()
            .replace(/\n/g, '<br>');
    },

    /**
     * 녹화 링크 메시지를 채팅창에 표시
     * @param {Object} file - 녹화 파일 정보 (userName, downloadUrl, fileSizeMB)
     * @param {string} type - 메시지 타입 ('self' 또는 'other')
     */
    showNewRecordingLinkMessage: function(file, type) {
        const self = this;

        // 메시지 컨테이너
        var contentElement = $('<li>').addClass(type).addClass('recording-link-message');

        // 발신자 정보 (other인 경우에만)
        if (type === 'other' && file.userName) {
            var senderElement = $('<div>', {
                class: 'recording-sender',
                style: 'font-size: 12px; color: #666; margin-bottom: 4px;'
            }).text(file.userName + ' 님이 녹화 파일을 공유했습니다');
            contentElement.append(senderElement);
        } else if (type === 'self') {
            var senderElement = $('<div>', {
                class: 'recording-sender',
                style: 'font-size: 12px; color: #666; margin-bottom: 4px;'
            }).text('녹화 파일이 준비되었습니다');
            contentElement.append(senderElement);
        }

        // 녹화 정보 컨테이너
        var infoContainer = $('<div>', {
            class: 'recording-info-container',
            style: 'display: flex; align-items: center; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); ' +
                   'padding: 12px; border-radius: 8px; color: white;'
        });

        // 비디오 아이콘
        var iconElement = $('<i>', {
            class: 'fas fa-video',
            style: 'font-size: 24px; margin-right: 12px;'
        });
        infoContainer.append(iconElement);

        // 파일 정보 텍스트
        var textContainer = $('<div>', {
            style: 'flex: 1;'
        });
        var titleElement = $('<div>', {
            style: 'font-weight: bold; font-size: 14px; margin-bottom: 2px;'
        }).text('녹화 파일');
        var sizeElement = $('<div>', {
            style: 'font-size: 12px; opacity: 0.9;'
        }).text('크기: ' + (file.fileSizeMB || 0) + ' MB');
        textContainer.append(titleElement, sizeElement);
        infoContainer.append(textContainer);

        // 다운로드 버튼
        var downloadBtn = $('<button>', {
            class: 'btn recording-download-chat-btn',
            style: 'background: white; color: #667eea; border: none; padding: 8px 16px; ' +
                   'border-radius: 6px; font-weight: bold; cursor: pointer; font-size: 12px;'
        }).html('<i class="fas fa-download" style="margin-right: 4px;"></i>다운로드');

        // 다운로드 버튼 클릭 이벤트
        downloadBtn.on('click', function() {
            if (!file.downloadUrl) {
                console.error('[Chat] 다운로드 URL이 없습니다.');
                return;
            }

            console.log('[Chat] 녹화 파일 다운로드 시작:', file.downloadUrl);

            // JavaScript를 통한 강제 다운로드
            var link = document.createElement('a');
            link.href = file.downloadUrl;
            link.download = 'recording_' + new Date().getTime() + '.webm';
            link.style.display = 'none';
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);

            console.log('[Chat] 다운로드 요청 완료');
        });

        infoContainer.append(downloadBtn);
        contentElement.append(infoContainer);

        // 채팅창에 추가
        self.$messagesContainer.append(contentElement);

        // 스크롤을 아래로
        self.$messagesContainer.scrollTop(self.$messagesContainer.prop("scrollHeight"));

        console.log('[Chat] 녹화 링크 메시지 표시 완료:', type);
    }
}