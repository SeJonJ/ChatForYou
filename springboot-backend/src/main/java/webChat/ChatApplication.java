package webChat;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Date;
import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
public class ChatApplication {

	public static void main(String[] args) {
		SpringApplication.run(ChatApplication.class, args);
	}

	@PostConstruct
	public void started() {
		// 애플리케이션 전역 타임존을 Asia/Seoul로 고정
		System.out.println("###########################################################################");
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
		System.out.println("############### ChatForYou TimeZone :: " + TimeZone.getDefault().getID() + "###############");
		System.out.println("############### ChatForYou StartAt :: " + new Date() + "###############");
		System.out.println("###########################################################################");

	}

}
