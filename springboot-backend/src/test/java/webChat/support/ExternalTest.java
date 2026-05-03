package webChat.support;

import org.junit.jupiter.api.Tag;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 외부 환경에 의존하는 테스트를 구분하기 위한 프로젝트 전용 어노테이션.
 *
 * <p>이 어노테이션이 붙은 테스트는 다음 특성을 가진다.</p>
 * <ul>
 *     <li>로컬 개발자의 기본 {@code ./gradlew test}, {@code ./gradlew build} 실행에서 제외된다.</li>
 *     <li>Docker build 및 GitHub Actions의 기본 빌드/배포 경로에서도 자동 실행되지 않는다.</li>
 *     <li>필요할 때만 별도 태스크({@code ./gradlew externalTest})로 명시 실행한다.</li>
 * </ul>
 *
 * <p>적용 대상 예시:</p>
 * <ul>
 *     <li>외부 HTTP 서버, 별도 Python API, 실제 운영 URL에 직접 요청하는 테스트</li>
 *     <li>네트워크/인프라 상태에 따라 성공 여부가 달라지는 테스트</li>
 * </ul>
 *
 * <p>JUnit 5의 {@link Tag}를 감싼 메타 어노테이션이므로, Gradle에서는
 * {@code external} 태그를 include/exclude 해서 실행 대상을 제어한다.</p>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Tag("external")
public @interface ExternalTest {
}
