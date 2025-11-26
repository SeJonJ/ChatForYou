package webChat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

@Entity
@Getter
@Setter
@Table(name = "friends")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Friend {
    @Id
    private int idx;
    @Column(name = "user_id")
    private String userId;
    @Column(name = "friend_id")
    private String friendId;
    @Column
    private String nickname;
    @Column
    private String status;
    @Column(name = "create_date")
    private long createDate;
    @Column(name = "update_date")
    private long updateDate;
}
