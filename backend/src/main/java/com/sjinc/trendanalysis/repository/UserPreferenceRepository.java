package com.sjinc.trendanalysis.repository;

import com.sjinc.trendanalysis.domain.UserPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {

    Optional<UserPreference> findByUserUsernameAndPrefKey(String username, String prefKey);

    /** 사용자 삭제 시 그 사용자의 설정을 먼저 지운다(FK). */
    @Modifying(clearAutomatically = true)
    @Query("delete from UserPreference pref where pref.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
