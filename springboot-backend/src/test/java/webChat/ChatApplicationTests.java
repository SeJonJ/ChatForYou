package webChat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import webChat.support.ExternalTest;

// 전체 컨텍스트 로드 시 MariaDB/Redis/Kafka 등 실제 인프라가 필요하므로 CI 기본 빌드에서 제외
@ExternalTest
@SpringBootTest
class ChatApplicationTests {

	@MockBean
	private ServletServerContainerFactoryBean webSocketContainer;

	@Test
	void contextLoads() {
	}

}
