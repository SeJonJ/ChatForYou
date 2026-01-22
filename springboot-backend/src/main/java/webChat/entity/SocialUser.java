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
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long idx;
    @Column
    private String email;
    @Column
    private String nickname;
    @Column(name = "photo_url")
    private String photoUrl;
    @Column
    private String type;
    @Column(name = "create_date")
    private long createDate;
    @Column(name = "update_date")
    private long updateDate;
    @Column(name = "last_login_date")
    private long lastLoginDate;
}
