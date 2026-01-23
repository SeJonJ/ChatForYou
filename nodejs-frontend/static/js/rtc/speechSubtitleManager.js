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
 * 실시간 음성 인식 및 자막 표시 관리 클래스
 * Web Speech API를 사용하여 실시간으로 음성을 인식하고
 * 결과를 GStreamerFilter textoverlay로 전송
 */
class SpeechRecognitionManager {
    constructor() {
        this.recognition = null;
        this.isRecognizing = false;
        this.isEnabled = false;
        this.lastTranscript = '';
        this.debounceTimer = null;
        this.errorRetryCount = 0;
        this.maxRetryCount = 3;
        
        // 성능 최적화를 위한 추가 속성
        this.textHistory = new Map(); // 중복 텍스트 방지
        this.performanceMetrics = {
            startTime: null,
            recognitionCount: 0,
            errorCount: 0,
            avgResponseTime: 0
        };
        this.cleanupTimer = null;
        this.qualityThreshold = 0.7; // 음성 인식 품질 임계값
        
        // 설정 (최적화된)
        this.config = {
            language: 'ko-KR', // 한국어 기본 설정
            continuous: true,  // 연속 인식
            interimResults: true, // 중간 결과 포함
            maxAlternatives: 1,   // 최대 대안 개수
            debounceDelay: 300,   // 기본 디바운스 지연 시간 (ms) - 더 빠르게
            minTextLength: 2,     // 최소 텍스트 길이
            maxTextLength: 100,   // 최대 텍스트 길이
            adaptiveDebounce: true, // 적응적 디바운스
            duplicateThreshold: 0.8, // 중복 텍스트 유사도 임계값
            cleanupInterval: 30000   // 메모리 정리 간격 (30초)
        };
        
        this.init();
        this.startPerformanceMonitoring();
    }
    
    /**
     * 음성 인식 초기화 (최적화됨)
     */
    init() {
        // 브라우저 호환성 체크
        if (!this.checkBrowserSupport()) {
            console.warn('이 브라우저는 Web Speech API를 지원하지 않습니다.');
            return false;
        }
        
        try {
            // SpeechRecognition 객체 생성
            const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
            this.recognition = new SpeechRecognition();
            
            // 브라우저별 최적화 적용
            this.applyBrowserOptimizations();
            
            // 기본 설정 적용
            this.recognition.continuous = this.config.continuous;
            this.recognition.interimResults = this.config.interimResults;
            this.recognition.lang = this.config.language;
            this.recognition.maxAlternatives = this.config.maxAlternatives;
            
            // 이벤트 리스너 등록
            this.setupEventListeners();
            
            console.log('SpeechRecognitionManager 초기화 완료 (최적화 적용)');
            return true;
            
        } catch (error) {
            console.error('음성 인식 초기화 실패:', error);
            return false;
        }
    }
    
    /**
     * 브라우저 지원 여부 확인
     */
    checkBrowserSupport() {
        return 'SpeechRecognition' in window || 'webkitSpeechRecognition' in window;
    }
    
    /**
     * 이벤트 리스너 설정
     */
    setupEventListeners() {
        if (!this.recognition) return;
        
        // 음성 인식 시작 이벤트
        this.recognition.onstart = () => {
            console.debug('음성 인식 시작');
            this.isRecognizing = true;
            this.errorRetryCount = 0;
            this.updateUI('recording');
        };
        
        // 음성 인식 결과 이벤트
        this.recognition.onresult = (event) => {
            this.handleRecognitionResult(event);
        };
        
        // 음성 인식 종료 이벤트
        this.recognition.onend = () => {
            console.debug('음성 인식 종료');
            this.isRecognizing = false;
            this.updateUI('stopped');
            
            // 자동 재시작 (활성화 상태일 때)
            if (this.isEnabled) {
                setTimeout(() => {
                    this.startRecognition();
                }, 100);
            }
        };
        
        // 에러 처리
        this.recognition.onerror = (event) => {
            this.handleRecognitionError(event);
        };
        
        // 음성 감지 시작
        this.recognition.onspeechstart = () => {
            console.debug('음성 감지 시작');
        };
        
        // 음성 감지 종료
        this.recognition.onspeechend = () => {
            console.debug('음성 감지 종료');
        };
    }
    
    /**
     * 음성 인식 결과 처리 (최적화됨)
     */
    handleRecognitionResult(event) {
        const startTime = performance.now();
        let finalTranscript = '';
        let interimTranscript = '';
        let confidence = 0;
        
        // 결과 분석 및 품질 평가
        for (let i = event.resultIndex; i < event.results.length; i++) {
            const result = event.results[i];
            const transcript = result[0].transcript;
            confidence = Math.max(confidence, result[0].confidence || 0);
            
            if (result.isFinal) {
                finalTranscript += transcript;
            } else {
                interimTranscript += transcript;
            }
        }
        
        // 최종 결과가 있으면 우선 사용, 없으면 중간 결과 사용
        const currentTranscript = finalTranscript || interimTranscript;
        const cleanText = this.cleanAndValidateText(currentTranscript);
        
        // 품질 및 중복 검사
        if (cleanText && 
            this.isQualityTextGood(cleanText, confidence) &&
            !this.isDuplicateText(cleanText)) {
            
            this.lastTranscript = cleanText;
            // this.updatePerformanceMetrics(startTime);
            
            // 적응적 디바운스 적용
            const debounceDelay = this.calculateAdaptiveDebounce(cleanText);
            this.debounceTextUpdate(cleanText, debounceDelay);
        }
    }
    
    /**
     * 텍스트 정리 및 검증
     */
    cleanAndValidateText(text) {
        if (!text) return '';
        
        const cleaned = text.trim()
            .replace(/\s+/g, ' ') // 연속된 공백 제거
            .substring(0, this.config.maxTextLength); // 최대 길이 제한
        
        // 최소 길이 검사
        return cleaned.length >= this.config.minTextLength ? cleaned : '';
    }
    
    /**
     * 텍스트 품질 검사
     */
    isQualityTextGood(text, confidence) {
        // 신뢰도 검사
        if (confidence > 0 && confidence < this.qualityThreshold) {
            return false;
        }
        
        // 의미 있는 텍스트인지 검사 (한글, 영문, 숫자 포함)
        const meaningfulChars = text.match(/[가-힣a-zA-Z0-9]/g);
        const meaningfulRatio = meaningfulChars ? meaningfulChars.length / text.length : 0;
        
        return meaningfulRatio > 0.4; // 40% 이상이 의미 있는 문자여야 함
    }
    
    /**
     * 중복 텍스트 검사 (유사도 기반)
     */
    isDuplicateText(text) {
        const now = Date.now();
        const textHash = this.getTextHash(text);
        
        // 최근 텍스트 히스토리에서 유사한 텍스트 검사
        for (const [hash, data] of this.textHistory) {
            if (now - data.timestamp < 5000) { // 5초 이내
                const similarity = this.calculateSimilarity(text, data.text);
                if (similarity > this.config.duplicateThreshold) {
                    return true; // 중복으로 판단
                }
            }
        }
        
        // 새 텍스트 히스토리에 추가
        this.textHistory.set(textHash, { text, timestamp: now });
        return false;
    }
    
    /**
     * 적응적 디바운스 계산
     */
    calculateAdaptiveDebounce(text) {
        if (!this.config.adaptiveDebounce) {
            return this.config.debounceDelay;
        }
        
        // 텍스트 길이에 따른 적응적 지연
        const baseDelay = this.config.debounceDelay;
        const lengthFactor = Math.min(text.length / 20, 2); // 최대 2배
        const adaptiveDelay = baseDelay * (0.5 + lengthFactor * 0.5);
        
        return Math.round(adaptiveDelay);
    }
    
    /**
     * 텍스트 해시 생성
     */
    getTextHash(text) {
        let hash = 0;
        for (let i = 0; i < text.length; i++) {
            const char = text.charCodeAt(i);
            hash = ((hash << 5) - hash) + char;
            hash = hash & hash; // 32비트 정수로 변환
        }
        return hash;
    }
    
    /**
     * 텍스트 유사도 계산
     */
    calculateSimilarity(str1, str2) {
        const len1 = str1.length;
        const len2 = str2.length;
        
        if (len1 === 0) return len2 === 0 ? 1 : 0;
        if (len2 === 0) return 0;
        
        const matrix = Array(len2 + 1).fill().map(() => Array(len1 + 1).fill(0));
        
        for (let i = 0; i <= len1; i++) matrix[0][i] = i;
        for (let j = 0; j <= len2; j++) matrix[j][0] = j;
        
        for (let j = 1; j <= len2; j++) {
            for (let i = 1; i <= len1; i++) {
                const cost = str1[i - 1] === str2[j - 1] ? 0 : 1;
                matrix[j][i] = Math.min(
                    matrix[j - 1][i] + 1,     // 삭제
                    matrix[j][i - 1] + 1,     // 삽입
                    matrix[j - 1][i - 1] + cost // 대체
                );
            }
        }
        
        const distance = matrix[len2][len1];
        const maxLength = Math.max(len1, len2);
        return 1 - (distance / maxLength);
    }
    
    /**
     * 디바운스를 적용한 텍스트 업데이트 (최적화됨)
     */
    debounceTextUpdate(text, customDelay = null) {
        // 기존 타이머 클리어
        if (this.debounceTimer) {
            clearTimeout(this.debounceTimer);
        }
        
        const delay = customDelay || this.config.debounceDelay;
        
        // 새 타이머 설정
        this.debounceTimer = setTimeout(() => {
            this.sendTextOverlay(text);
            this.debounceTimer = null; // 메모리 정리
        }, delay);
    }
    
    /**
     * 텍스트 오버레이 전송
     */
    sendTextOverlay(text) {
        if (!text || text.trim().length === 0) return;
        
        try {
            const message = {
                event: 'TEXT_OVERLAY',
                roomId: roomId,
                senderId: userId,
                senderNickName: nickName,
                text: text
            };
            
            // kurento-service.js의 sendMessageToServer 함수 사용
            if (typeof sendMessageToServer === 'function') {
                sendMessageToServer(message);
                console.debug('자막 전송:', text);
            } else {
                console.error('sendMessageToServer 함수를 찾을 수 없습니다.');
            }
            
        } catch (error) {
            console.error('텍스트 오버레이 전송 실패:', error);
        }
    }
    
    /**
     * 음성 인식 에러 처리 (개선됨)
     */
    handleRecognitionError(event) {
        this.performanceMetrics.errorCount++;
        console.error('음성 인식 에러:', event.error);
        
        switch (event.error) {
            case 'no-speech':
                console.log('음성이 감지되지 않았습니다.');
                // 자동 재시작 (no-speech는 정상적인 상황)
                if (this.isEnabled) {
                    setTimeout(() => this.startRecognition(), 500);
                }
                break;
            case 'audio-capture':
                console.error('오디오 캡처 실패 - 마이크 연결 확인 필요');
                this.updateUI('error');
                this.showUserFriendlyError('마이크에 접근할 수 없습니다. 마이크 연결을 확인해주세요.');
                break;
            case 'not-allowed':
                console.error('마이크 권한이 거부되었습니다.');
                this.updateUI('permission-denied');
                this.showUserFriendlyError('마이크 권한이 필요합니다. 브라우저 설정에서 마이크 권한을 허용해주세요.');
                break;
            case 'network':
                console.error('네트워크 오류');
                this.handleNetworkError();
                break;
            case 'service-not-allowed':
                console.error('음성 인식 서비스 사용 불가');
                this.updateUI('error');
                this.showUserFriendlyError('음성 인식 서비스를 사용할 수 없습니다.');
                break;
            case 'bad-grammar':
                console.error('문법 오류');
                // 문법 오류는 무시하고 재시작
                if (this.isEnabled) {
                    setTimeout(() => this.startRecognition(), 100);
                }
                break;
            default:
                console.error('알 수 없는 오류:', event.error);
                this.handleUnknownError(event.error);
        }
    }
    
    /**
     * 사용자 친화적 에러 메시지 표시
     */
    showUserFriendlyError(message) {
        // kurento-service.js의 showToast 함수 사용
        if (typeof showToast === 'function') {
            showToast(message, 'error', 5000);
        } else {
            alert(message); // 폴백
        }
    }
    
    /**
     * 알 수 없는 에러 처리
     */
    handleUnknownError(errorType) {
        this.errorRetryCount++;
        
        if (this.errorRetryCount < this.maxRetryCount) {
            console.log(`알 수 없는 에러 재시도 (${this.errorRetryCount}/${this.maxRetryCount})`);
            setTimeout(() => {
                if (this.isEnabled) {
                    this.startRecognition();
                }
            }, 1000 * this.errorRetryCount);
        } else {
            console.error('최대 재시도 횟수 초과 - 음성 인식 중단');
            this.stop();
            this.updateUI('error');
            this.showUserFriendlyError('음성 인식에 문제가 발생했습니다. 페이지를 새로고침 후 다시 시도해주세요.');
        }
    }
    
    /**
     * 네트워크 에러 처리 및 재시도
     */
    handleNetworkError() {
        this.errorRetryCount++;
        
        if (this.errorRetryCount < this.maxRetryCount) {
            console.log(`네트워크 오류 재시도 (${this.errorRetryCount}/${this.maxRetryCount})`);
            setTimeout(() => {
                if (this.isEnabled) {
                    this.startRecognition();
                }
            }, 2000 * this.errorRetryCount); // 점진적 지연
        } else {
            console.error('최대 재시도 횟수 초과. 음성 인식을 중단합니다.');
            this.stop();
            this.updateUI('error');
        }
    }
    
    /**
     * 음성 인식 시작
     */
    start() {
        if (!this.recognition) {
            console.error('음성 인식이 초기화되지 않았습니다.');
            return false;
        }
        
        if (this.isRecognizing) {
            console.log('이미 음성 인식이 실행 중입니다.');
            return true;
        }
        
        try {
            this.isEnabled = true;
            this.startRecognition();
            return true;
        } catch (error) {
            console.error('음성 인식 시작 실패:', error);
            return false;
        }
    }
    
    /**
     * 실제 음성 인식 시작
     */
    startRecognition() {
        if (this.recognition && !this.isRecognizing) {
            try {
                this.recognition.start();
            } catch (error) {
                console.error('recognition.start() 실패:', error);
            }
        }
    }
    
    /**
     * 음성 인식 중지 (최적화됨)
     */
    stop() {
        this.isEnabled = false;
        
        if (this.recognition && this.isRecognizing) {
            try {
                this.recognition.stop();
            } catch (error) {
                console.error('음성 인식 중지 실패:', error);
            }
        }
        
        // 리소스 정리
        this.cleanup();
        this.updateUI('stopped');
    }
    
    /**
     * 리소스 정리
     */
    cleanup() {
        // 디바운스 타이머 클리어
        if (this.debounceTimer) {
            clearTimeout(this.debounceTimer);
            this.debounceTimer = null;
        }
        
        // 정리 타이머 클리어
        if (this.cleanupTimer) {
            clearTimeout(this.cleanupTimer);
            this.cleanupTimer = null;
        }
        
        // 오래된 텍스트 히스토리 정리
        this.cleanupTextHistory();
    }
    
    /**
     * 텍스트 히스토리 정리
     */
    cleanupTextHistory() {
        const now = Date.now();
        const maxAge = 30000; // 30초
        
        for (const [hash, data] of this.textHistory) {
            if (now - data.timestamp > maxAge) {
                this.textHistory.delete(hash);
            }
        }
    }
    
    /**
     * 성능 모니터링 시작
     */
    startPerformanceMonitoring() {
        // 주기적 메모리 정리
        this.cleanupTimer = setInterval(() => {
            this.cleanupTextHistory();
            // this.logPerformanceMetrics();
        }, this.config.cleanupInterval);
    }
    
    /** 추후 AI 기반 예측 및 녹화 후 채팅 내용 요약 기능 개발 시 활용 예정 **/
    // /**
    //  * 성능 메트릭 업데이트
    //  */
    // updatePerformanceMetrics(startTime) {
    //     this.performanceMetrics.recognitionCount++;
    //     const responseTime = performance.now() - startTime;
        
    //     // 평균 응답 시간 계산
    //     const count = this.performanceMetrics.recognitionCount;
    //     this.performanceMetrics.avgResponseTime = 
    //         (this.performanceMetrics.avgResponseTime * (count - 1) + responseTime) / count;
    // }
    
    // /**
    //  * 성능 메트릭 로깅
    //  */
    // logPerformanceMetrics() {
    //     if (this.performanceMetrics.recognitionCount > 0) {
    //         console.log('음성 인식 성능 메트릭:', {
    //             인식횟수: this.performanceMetrics.recognitionCount,
    //             에러횟수: this.performanceMetrics.errorCount,
    //             평균응답시간: `${this.performanceMetrics.avgResponseTime.toFixed(2)}ms`,
    //             텍스트히스토리크기: this.textHistory.size,
    //             성공률: `${((1 - this.performanceMetrics.errorCount / this.performanceMetrics.recognitionCount) * 100).toFixed(1)}%`
    //         });
    //     }
    // }
    
    /**
     * 설정 업데이트
     */
    updateConfig(newConfig) {
        this.config = { ...this.config, ...newConfig };
        
        // 언어 설정 변경 시 음성 인식 재시작
        if (newConfig.language && this.recognition) {
            this.recognition.lang = newConfig.language;
            if (this.isEnabled) {
                this.stop();
                setTimeout(() => this.start(), 100);
            }
        }
        
        console.log('설정 업데이트:', this.config);
    }
    
    /**
     * 브라우저별 최적화 설정 적용
     */
    applyBrowserOptimizations() {
        const userAgent = navigator.userAgent.toLowerCase();
        
        if (userAgent.includes('chrome')) {
            // Chrome 최적화
            this.config.debounceDelay = Math.max(this.config.debounceDelay, 200);
        } else if (userAgent.includes('firefox')) {
            // Firefox 최적화
            this.config.debounceDelay = Math.max(this.config.debounceDelay, 400);
            this.config.maxAlternatives = 1; // Firefox에서 안정성 향상
        } else if (userAgent.includes('safari')) {
            // Safari 최적화
            this.config.debounceDelay = Math.max(this.config.debounceDelay, 500);
            this.config.continuous = true; // Safari에서 연속 인식 강제
        }
        
        console.log('브라우저별 최적화 적용:', userAgent.split(' ')[0]);
    }
    
    /**
     * 음성 인식 토글
     */
    toggle() {
        if (this.isEnabled) {
            this.stop();
        } else {
            this.start();
        }
        return this.isEnabled;
    }
    
    /**
     * 언어 설정 변경
     */
    setLanguage(language) {
        this.config.language = language;
        if (this.recognition) {
            this.recognition.lang = language;
        }
    }
    
    /**
     * UI 상태 업데이트
     */
    updateUI(status) {
        // UI 업데이트 이벤트 발생
        const event = new CustomEvent('speechRecognitionStatusChanged', {
            detail: {
                status: status,
                isEnabled: this.isEnabled,
                isRecognizing: this.isRecognizing
            }
        });
        document.dispatchEvent(event);
    }
    
    /**
     * 현재 상태 반환
     */
    getStatus() {
        return {
            isSupported: this.checkBrowserSupport(),
            isEnabled: this.isEnabled,
            isRecognizing: this.isRecognizing,
            language: this.config.language
        };
    }
}

// 전역 인스턴스 생성
let speechRecognitionManager = null;

// 초기화 함수 (최적화됨)
function initSpeechRecognition() {
    if (!speechRecognitionManager) {
        speechRecognitionManager = new SpeechRecognitionManager();
    }
    return speechRecognitionManager;
}

// 전역 접근을 위한 함수들 (최적화됨)
function startSpeechRecognition() {
    if (!speechRecognitionManager) {
        speechRecognitionManager = initSpeechRecognition();
    }
    return speechRecognitionManager.start();
}

function stopSpeechRecognition() {
    if (speechRecognitionManager) {
        speechRecognitionManager.stop();
        return true;
    }
    return false;
}

function toggleSpeechRecognition() {
    if (!speechRecognitionManager) {
        speechRecognitionManager = initSpeechRecognition();
    }
    return speechRecognitionManager.toggle();
}

function getSpeechRecognitionStatus() {
    if (speechRecognitionManager) {
        return speechRecognitionManager.getStatus();
    }
    return { isSupported: false, isEnabled: false, isRecognizing: false };
}

// 추가 유틸리티 함수들

/**
 * 음성 인식 설정 업데이트
 */
function updateSpeechRecognitionConfig(config) {
    if (speechRecognitionManager) {
        speechRecognitionManager.updateConfig(config);
        return true;
    }
    return false;
}

/**
 * 언어 설정 변경
 */
function setSpeechRecognitionLanguage(language) {
    if (speechRecognitionManager) {
        speechRecognitionManager.setLanguage(language);
        return true;
    }
    return false;
}

/**
 * 성능 메트릭 조회
 */
function getSpeechRecognitionMetrics() {
    if (speechRecognitionManager) {
        speechRecognitionManager.logPerformanceMetrics();
        return speechRecognitionManager.performanceMetrics;
    }
    return null;
}

/**
 * 메모리 정리 강제 실행
 */
function cleanupSpeechRecognition() {
    if (speechRecognitionManager) {
        speechRecognitionManager.cleanup();
        return true;
    }
    return false;
}

/**
 * 음성 인식 완전 재시작 (문제 해결용)
 */
function restartSpeechRecognition() {
    if (speechRecognitionManager) {
        const wasEnabled = speechRecognitionManager.isEnabled;
        speechRecognitionManager.stop();
        speechRecognitionManager.cleanup();
        
        // 잠시 대기 후 재시작
        setTimeout(() => {
            if (wasEnabled) {
                speechRecognitionManager.start();
            }
        }, 500);
        
        return true;
    }
    return false;
}

/**
 * 디버그 정보 출력
 */
function debugSpeechRecognition() {
    if (speechRecognitionManager) {
        console.log('=== 음성 인식 디버그 정보 ===');
        console.log('상태:', speechRecognitionManager.getStatus());
        console.log('설정:', speechRecognitionManager.config);
        console.log('성능 메트릭:', speechRecognitionManager.performanceMetrics);
        console.log('텍스트 히스토리 크기:', speechRecognitionManager.textHistory.size);
        console.log('브라우저:', navigator.userAgent);
        console.log('========================');
        return true;
    }
    return false;
}

/**
 * 프리셋 설정 적용
 */
function applySpeechRecognitionPreset(preset) {
    const presets = {
        'fast': {
            debounceDelay: 200,
            adaptiveDebounce: true,
            duplicateThreshold: 0.9
        },
        'accurate': {
            debounceDelay: 600,
            adaptiveDebounce: false,
            duplicateThreshold: 0.7,
            qualityThreshold: 0.8
        },
        'balanced': {
            debounceDelay: 300,
            adaptiveDebounce: true,
            duplicateThreshold: 0.8,
            qualityThreshold: 0.7
        }
    };
    
    if (presets[preset] && speechRecognitionManager) {
        speechRecognitionManager.updateConfig(presets[preset]);
        console.log(`프리셋 '${preset}' 적용됨`);
        return true;
    }
    
    console.error('유효하지 않은 프리셋:', preset);
    console.log('사용 가능한 프리셋:', Object.keys(presets));
    return false;
}

/**
 * 자막 활성화 여부 확인
 */
function isSpeechRecognitionEnabled() {
    if (speechRecognitionManager) {
        return speechRecognitionManager.isEnabled;
    }
    return false;
}

/**
 * 녹화 기능에 따른 자막 기능 처리
 */
function handleRecordingStart() {
    if (speechRecognitionManager) {
        speechRecognitionManager.stop();
    }
}

function handleRecordingStop() {
    if (speechRecognitionManager) {
        speechRecognitionManager.start();
    }
}

/**
 * 녹화 여부에 따른 자막 기능 처리
 * @param {boolean} isRecording
 */
function handlingSubtitleByRecording(isRecording) {
    console.log('[SUBTITLE] handlingSubtitleByRecording called - isRecording:', isRecording);

    if(isRecording) {
        // 녹화 시작 → 자막 비활성화
        console.log('[SUBTITLE] Disabling subtitle due to recording start');

        _showSubtitleToast('녹화 시작으로 인해 자막 기능을 비활성화합니다');

        console.log('[SUBTITLE] Calling stopSpeechRecognition()');
        if (typeof stopSpeechRecognition === 'function') {
            stopSpeechRecognition();
            console.log('[SUBTITLE] stopSpeechRecognition() completed');
        } else {
            console.error('[SUBTITLE] stopSpeechRecognition function not found!');
        }

        // 자막 버튼 disabled 처리
        _updateSubtitleButton(true);
    } else {
        // 녹화 중지 → 자막 활성화
        console.log('[SUBTITLE] Enabling subtitle due to recording stop');

        _showSubtitleToast('녹화 중지로 인해 자막 기능을 다시 사용할 수 있습니다');

        // 자막 버튼 enabled 처리
        _updateSubtitleButton(false);
    }
}

/**
 * 자막 Toast 메시지 표시 
 * @private
 * @param {string} message - 표시할 메시지
 */
function _showSubtitleToast(message) {
    if (typeof Toastify !== 'undefined') {
        Toastify({
            text: message,
            duration: 3000,
            newWindow: true,
            close: true,
            gravity: 'top',
            position: 'center',
        }).showToast();
    }
}

/**
 * 자막 버튼 상태 업데이트
 * @private
 * @param {boolean} disabled - 비활성화 여부
 */
function _updateSubtitleButton(disabled) {
    const $subtitleBtn = $('#subtitleBtn');
    console.log('[SUBTITLE] Subtitle button found:', $subtitleBtn.length);
    
    if ($subtitleBtn.length > 0) {
        $subtitleBtn.prop('disabled', disabled);
        $subtitleBtn.css({
            'opacity': disabled ? '0.5' : '1',
            'cursor': disabled ? 'not-allowed' : 'pointer',
            'pointer-events': disabled ? 'none' : 'auto'
        });
        console.log('[SUBTITLE] Subtitle button ' + (disabled ? 'disabled' : 'enabled') + ' successfully');
    } else {
        console.error('[SUBTITLE] Subtitle button element not found!');
    }
}

// 전역 스코프에 유틸리티 함수들 노출
window.speechRecognitionUtils = {
    updateConfig: updateSpeechRecognitionConfig,
    setLanguage: setSpeechRecognitionLanguage,
    getMetrics: getSpeechRecognitionMetrics,
    cleanup: cleanupSpeechRecognition,
    restart: restartSpeechRecognition,
    debug: debugSpeechRecognition,
    applyPreset: applySpeechRecognitionPreset,
    handlingSubtitleByRecording: handlingSubtitleByRecording
}; 