/**
 * 공지사항 관련 Popup 관리 객체
 * - 업데이트 내역 modal (#updateHistoryModal)
 * - 이용약관 및 개인정보처리방침 modal (#announcementModal)
 */
const AnnouncementPopup = {
  
  /**
   * 초기화 함수
   */
  init: function() {
    this.bindEvents();
    this.initAnnouncement();
    console.log('AnnouncementPopup initialized');
  },

  /**
   * 이벤트 바인딩
   */
  bindEvents: function() {
    const self = this;
    
    // 업데이트 내역 버튼 클릭 이벤트
    $(document).off('click', '#showUpdatesButton').on('click', '#showUpdatesButton', function() {
      self.showUpdateHistory();
    });
    
    // 공지사항 modal 숨김 이벤트
    $('#announcementModal').off('hide.bs.modal').on('hide.bs.modal', function (event) {
      if (document.getElementById('dontShowAgain') && document.getElementById('dontShowAgain').checked) {
        sessionStorage.setItem('hideAnnouncement', 'true');
      }
    });
    
    // 동의 버튼 클릭 이벤트
    $(document).off('click', '#agreeBtn').on('click', '#agreeBtn', function() {
      self.handleAgree();
    });
  },

  /**
   * 업데이트 내역 modal 표시
   */
  showUpdateHistory: function() {
    try {
      const updateHistoryModal = new bootstrap.Modal($('#updateHistoryModal'));
      updateHistoryModal.show();
    } catch (error) {
      console.error('Error showing update history modal:', error);
      // fallback
      $('#updateHistoryModal').modal('show');
    }
  },

  /**
   * 공지사항 modal 초기화 및 표시 로직
   */
  initAnnouncement: function() {
    const self = this;
    
    // sessionStorage에서 숨김 설정 확인
    const hideAnnouncement = sessionStorage.getItem('hideAnnouncement');
    
    if (!hideAnnouncement || hideAnnouncement === 'false') {
      // 공지사항 modal 표시
      $('#announcementModal').modal('show');
    } else {
      // 공지사항 modal 숨김
      $('#announcementModal').modal('hide');
    }
  },

  /**
   * 동의 버튼 클릭 처리
   */
  handleAgree: function() {
    try {
      // 방문자 체크 (roomList 객체가 있는 경우)
      if (window.roomList && typeof window.roomList.checkVisitor === 'function') {
        window.roomList.checkVisitor();
      }
      
      // 사용자 동의 API 호출
      // TODO: 향후 사용자 동의 API 연동 예정
      // if (window.__CONFIG__ && window.__CONFIG__.API_BASE_URL) {
      //   fetchJson(window.__CONFIG__.API_BASE_URL + "/user_agree", { method: 'GET' }, '이용약관 동의 처리에 실패했습니다.')
      //     .then(() => { 
      //       console.info("user agree!!"); 
      //     })
      //     .catch(error => {
      //       console.error("Error in user agree API:", error);
      //     });
      // }
      
      // modal 닫기
      $('#announcementModal').modal('hide');
      
    } catch (error) {
      console.error('Error in handleAgree:', error);
      // 에러가 발생해도 modal은 닫기
      $('#announcementModal').modal('hide');
    }
  },

  /**
   * 공지사항 modal 강제 표시
   */
  showAnnouncement: function() {
    $('#announcementModal').modal('show');
  },

  /**
   * 공지사항 modal 숨김
   */
  hideAnnouncement: function() {
    $('#announcementModal').modal('hide');
  },

  /**
   * 오늘 더 이상 보지 않기 설정
   */
  setDontShowToday: function() {
    sessionStorage.setItem('hideAnnouncement', 'true');
  },

  /**
   * 공지사항 표시 설정 초기화
   */
  resetAnnouncementSettings: function() {
    sessionStorage.removeItem('hideAnnouncement');
  }
};

// DOM이 준비되면 자동 초기화
$(document).ready(function() {
  AnnouncementPopup.init();
});

// 전역 객체로 노출
window.AnnouncementPopup = AnnouncementPopup;
