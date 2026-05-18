package webChat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@SpringBootTest
class ChatApplicationTests {

	@MockBean
	private ServletServerContainerFactoryBean webSocketContainer;

	@Test
	void contextLoads() {
	}

}
