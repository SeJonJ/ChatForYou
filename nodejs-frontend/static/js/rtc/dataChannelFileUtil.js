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
        var file = $('#file')[0].files[0];
        if (!file) {
            console.log('No file chosen');
        }

        var formData = new FormData();
        formData.append('file', file);
        formData.append('roomId', roomId);

        // 확장자 추출
        var fileDot = file.name.lastIndexOf('.');

        // 확장자 검사
        var fileType = file.name.substring(fileDot + 1, file.name.length);
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

            var fileData = {
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
        // console.log("파일 이름 : "+name);
        // console.log("파일 경로 : " + path);
        if(!bucket || !name || !path){
            this.showToast('다운로드 정보가 없습니다.');
            return;
        }

        let url = window.__CONFIG__.API_BASE_URL + '/file/download';
        let data = {
            "roomId" : roomId,
            "bucket" : bucket,
            "fileName": name,
            "filePath": path // 파일의 경로를 파라미터로 넣는다.
        };

        let successCallback = function (data) {
            var link = document.createElement('a');
            link.href = URL.createObjectURL(data);
            link.download = name;
            link.click();
        };

        let errorCallback = function (error) {

        };

        fileDownloadAjax(url, 'POST', '', data, successCallback, errorCallback);
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