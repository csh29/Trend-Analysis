package com.sjinc.trendanalysis.repository;

import com.sjinc.trendanalysis.domain.Program;
import com.sjinc.trendanalysis.domain.UserProgramPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserProgramPermissionRepository extends JpaRepository<UserProgramPermission, Long> {

    /** 사이드바 메뉴·API 접근 판단 — 그 사용자가 권한을 가진 사용중 프로그램(정렬순서대로). */
    @Query("select perm.program from UserProgramPermission perm "
            + "where perm.user.username = :username "
            + "and perm.program.useYn = 'Y' "
            + "order by perm.program.sortOrder asc")
    List<Program> findAccessiblePrograms(@Param("username") String username);

    List<UserProgramPermission> findByUserId(Long userId);

    /** 화면 툴바 버튼을 정할 때 쓴다 — 그 사용자의 그 프로그램 권한 행(없으면 접근 권한도 없음). */
    Optional<UserProgramPermission> findByUserUsernameAndProgramProgramId(String username, String programId);

    /**
     * 벌크 DELETE로 즉시 실행된다. 권한 재설정 때 같은 트랜잭션에서 같은 (user_id, program_id)를 다시 넣는데,
     * Hibernate가 삭제보다 삽입을 먼저 flush해 unique 제약을 위반하는 문제를 피한다.
     */
    @Modifying(clearAutomatically = true)
    @Query("delete from UserProgramPermission perm where perm.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    /** 프로그램 삭제 시 그 프로그램을 가리키는 권한 행을 먼저 지운다(FK). */
    @Modifying(clearAutomatically = true)
    @Query("delete from UserProgramPermission perm where perm.program.programId = :programId")
    void deleteByProgramId(@Param("programId") String programId);
}
