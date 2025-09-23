package webChat.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "social_user")
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SocialUser {
    @Id
    private String email;

    @Column
    private String accessToken;
    @Column
    private String refreshToken;
    @Column
    private String name;
    @Column
    private String photo;
    @Column
    private String type;
}
