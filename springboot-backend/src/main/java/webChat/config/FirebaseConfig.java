package webChat.config;

import com.google.auth.oauth2.GoogleCredentials;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.auth.oauth2.GoogleCredentials;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

@Component
public class FirebaseConfig {

    @PostConstruct
    public void initializeFirebase() throws IOException {
        GoogleCredentials credentials;

        // 1단계: GOOGLE_APPLICATION_CREDENTIALS 환경변수 확인
        String credentialsPath = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");

        if (credentialsPath != null && !credentialsPath.isEmpty()) {
            // 환경변수가 있으면 해당 경로의 파일 사용
            FileInputStream serviceAccount = new FileInputStream(credentialsPath);
            credentials = GoogleCredentials.fromStream(serviceAccount);
            System.out.println("Firebase 초기화: GOOGLE_APPLICATION_CREDENTIALS 사용");
        } else {
            // 환경변수가 없으면 기본 경로에서 JSON 파일 찾기
            credentials = loadCredentialsFromDefaultPath();
            System.out.println("Firebase 초기화: 기본 JSON 파일 사용");
        }

        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(credentials)
                .build();

        FirebaseApp.initializeApp(options);
    }

    private GoogleCredentials loadCredentialsFromDefaultPath() throws IOException {
        ClassPathResource resource = new ClassPathResource("firebase/google_account_key.json");

        if (resource.exists()) {
            InputStream inputStream = resource.getInputStream();
            GoogleCredentials credentials = GoogleCredentials.fromStream(inputStream);
            return credentials;
        } else {
            //throw new RuntimeException("Firebase 설정 파일을 찾을 수 없습니다: firebase/google_account_key.json");

            // 여러 위치에서 JSON 파일 찾기
            String[] possiblePaths = {
                    "/etc/firebase/google_account_key.json",  // K8s 마운트 경로
                    //"firebase-service-account.json"  // 현재 디렉토리
            };

            for (String path : possiblePaths) {
                try {
                    File file = new File(path);
                    if (file.exists()) {
                        System.out.println("Firebase JSON 파일 발견: " + path);
                        return GoogleCredentials.fromStream(new FileInputStream(file));
                    }
                } catch (IOException e) {
                    System.out.println("파일 읽기 실패: " + path);
                }
            }

            throw new RuntimeException("Firebase 설정 파일을 찾을 수 없습니다.");
        }


    }
}
