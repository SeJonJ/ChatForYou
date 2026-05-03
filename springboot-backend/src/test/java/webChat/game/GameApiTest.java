package webChat.game;


import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import webChat.model.game.GameSubjects;
import webChat.model.game.GameTitles;
import webChat.support.ExternalTest;
import webChat.utils.HttpUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 로컬 Python 게임 API가 실제로 떠 있어야만 통과하는 외부 연동 테스트.
 *
 * <p>기본 build/test 경로에서는 제외하고, 필요할 때만
 * {@code ./gradlew externalTest}로 수동 실행한다.</p>
 */
@ExternalTest
@SpringBootTest
@TestPropertySource(locations = "/application.properties")
@Slf4j
class GameApiTest {

    @Test
    @DisplayName("python api get test")
    void  getTitles() throws Exception {
        String url = "http://localhost:8000/game_title";
        GameTitles titles = HttpUtil.get(url, new HttpHeaders(), new ConcurrentHashMap<>(), GameTitles.class);
        log.info("titles :: {}",titles.toString());

        this.getSubjects(titles.getTitles().get(2));
    }

    @Test
    @DisplayName("python api post test")
    void  getSubjects(String title) throws Exception {
        String url = "http://localhost:8000/game_subject";
        GameSubjects subjects = new GameSubjects(title, Arrays.asList("호랑이", "고양이", "강아지"), new ArrayList<>(), "hard");
        subjects = HttpUtil.post(url, new HttpHeaders(), new ConcurrentHashMap<>(), subjects, GameSubjects.class);
        log.info("subjects :: {}",subjects.toString());
    }
}
