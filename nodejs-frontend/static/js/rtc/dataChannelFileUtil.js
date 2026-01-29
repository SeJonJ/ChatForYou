/**
 * DataChannel 로 file 다루기 위한 util js
 */
const dataChannelFileUtil = {
    isinit: false,
    allowFileExt : ['jpg', 'png', 'jpeg', 'gif'],
    init : function(){
        let self = this;
        if (!self.isinit) {
            $('#uploadFile').on('click', function () {
                $('#file').click();
            });

            $('#file').on('change', function(){
                // 파일선택으로 change 되면 실행
                self.uploadFile();
            })

            self.isinit = true;
        }

    },
    uploadFile : function(){

        // 파일 확인
        let file = $('#file')[0].files[0];
        if (!file) {
            console.log('No file chosen');
        }

        let formData = new FormData();
        formData.append('file', file);
        formData.append('roomId', roomId);

        // 확장자 추출
        let fileDot = file.name.lastIndexOf('.');

        // 확장자 검사
        let fileType = file.name.substring(fileDot + 1, file.name.length);
        // console.log('type : ' + fileType);

        if(!this.allowFileExt.includes(fileType)){
            this.showToast('파일 업로드는 png, jpg, gif, jpeg 만 가능합니다');
            return;
        }

        let successCallback = function (data) {

            // console.log('업로드 성공')
            if (data.status === 'FAIL') {
                alert('서버와의 연결 문제로 파일 업로드에 실패했습니다 \n 잠시 후 다시 시도해주세요')
                return;
            }

            let fileData = {
                type: 'file',
                'roomId': roomId,
                fileMeta: data
            };

            dataChannel.sendFileMessage(fileData);

        };

        let errorCallback = function (error) {
            let errorJson = error?.responseJSON;
            if (!errorJson) {
                alert('파일 업로드 용량 또는 파일 확장자를 확인해주세요 \n 확장자 : jpg, jepg, png, gif \n 용량 제한 : Max 10MB');
                return;
            }
            if (errorJson?.code === '40022') {
                alert('업로드는 jpg, jepg, png, gif 파일 만 가능합니다');
            }
        };

        // 2. 서버에 파일 전송
        fileUploadAjax(window.__CONFIG__.API_BASE_URL + '/file/upload', 'POST', true, formData, successCallback, errorCallback);

    },
    downloadFile : function ({
        bucket,
        name,
        path
    } = {}) {
        let self = this;

        if(!bucket || !name || !path){
            self.showToast('다운로드 정보가 없습니다.');
            return;
        }

        let apiUrl = window.__CONFIG__.API_BASE_URL + '/file/download';
        let data = {
            "roomId" : roomId,
            "bucket" : bucket,
            "fileName": name,
            "filePath": path
        };

        let successCallback = function (blobData) {
            // Electron 환경 감지
            if (window.electronAPI && window.electronAPI.downloadFile) {
                console.log('[FileUtil] Electron 환경 감지 - IPC 다운로드 사용');

                // Blob을 ArrayBuffer로 변환하여 IPC로 전송
                blobData.arrayBuffer().then(function(buffer) {
                    let uint8Array = new Uint8Array(buffer);

                    window.electronAPI.downloadFile(Array.from(uint8Array), name)
                        .then(function(result) {
                            if (result.success) {
                                console.log('[FileUtil] 파일 저장 완료:', result.path);
                                self.showToast('파일이 다운로드되었습니다.\n저장 위치: Downloads 폴더');
                            } else {
                                console.error('[FileUtil] 파일 저장 실패:', result.error);
                                self.showToast('파일 다운로드에 실패했습니다: ' + result.error);
                            }
                        })
                        .catch(function(error) {
                            console.error('[FileUtil] IPC 다운로드 오류:', error);
                            self.showToast('파일 다운로드에 실패했습니다.');
                        });
                }).catch(function(error) {
                    console.error('[FileUtil] ArrayBuffer 변환 실패:', error);
                    self.showToast('파일 처리에 실패했습니다.');
                });

            } else {
                // 웹 브라우저 환경 - 기존 방식 사용
                console.log('[FileUtil] 웹 브라우저 환경 - blob URL 다운로드 사용');

                let link = document.createElement('a');
                link.href = URL.createObjectURL(blobData);
                link.download = name;
                link.click();

                // 메모리 정리
                setTimeout(function() {
                    URL.revokeObjectURL(link.href);
                }, 100);
            }
        };

        let errorCallback = function (error) {
            console.error('[FileUtil] 파일 다운로드 요청 실패:', error);
            self.showToast('파일 다운로드에 실패했습니다.');
        };

        fileDownloadAjax(apiUrl, 'POST', '', data, successCallback, errorCallback);
    },
    showToast : function(message) {
        Toastify({
            text: message,
            duration: 4000,
            newWindow: true,
            close: true,
            gravity: "top",
            position: "center",
            style: {
                background: "linear-gradient(to right, #FF6B6B, #FFE66D)",
            },
        }).showToast();
    }
}