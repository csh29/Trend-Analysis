package com.sjinc.trendanalysis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

/** 테이블명을 "user"로 두면 H2 등 다수 DB의 예약어와 충돌해서 DDL이 깨지므로 "users"로 분리. */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    /** 사용자명(화면 표시용 이름). 로그인 아이디는 username이다. */
    @Column(name = "user_nm", nullable = false)
    private String userNm;

    /** BCrypt로 인코딩한 값만 저장한다. */
    @Column(nullable = false)
    private String password;

    /** "ROLE_" 접두어 없이 저장 (예: "ADMIN", "USER"). 값의 출처는 공통코드 ROLE이다. */
    @Column(nullable = false)
    private String role;

    /**
     * 사용여부(Y/N). N이면 로그인할 수 없다. 기본값은 @ColumnDefault로 준다 — columnDefinition에 "default 'Y' not null"까지
     * 넣으면 ddl-auto=update가 컬럼 타입을 맞출 때 그 문자열을 "set data type" 뒤에 그대로 붙여 H2 문법 오류가 났다.
     */
    @Builder.Default
    @ColumnDefault("'Y'")
    @Column(name = "use_yn", nullable = false, length = 1)
    private String useYn = "Y";

    public boolean isActive() {
        return !"N".equals(useYn);
    }

    /** 본인 비밀번호 변경(UserService.changeMyPassword). 인코딩된 값을 받는다. */
    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
    }
}
