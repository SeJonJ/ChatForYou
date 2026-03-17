package webChat.config;

import kr.co.shineware.nlp.komoran.constant.DEFAULT_MODEL;
import kr.co.shineware.nlp.komoran.core.Komoran;
import lombok.Getter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Getter
@Configuration
public class KomoranConfig {
    @Bean
    Komoran komoran() {
        return new Komoran(DEFAULT_MODEL.FULL);
    }
}
