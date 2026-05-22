package webChat.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class JsonUtilsTest {

    @Test
    @DisplayName("jsonToObj 는 유효하지 않은 JSON 문자열이면 JSON_CONVERSION_ERROR 를 던진다")
    void jsonToObj_withInvalidJson_throwsChatForYouException_I002() {
        // given
        String invalidJson = "{ not valid json @@@ }";

        // when & then
        assertThatThrownBy(() -> JsonUtils.jsonToObj(invalidJson, String.class))
                .isInstanceOf(ChatForYouException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.JSON_CONVERSION_ERROR);
    }

    @Test
    @DisplayName("jsonToObj 는 JSON_CONVERSION_ERROR 에 detail 메시지를 포함한다")
    void jsonToObj_withInvalidJson_exceptionContainsDetail() {
        // given
        String invalidJson = "{ broken";

        // when & then
        assertThatThrownBy(() -> JsonUtils.jsonToObj(invalidJson, String.class))
                .isInstanceOf(ChatForYouException.class)
                .satisfies(ex -> {
                    ChatForYouException cfEx = (ChatForYouException) ex;
                    assertThat(cfEx.getDetail()).isEqualTo("json→object 변환 실패");
                });
    }

    @Test
    @DisplayName("objToJson 는 직렬화 불가능한 객체이면 JSON_CONVERSION_ERROR 를 던진다")
    void objToJson_withUnserializableObject_throwsChatForYouException_I002() {
        // given — 순환 참조로 직렬화 불가능한 구조 생성
        Object selfRef = new Object() {
            @SuppressWarnings("unused")
            public final Object self = this; // 직접 순환 참조는 ObjectMapper가 거부
        };

        // when & then
        assertThatThrownBy(() -> JsonUtils.objToJson(selfRef))
                .isInstanceOf(ChatForYouException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.JSON_CONVERSION_ERROR);
    }

    @Test
    @DisplayName("objToJson 는 JSON_CONVERSION_ERROR 에 cause 를 보존한다")
    void objToJson_withUnserializableObject_exceptionPreservesCause() {
        // given
        Object selfRef = new Object() {
            @SuppressWarnings("unused")
            public final Object self = this;
        };

        // when & then
        assertThatThrownBy(() -> JsonUtils.objToJson(selfRef))
                .isInstanceOf(ChatForYouException.class)
                .satisfies(ex -> assertThat(ex.getCause()).isNotNull());
    }

    @Test
    @DisplayName("jsonToObj 는 유효한 JSON 이면 정상 변환한다")
    void jsonToObj_withValidJson_returnsExpectedObject() {
        // given
        String validJson = "\"hello\"";

        // when
        String result = JsonUtils.jsonToObj(validJson, String.class);

        // then
        assertThat(result).isEqualTo("hello");
    }

    @Test
    @DisplayName("objToJson 는 일반 객체이면 JSON 문자열로 변환한다")
    void objToJson_withSimpleObject_returnsJsonString() {
        // given
        String input = "hello";

        // when
        String result = JsonUtils.objToJson(input);

        // then
        assertThat(result).isEqualTo("\"hello\"");
    }
}
