package com.sjinc.trendanalysis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 사용자별 화면 설정(개인화) 한 건 — 설정 키마다 한 행, 값은 그 키가 정한 JSON. 브라우저가 아니라 서버에 두어
 * 다른 PC·브라우저에서도 같은 설정을 쓴다(2026-10-23 사용자 결정). 지금 키는 홈 배치(home-layout) 하나.
 */
@Entity
@Table(name = "user_preferences",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "pref_key"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", referencedColumnName = "user_id", nullable = false)
    private User user;

    @Column(name = "pref_key", nullable = false, length = 50)
    private String prefKey;

    @Column(name = "pref_value", nullable = false, length = 4000)
    private String prefValue;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void change(String value) {
        this.prefValue = value;
        this.updatedAt = LocalDateTime.now();
    }
}
