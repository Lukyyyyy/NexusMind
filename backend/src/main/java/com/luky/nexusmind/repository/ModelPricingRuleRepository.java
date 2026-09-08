package com.luky.nexusmind.repository;

import com.luky.nexusmind.model.ModelPricingRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ModelPricingRuleRepository extends JpaRepository<ModelPricingRule, Long> {
    Optional<ModelPricingRule> findByModelName(String modelName);
}
