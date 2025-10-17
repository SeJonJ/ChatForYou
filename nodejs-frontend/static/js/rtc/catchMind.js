/*
* Catch Mind 게임을 구현하기 위한 js
* 1. 게임 준비(마스터) : 게임 시작 시 주제 선택 후 선택된 주제 전체 인원에게 전달
* 2. 게임 준비(참여자) : 게임 마스터가 아닌 경우 게임 준비 상태 필요
* 3. 게임 시작 : 마스터는 캔버스화면, 참여자도 캔버스화면
* 4. 캔버스 초기화 : 캔버스 초기화 및 30 초 추가, 전체에게 해당 이벤트 전달
* 5. 정답 이벤트 : 정답 맞췄을 때 캔버스 초기화 및 다른 사람에게 참여자 이벤트
* 6. 게임 종료 이벤트 : 총 N번의 게임 후 전체 게임 종료
* */

const catchMind = {
    isInit: false,
    canvas: null,
    ctx: null,
    title: '', // 선택된 대주제
    subject: '', // 선택된 주제
    nickName: '', // 게임 닉네임
    isGameLeader: false, // 게임 진행자 여부
    isGameParticipant: false, // 게임 참여자 여부
    isGameStart: false, // 게임 시작 여부
    isGameReady: false, // 게임 준비 여부
    gameReadyUser: 0, // 게임준비를 누른 유저 수
    gameUserCount: 1, // 게임 참여자 수
    gameUserList: [], // 게임 유저 정보(리스트)
    totalGameRound: 3, // 고정값
    gameRound: 1,
    timerBar: null,
    timerId: null,
    drawing: false, // 그리기 상태를 추적하는 변수
    mouseInit: false,
    lastX: 0,
    lastY: 0,
    saveX: 0,
    saveY: 0,
    totalTime: 60, // 라운드 그림 시간 제한
    isTimeRemain: false, // 라운드 남은 시간
    maxClearCount: 3, // 캔버스 클리어 최대 횟수
    recognition: null, // 음성 인식 객체
    synth: null,
    alreadyPlayedGame: false, // 게임 이미 진행되었는지 여부
    init: function () {
        this.canvas = document.getElementById('mycanvas');
        this.ctx = this.canvas.getContext('2d');
        if (!this.isInit) {
            if (isMobile()) {
                this.initMobileCanvasEvents();
            } else {
                this.initCanvasEvent();
            }
            this.initClickEvent();

            // SpeechRecognition 인터페이스 확인
            // window.SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
            this.recognition = new webkitSpeechRecognition() || new SpeechRecognition();
            this.synth = window.speechSynthesis;

            // 게임 관련 변수 초기화
            this.gameReadyUser = 0 // 게임준비를 누른 유저 수
            this.gameUserList = [] // 게임 유저 정보(리스트)

            // 로그인한 사용자 닉네임으로 설정
            this.nickName = localStorage.getItem('nickName');

            this.isInit = true;
        }
    },
    initCanvasEvent: function () {
        let self = this;
        // 마우스 움직일 때
        self.canvas.addEventListener('mousemove', function (e) {
            if (self.drawing && self.isGameStart) {
                self.ctx.beginPath();
                self.ctx.moveTo(self.lastX, self.lastY);
                self.setMousePosition(e);
                self.ctx.lineTo(self.lastX, self.lastY);
                self.ctx.stroke();

                // console.log("x pos : ", lastX + " ::::: " + "y pos : ", lastY);

                const pos = {
                    "gameEvent": "mouseEvent",
                    "mouseX": self.lastX,
                    "mouseY": self.lastY
                }

                dataChannel.sendMessage(pos, 'gameEvent');
            }
        });

        // 마우스 누를 때
        self.canvas.addEventListener('mousedown', function (e) {
            if (self.isTimeRemain) { // 게임 시간이 남아있다면
                self.drawing = true;
                self.setMousePosition(e);
                const pos = {
                    "gameEvent": "mouseEvent",
                    "mouseInit": true
                }

                dataChannel.sendMessage(pos, 'gameEvent');
            }
        });

        // 마우스 뗄 때와 캔버스 밖으로 나갈 때
        self.canvas.addEventListener('mouseup', function () {
            self.drawing = false;
        });
        self.canvas.addEventListener('mouseout', function () {
            self.drawing = false;
        });

        $('#answerBtn').text('Tell Your Answer!');
    },
    initMobileCanvasEvents: function () {
        let self = this;

        // 터치로 그리기 시작
        self.canvas.addEventListener('touchstart', function (e) {
            e.preventDefault();  // 기본 터치 스크롤 방지
            if (self.isTimeRemain) {
                let touch = e.touches[0];
                self.setMousePosition(touch);  // 터치 위치 설정
                self.drawing = true;

                const pos = {
                    "gameEvent": "mouseEvent",
                    "mouseInit": true,
                    "mouseX": self.lastX,
                    "mouseY": self.lastY
                };
                dataChannel.sendMessage(pos, 'gameEvent');
            }
        }, { passive: false });

        // 터치로 그리기
        self.canvas.addEventListener('touchmove', function (e) {
            e.preventDefault(); // 기본 터치 스크롤 방지
            if (self.drawing && self.isGameStart) {
                let touch = e.touches[0];
                self.ctx.beginPath();
                self.ctx.moveTo(self.lastX, self.lastY);
                self.setMousePosition(touch);
                self.ctx.lineTo(self.lastX, self.lastY);
                self.ctx.stroke();

                const pos = {
                    "gameEvent": "mouseEvent",
                    "mouseX": self.lastX,
                    "mouseY": self.lastY
                };
                dataChannel.sendMessage(pos, 'gameEvent');
            }
        }, { passive: false });

        // 터치 끝
        self.canvas.addEventListener('touchend', function (e) {
            self.drawing = false;
        });

        self.canvas.addEventListener('touchcancel', function (e) {
            self.drawing = false;
        });

        $('#answerBtn').text('Type Your Answer!');
    },
    setMousePosition: function (e) {
        // 정리 필요!!
        let rect = this.canvas.getBoundingClientRect();
        if (e.clientX) {
            this.lastX = e.clientX - rect.left;
            this.lastY = e.clientY - rect.top;
        } else if (e.touches) {
            this.lastX = e.touches[0].clientX - rect.left;
            this.lastY = e.touches[0].clientY - rect.top;
        }
    },
    canvasDrawingEvent: function (event) {

        let mouseX = event.mouseX;
        let mouseY = event.mouseY;

        if (event.mouseInit) {
            this.saveX = 0;
            this.saveY = 0;
            return;
        }

        if (this.saveX === 0 && this.saveY === 0) {
            this.saveX = mouseX;
            this.saveY = mouseY;
        }

        this.ctx.beginPath();
        this.ctx.moveTo(this.saveX, this.saveY); // 시작점 설정

        // console.log("x pos : ", this.lastX + " ::::: "+"y pos : ", this.lastY);

        this.ctx.lineTo(mouseX, mouseY); // 끝점 설정 (여기서는 시작점에서 조금 떨어진 위치로 설정)
        this.ctx.stroke(); // 선 그리기

        this.saveX = mouseX;
        this.saveY = mouseY;
    },
    initClickEvent: function () {
        let self = this;

        $('#clearCanvasBtn').off('click').on('click', function () {
            // 버튼 로딩 시작
            spinnerOpt.init();
            spinnerOpt.start(this);

            if (self.maxClearCount <= 0) {
                self.showToast("더 이상 캔버스 초기화가 불가능해요!!");
            }

            self.clearCanvas();

            const clearCanvasEvent = {
                "gameEvent": "clearCanvas"
            };
            dataChannel.sendMessage(clearCanvasEvent, 'gameEvent');
            // 버튼 로딩 종료
            spinnerOpt.stop();
        });

        $('#subjectModal').on('shown.bs.modal', function (e) {
            if(self.alreadyPlayedGame) {
                self.showToast("이미 게임이 진행되었습니다!");
                return;
            }
            $('#maxGameCount').val(self.totalGameRound);
            // 로그인 사용자의 nickName 으로 설정
            $('#nickName_ld').val(self.nickName);
            // 닉네임 수정 불가능하도록 설정
            $('#nickName_ld').attr('disabled', true);

            let $body = $('body');
            spinnerOpt.initByOption(20, 15, 4.0, 'shrink', '#ffffff', '50%', '50%');
            spinnerOpt.start($body);

            let $titleButtonContainer = $('#titleButtonContainer');
            let $subjectButtonContainer = $('#subjectButtonContainer');

            $titleButtonContainer.empty(); // 버튼 넣기 전 한번 비우기
            $subjectButtonContainer.empty();
            $titleButtonContainer.removeClass('d-none');

            let url = window.__CONFIG__.API_BASE_URL + `/catchmind/titles?roomId=${roomId}`;

            let successCallback = function (data) {
                let titles = data.titles;
                // 배열 순회
                $.each(titles, function (index, title) {
                    // 버튼 생성
                    let button = $('<button>', {
                        class: 'btn btn-outline-primary title-btn',
                        text: title, // 버튼 내용으로 title 사용
                        'data-title': title, // data-title 속성 설정
                        value: title // value 속성 설정
                    });

                    // 스피너 중지
                    spinnerOpt.stop();

                    // 생성된 버튼을 컨테이너에 추가
                    $titleButtonContainer.append(button);
                });

                $('#startBtn').attr('disabled', false);
            };

            let errorCallback = function (data) {
                self.preventGameEvent();
                let result = data?.responseJSON;
                console.error("error :: ", result);
                alert(result.message);
                spinnerOpt.stop();
                return false;
            };

            ajax(url, "GET", true, '', successCallback, errorCallback);

        });

        $('#titleButtonContainer').off('click').on('click', '.title-btn', function () {

            let $subjectButtonContainer = $('#subjectButtonContainer');
            let $titleButtonContainer = $('#titleButtonContainer');
            $titleButtonContainer.addClass('d-none');
            $subjectButtonContainer.removeClass('d-none');

            spinnerOpt.init();
            spinnerOpt.start($subjectButtonContainer);

            self.title = $(this).attr('data-title');

            let url = window.__CONFIG__.API_BASE_URL +`/catchmind/subjects?roomId=${roomId}`;
            const data = {
                "title": self.title
            }

            let successCallback = function (data) {
                $subjectButtonContainer.empty();
                let subjects = data.subjects;
                // 배열 순회
                $.each(subjects, function (index, subject) {
                    // 앞에 AI : 혹은 AI: 제거
                    subject = subject.replace(/AI\s*:\s*/g, "");
                    // 버튼 생성
                    let button = $('<button>', {
                        class: 'btn btn-outline-primary subject-btn',
                        text: subject, // 버튼 내용으로 subject 사용
                        'data-subject': subject, // data-subject 속성 설정
                        value: subject // value 속성 설정
                    });

                    // 스피너 중지
                    spinnerOpt.stop();

                    // 생성된 버튼을 컨테이너에 추가
                    $subjectButtonContainer.append(button);
                });
                $('#readyBtn').prop('disabled', false);
            };

            let errorCallback = function () {
                // 스피너 중지
                spinnerOpt.stop();
            };

            ajaxToJson(url, 'POST', true, data, successCallback, errorCallback);

        });

        $('#subjectButtonContainer').off('click').on('click', '.subject-btn', function () {
            console.log('data ::: ', $(this).attr('data-subject'));

            $('.subject-btn').removeClass('active');
            $(this).addClass('active');
            // self.subject = $(this).attr('data-subject');
            let data = $(this).attr('data-subject');
            self.subject = self.replaceStr(data);
        });

        // game ready btn
        $('#readyBtn').off('click').on('click', function () {
            if (!self.title || !self.subject) {
                alert("게임 주제를 선정 후 시작하실 수 있습니다!");
                return;
            }
            self.setGameUser(); // 게임 참여 가능 인원 세팅
            if (!self.isGameParticipant && !self.isGameReady && !self.isGameStart) {
                self.isGameLeader = true;
                self.isGameReady = true;
            }

            if (self.isGameLeader) {

                let $nickName = $('#nickName_ld').val();
                if (!$nickName) {
                    alert("게임 닉네임은 필수값입니다!");
                    return;
                }

                self.nickName = $nickName;
                // self.addGameParticipant();
                // dataChannel.sendMessage("addParticipant", "gameEvent");
                const newGame = {
                    "gameEvent": "newGame",
                    "newTitle": self.title,
                    "newSubject": self.subject
                }
                dataChannel.sendMessage(newGame, 'gameEvent');

                self.addGameReady('self');

                const addReadyUser = {
                    "gameEvent": "addReadyUser",
                    "gameUser": self.nickName,
                    "nickName": self.nickName
                }
                dataChannel.sendMessage(addReadyUser, 'gameEvent');
                self.sendGameRequest();
                self.gameReadyToast('leader');

                $('#readyBtn').hide();
                $('#exitBtn').hide();
                $('#startBtn').removeAttr('hidden');

            } else if (self.isGameParticipant) {
                self.addGameReady('self');
                const addReadyUser = {
                    "gameEvent": "addReadyUser",
                    "gameUser": userId,
                    "nickName": self.nickName
                }
                dataChannel.sendMessage(addReadyUser, 'gameEvent');

                // 'GAME READY' 버튼 숨기기
                $("#readyBtn").hide();
                // 로딩 인디케이터 표시
                $("#loadingIndicator").show();
            }

        });

        // '예' 버튼 클릭 이벤트 핸들러
        $('#acceptGameRequest').off('click').on('click', function () {
            // 게임 참여 수락 처리 로직
            self.isGameParticipant = true;
            // 게임 참여 가능한 총 인원 세팅(현재 방 인원과 동일)
            self.setGameUser();
            // dataChannel.sendMessage("addParticipant", "gameEvent");

            // 게임 방법 & 팁 탭을 기본적으로 표시
            $('#gameSubject').removeClass('show active'); // 주제 선택 탭 내용 숨김
            $('#gameTip').addClass('show active');

            $('#gameSubject-tab').hide(); // 주제 선택 탭 숨김
            $('#gameTip-tab').tab('show'); // 게임 방법 & 팁 탭을 활성화

            $('#gameRequestModal').modal('hide');
            $('#subjectModal').modal('show');

            $('#readyBtn').prop('disabled', false);
            $('#exitBtn').hide();

        });

        $('#rejectGameRequest').off('click').on('click', function () {
            const rejectGame = {
                "gameEvent": "rejectGame"
            }
            dataChannel.sendMessage(rejectGame, 'gameEvent');
        });

        // game start btn
        $('#startBtn').off('click').on('click', function () {
            if(self.gameReadyUser === 1 ) {
                alert("혼자서는 게임 진행이 불가능해요!");
                return;
            }
            if (!self.title || !self.subject) {
                alert("게임 주제를 선정 후 시작하실 수 있습니다!");
                return;
            }


            $('#subjectModal').modal('hide');
            $('#answerBtn').attr('disabled', true);

            if (self.gameRound === 1) { // 1라운드 일때만 서버로 게임 정보 전달
                let url = window.__CONFIG__.API_BASE_URL +'/catchmind/gameSetting';
                let data = {
                    "roomId": roomId,
                    "gameUserList": self.gameUserList,
                    "totalGameRound" : self.totalGameRound,
                    "nowGameRound" : self.gameRound
                };

                // let successCallback = function (data) {
                //     console.log(data.message);
                // };
                //
                // let errorCallback = function (error) {
                //     // TODO 실패한 경우 모든 이벤트 초기화 필요
                // };

                ajaxToJson(url, 'POST', '', data);

            }

            // self.timeLeft = 60; // N초로 설정
            self.isGameStart = true;
            self.isGameLeader = true;
            self.isTimeRemain = true;

            // 게임 진행자만 캔버스 초기화 기회
            // 캔버스 초기화
            $('#maxClearCount').text("캔버스 초기화 기회 : " + self.maxClearCount);
            self.showRoundSubject();

            if (self.gameRound > 1) {
                // 새로운 라운드에 맞는 새로운 주제 선택 이벤트
                const newRoundSetting = {
                    'gameEvent': 'newRoundSetting',
                    'title': self.title,
                    'subject': self.subject
                }
                dataChannel.sendMessage(newRoundSetting, 'gameEvent');
            }

            dataChannel.sendMessage('gameStart', 'gameEvent');

            $('#catchMindCanvas').modal('show');
            // 모달 바디의 크기를 캔버스에 맞춤

            $('#clearCanvasBtn').show();

            self.startTimer();

        });

        $('#answerBtn').off('click').on('click', function () {
            if (isMobile()) {
                self.mobileAnswerEvent();
            } else {
                self.answerEvent();
            }
        });

        if (isMobile()) {
            $('#submitAnswer').on('click', function() {
                let $userAnswer = $('#userAnswer');
                // 사용자가 입력한 정답 가져오기
                let answer = self.replaceStr($userAnswer.val());
                console.log("사용자가 입력한 정답:", answer);
                // 정답 처리 로직
                self.checkAnswer(answer, false);

                // 입력 필드 초기화 및 모달 닫기
                $userAnswer.val('');
                $('#answerInputModal').modal('hide');
            });
        }

    },
    answerEvent : function(){
        let self = this;
        $('#answerBtn').attr('disabled', true);
        let text = "이제 정답을 외쳐주세요!";
        self.showToast(text);

        // 음성 인식 언어 설정 (더 구체적인 설정)
        self.recognition.lang = 'ko-KR';
        
        // 실시간 결과 활성화 (인식률 향상)
        self.recognition.interimResults = true;
        
        // 연속 인식 활성화
        self.recognition.continuous = true;
        
        // 적절한 대안 수 설정 (너무 크면 오히려 정확도 저하)
        self.recognition.maxAlternatives = 5;

        // console.log("음성 인식 시작");
        self.recognition.start();

        // 5초 동안 음성이 감지되지 않으면 인식 종료 (시간 연장)
        let recognitionTimeout = setTimeout(function() {
            self.recognition.stop();
            $('#answerBtn').attr('disabled', false);
            // console.log("5초 동안 음성을 감지하지 못했습니다. 음성 인식을 종료합니다.");
            self.showToast("음성을 감지하지 못했습니다. 음성 인식을 종료합니다.");
        }, 5000);

        // 음성 인식 결과 이벤트
        self.recognition.onresult = function (event) {
            // 타이머 취소
            clearTimeout(recognitionTimeout);

            // 최종 결과만 처리 (실시간 결과는 무시)
            let finalTranscript = '';
            for (let i = event.resultIndex; i < event.results.length; i++) {
                if (event.results[i].isFinal) {
                    finalTranscript += event.results[i][0].transcript;
                }
            }

            // 최종 결과가 있을 때만 처리
            if (finalTranscript.trim()) {
                // 텍스트 정제 (공백, 특수문자 제거)
                finalTranscript = finalTranscript
                    .replaceAll(' ', '')
                    .replaceAll(/[^\w가-힣]/g, '')
                    .toLowerCase();
                
                console.log('인식된 텍스트:', finalTranscript);

                // 여러 대안 결과 확인
                let alternatives = [];
                for (let i = 0; i < Math.min(event.results[event.results.length - 1].length, 3); i++) {
                    let altText = event.results[event.results.length - 1][i].transcript
                        .replaceAll(' ', '')
                        .replaceAll(/[^\w가-힣]/g, '')
                        .toLowerCase();
                    if (altText && !alternatives.includes(altText)) {
                        alternatives.push(altText);
                    }
                }

                console.log('대안 결과들:', alternatives);

                // 모든 대안에 대해 정답 확인
                let answerFound = false;
                for (let alt of alternatives) {
                    if (self.checkAnswer(alt, true)) {
                        answerFound = true;
                        break;
                    }
                }

                // 정답을 찾지 못한 경우 메인 결과로 재시도
                if (!answerFound) {
                    self.checkAnswer(finalTranscript, false);
                }

                // 음성 인식 종료
                self.recognition.stop();
                $('#answerBtn').attr('disabled', false);
            }
        };

        // 음성 인식 에러 이벤트 (개선된 에러 처리)
        self.recognition.onerror = function(event) {
            // 타이머 취소
            clearTimeout(recognitionTimeout);
            $('#answerBtn').attr('disabled', false);
            
            console.log('음성 인식 에러:', event.error);
            
            // 에러 타입별 메시지
            let errorMessage = '';
            switch(event.error) {
                case 'no-speech':
                    errorMessage = '음성이 감지되지 않았습니다. 다시 시도해주세요.';
                    break;
                case 'audio-capture':
                    errorMessage = '마이크에 접근할 수 없습니다. 마이크 권한을 확인해주세요.';
                    break;
                case 'not-allowed':
                    errorMessage = '마이크 사용 권한이 거부되었습니다.';
                    break;
                case 'network':
                    errorMessage = '네트워크 오류가 발생했습니다.';
                    break;
                default:
                    errorMessage = '음성 인식 중 오류가 발생했습니다.';
            }
            
            self.showToast(errorMessage);
        };
    },
    mobileAnswerEvent : function(){
        // 모달을 띄우는 코드
        $('#answerInputModal').modal('show');
    },
    gameReadyToast: function (type) {
        let text = "게임에 참여하셨습니다. 게임 규칙을 숙지한 후 ready 를 클릭해주세요.";

        if (type === 'leader') {
            text = "게임에 참여하셨습니다. 모든 유저가 준비를 마치면 start 버튼을 눌러주세요.";
        }

        this.showToast(text);
    },
    sendGameRequest: function () {
        const gameRequest = {
            "gameEvent": "gameRequest"
        }
        dataChannel.sendMessage(gameRequest, 'gameEvent');
    },
    addGameReady: function (type, userName, nickName) {
        this.gameReadyUser += 1;
        if (type === 'self') {
            let gameUser = {
                "roomId": roomId,
                "userId": this.nickName,
                "nickName": this.nickName
            }
            this.gameUserList.push(gameUser);
        } else {
            let gameUser = {
                "roomId": roomId,
                "userId": userName,
                "nickName": nickName
            }
            this.gameUserList.push(gameUser);
        }

        if (!this.isGameLeader) {
            $('#loadingUser').text('다른 참여자를 기다리는 중입니다. : ' + this.gameReadyUser + "/" + this.gameUserCount);
        } else {
            if (this.isAllUserReady() && !this.subject) {
                $('#readyUser').text('모든 유저가 준비를 완료했습니다. 게임을 시작해주세요!');
                $('#startBtn').attr('disabled', false);
            } else {
                $('#readyUser').text(this.gameReadyUser + "/" + this.gameUserCount);
            }
        }
    },
    rejectGame: function () {
        // 게임 참여자 수 감소
        this.gameUserCount -= 1;
        if(this.gameUserCount === 1 && this.isGameLeader) {
            self.showToast('다른 게임 참여자가 없어 게임을 시작할 수 없습니다.');
            $('#subjectModal').modal('hide');
            return;
        } 
        
        if (this.isGameLeader) {
            $('#readyUser').text(this.gameReadyUser + "/" + this.gameUserCount);
        } else {
            $('#loadingUser').text(this.gameReadyUser + "/" + this.gameUserCount);
        }
    },
    isAllUserReady: function () {
        return this.gameReadyUser === this.gameUserCount;
    },
    participantGameStartEvent: function () { // 참여자의 게임 시작 이벤트
        $('#subjectModal').modal('hide');
        $('#catchMindCanvas').modal('show');


        this.showRoundSubject();

        $('#clearCanvasBtn').hide();
        $('#answerBtn').attr('disabled', false);
    },
    checkAnswer: function (answer, isAltAnswer) {
        let self = this;
        if (answer !== this.subject && !isAltAnswer) {
            $('#answerBtn').attr('disabled', false);
            let text = "아쉽지만 " + answer + " 는(은) 정답이 아니에요";
            this.showToast(text);
            return false;
        }

        const gameData = {
            gameStatus: "WINNER",
            "roomId": roomId,
            "userId": self.nickName
        };

        let successCallback = function (data) {
            let gameWiner = {
                "gameEvent": "newWiner",
                "winer": data.nickName
            }

            dataChannel.sendMessage(gameWiner, 'gameEvent');

            $('#answerBtn').attr('disabled', true);
            self.speakWiner(data.nickName);
            self.resetGameRound(data.nickName);
        };

        let errorCallback = function (data) {

        };

        ajaxToJson(window.__CONFIG__.API_BASE_URL + '/catchmind/updateGameStatus', 'POST', '', gameData, successCallback, errorCallback);
    },
    speakWiner: function (winerName) {

        if (winerName !== '') {
            var speakText = winerName + "님이 정답을 맞췄습니다";

            this.showToast(speakText);

            // SpeechSynthesisUtterance 객체 생성
            var utterThis = new SpeechSynthesisUtterance(speakText);

            // 음성이 끝났을 때 실행할 콜백 함수
            utterThis.onend = function (event) {
                console.log('음성 합성이 끝났습니다.');
            };

            // 오류가 발생했을 때 실행할 콜백 함수
            utterThis.onerror = function (event) {
                console.error('음성 합성 중 오류가 발생했습니다.');
            };

            // 사용할 수 있는 음성 중 하나를 선택 (예: 첫 번째 음성)
            var voices = this.synth.getVoices();
            utterThis.voice = voices[0];

            // 음성 속도와 피치 설정 (선택 사항)
            utterThis.pitch = 1; // 기본값은 1
            utterThis.rate = 1; // 기본값은 1

            // 음성 합성 시작
            this.synth.speak(utterThis);
        }
    },
    showRoundSubject: function (resetFlag) {
        let text = '';
        if (resetFlag) {
            $('#roundSubject').text(text);
        } else {
            if (this.isGameLeader) {
                text = '정답 : ' + this.subject;
            } else {
                text = '주제 : ' + this.title;
            }
            $('#roundSubject').text(text);
        }
    },
    // leftGameParticipants: function () {
    //     this.gameUserCount -= 1;
    // },
    // addGameParticipants : function(){
    //     this.gameUserCount +=1;
    // },
    setGameUser: function () {
        this.gameUserCount = Object.keys(participants).length;
    },
    clearCanvas: function () {
        this.maxClearCount -= 1;
        this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);

        if (this.isGameParticipant) {
            if (this.maxClearCount === 0) {
                $('#maxClearCount').text("진행자가 초기화 기회를 모두 소진했습니다");
                this.showToast("진행자가 초기화 기회를 모두 소진했습니다!");
            } else {
                $('#maxClearCount').text("진행자의 캔버스 초기화 기회 : " + this.maxClearCount);
                this.showToast("진행자가 캔버스를 초기화 했습니다!");
            }
        } else if (this.isGameLeader) { // 게임 리더면 maxclearCanvas -=1
            if (this.maxClearCount === 0) {
                $('#clearCanvasBtn').prop('disabled', true);
                $('#maxClearCount').text("이제 캔버스 초기화는 할 수 없어요!!");
            } else {
                $('#maxClearCount').text("캔버스 초기화 기회 : " + this.maxClearCount);
            }
        }
        // // 캔버스 초기화
        // this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
    },
    showToast: function (text) {
        Toastify({
            text: text,
            duration: 3000,
            // destination: "",
            newWindow: true,
            close: true,
            gravity: "top", // `top` or `bottom`
            position: "center", // `left`, `center` or `right`
            stopOnFocus: true, // Prevents dismissing of toast on hover
            style: {
                background: "linear-gradient(to right, #00b09b, #96c93d)",
            },
        }).showToast();
    },
    resetGameRound: async function (winner) {
        let self = this;
        // 캔버스 초기화
        self.clearCanvas();
        $('#catchMindCanvas').modal('hide');

        if (self.gameRound === self.totalGameRound) {
            let url = window.__CONFIG__.API_BASE_URL +`/catchmind/gameResult?roomId=${roomId}`;
            try {
                let data = await ajaxToJsonPromise(url, 'GET');
                if (data.result === 'SyncGameRound') {
                    console.log(data.message);
                    self.gameRound = data.gameRound;
                } else {
                    let gameResult = data.gameResult;
                    self.displayGameResults(gameResult);
                    // self.preventGameEvent();
                    return;
                }
            } catch (error) {
                console.error("An error occurred:", error);
                // 예외처리 필요
                return;
            }
        }

        // 게임 상태 초기화
        self.isGameStart = false;
        self.isGameReady = false;
        self.drawing = false;
        self.isTimeRemain = false;
        self.title = '';
        self.subject = '';
        // self.gameReadyUser = 0; // 게임 준비 상태인 유저 수 초기화
        self.showRoundSubject(true);

        // 최대 캔버스 클리어 횟수 재설정
        self.maxClearCount = 3;

        // TODO 타이머 이벤트 초기화
        $('#progress-container').empty();
        self.resetTimer();

        if (winner === self.nickName) {
            self.isGameLeader = true;
            self.isGameParticipant = false;

            $('#gameSubject').addClass('show active');  // 주제 선택 탭 내용 숨김
            $('#gameTip').removeClass('show active')

            $('#gameSubject-tab').show();
            $('#gameSubject-tab').tab('show');

            $('#nickName_ld').val(self.nickName);
            $('#nickName_ld').attr('disabled', true);

            $('#startBtn').removeAttr('hidden');
            // $('#startBtn').attr('disabled', false);

            // 캔버스 클리어 후 이벤트
            $('#maxClearCount').text("캔버스 초기화 기회 : " + self.maxClearCount);

            $("#loadingIndicator").hide();
            $('#startBtn').show();

        } else {
            self.isGameLeader = false;
            self.isGameParticipant = true;

            $('#gameSubject-tab').hide(); // 주제 선택 탭 숨김
            $('#gameTip-tab').tab('show'); // 게임 방법 & 팁 탭을 활성화

            // 게임 방법 & 팁 탭을 기본적으로 표시
            $('#gameSubject').removeClass('show active');  // 주제 선택 탭 내용 숨김
            $('#gameTip').addClass('show active');

            $('#startBtn').hide();
            $('#readyUser').hide();

            // 캔버스 클리어후 이벤트
            $('#maxClearCount').text("진행자의 캔버스 초기화 기회 : " + this.maxClearCount);

            $("#loadingIndicator").show();
            $('#loadingUser').removeAttr('hidden');
            $('#loadingUser').text('승리자의 주제 선택을 기다리는 중...!! : ' + this.gameReadyUser + "/" + this.gameUserCount);
        }

        $('#subjectModal').modal('show');
        self.gameRound += 1; // 게임 라운드 추가
    },
    newRoundSubject: function (data) {
        this.title = data.title;
        this.subject = this.replaceStr(data.subject);
    },
    /**
     * 타이머 시작
     */
    startTimer: function () {
        let self = this;
        let totalTime = self.totalTime;
        let timeLeft = self.totalTime;
        // ProgressBar.js를 사용한 타이머 표시 업데이트
        self.timerBar = new ProgressBar.Line('#progress-container', {
            strokeWidth: 2,
            color: '#FFEA82',
            trailColor: '#eee',
            trailWidth: 1,
            easing: 'easeInOut',
            duration: 1000, // 각 갱신에 걸리는 시간을 1초로 설정하여 더 자연스러운 전환을 만듭니다.
            svgStyle: null,
            // from: {color: '#0408f8'}, // 시작 색상
            // to: {color: '#5153c4'}, // 종료 색상
            step: function (state, bar, attachment) {
                // 남은 시간에 따라 색상을 동적으로 계산
                let progress = (totalTime - timeLeft) / totalTime;
                let red = Math.round(4 + progress * (248 - 4)); // 4에서 248로 변화
                let green = Math.round(8 + progress * (4 - 8)); // 8에서 4로 변화
                let blue = Math.round(248 + progress * (4 - 248)); // 248에서 4로 변화

                let color = `rgb(${red}, ${green}, ${blue})`;
                bar.path.setAttribute('stroke', color);
            }
        });

        self.timerId = setInterval(function () {
            timeLeft--;
            let timeFraction = timeLeft / self.totalTime;

            // 매 초마다 프로그레스 바 업데이트
            self.timerBar.animate(timeFraction, {duration: 1000});

            if (timeLeft <= 0) {
                clearInterval(self.timerId);
                self.isTimeRemain = false;
                console.log("타이머 종료");
            }
        }, 1000);
    },
    /**
     * 타이머 초기화
     */
    resetTimer: function () {
        let self = this;
        // 타이머 중지
        clearInterval(self.timerId);

        if (self.timerBar) {
            // 프로그레스 바를 원래 상태로 재설정
            self.timerBar.set(1);
        }
    },
    /**
     * subject 의 각종 띄어쓰기 및 특수문자를 제거하기 위한 func
     * @param {String}str
     * @returns {String} str
     */
    replaceStr: function (str) {
        // let 정제된문자열 = 문자열.replace(/AI\s*:\s*/g, "");
        str = str.replace(/[^a-zA-Z0-9가-힣]/g, '');
        return str;
    },
    showGameRequestModal: function () {
        let self = this;
        let $nickNamePt = $('#nickName_pt');
        self.nickName = localStorage.getItem('nickName');

        // 로그인한 사용자로 닉네임 설정
        $nickNamePt.val(self.nickName);
        $nickNamePt.attr('disabled', true);
        $('#gameRequestModal').modal('show');
    },
    // /**
    //  * 게임 상태 초기화
    //  * TODO 초기화 로직은 나중에 개발 예정
    //  */
    // endGameEvent: function() {
    //     this.isGameStart = false;
    //     this.isGameReady = false;
    //     this.isGameLeader = false;
    //     this.isGameParticipant = false;
    //     this.gameReadyUser = 0;
    //     this.gameUserList = [];
    //
    //     // 캔버스 초기화
    //     this.clearCanvas();
    //
    //     // 타이머 초기화
    //     this.resetTimer();
    //
    //     // 게임 참여자 목록 초기화
    //     this.gameUserList = [];
    //
    //     // 사용자에게 게임 종료 알림
    //     this.showToast("게임이 종료되었습니다. 다시 시작하려면 게임 준비를 클릭하세요.");
    //
    //     // 모든 사용자 UI를 초기 상태로 설정
    //     $('#subjectModal').modal('hide');
    //     $('#catchMindCanvas').modal('hide');
    //     $('#startBtn').hide();
    //     $('#readyBtn').show();
    //     $('#exitBtn').show();
    //     $('#nickName_ld').val('');
    //     $('#nickName_ld').attr('disabled', false);
    //
    //     // 모달 내부의 상태도 초기화
    //     $('.subject-btn').removeClass('active');
    //     $('#titleButtonContainer').empty();
    //     $('#subjectButtonContainer').empty();
    //
    //     // 게임 라운드와 관련된 모든 내부 상태 변수 초기화
    //     this.gameRound = 1;
    //     this.maxClearCount = 3;
    //
    //     // 게임 종료 후 처리 로직 (예: 데이터베이스에 게임 결과 저장)
    //     console.log("게임 결과를 저장합니다.");
    //
    // }
    // 게임 참여자 데이터를 기반으로 모달 내용을 동적으로 생성
    displayGameResults: function (data) {
        let self = this;
        self.alreadyPlayedGame = true;
        // 모달 헤더에 총 게임 라운드 수 표시
        document.getElementById('totalGameRounds').textContent = `총 게임 라운드: ${data.totalGameRound}`;

        // 게임 결과 목록 업데이트
        let list = document.getElementById('gameResultsList');
        list.innerHTML = ''; // 목록 초기화
        data.gameUserList.forEach((user, index) => {
            let item = document.createElement('li');
            item.className = 'list-group-item';
            if (index === 0) { // 첫 번째 항목이 우승자
                item.classList.add('winner');
                self.showToast(`최종 우승자는 ${user.nickName} 입니다!`);
            }
            item.textContent = `${user.nickName} - 점수: ${user.score}`;
            list.appendChild(item);
        });

        $('#gameResultsModal').modal('show');
    },
    preventGameEvent : function (){
        $('#subjectModal').modal('hide').attr('disabled', true);
    }
}