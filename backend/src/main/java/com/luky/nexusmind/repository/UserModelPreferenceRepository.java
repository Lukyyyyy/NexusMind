package com.luky.nexusmind.repository;

import com.luky.nexusmind.model.UserModelPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserModelPreferenceRepository extends JpaRepository<UserModelPreference, Long> {
    Optional<UserModelPreference> findByUserId(Long userId);
}
