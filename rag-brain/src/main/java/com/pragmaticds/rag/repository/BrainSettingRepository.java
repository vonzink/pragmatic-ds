package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.domain.BrainSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BrainSettingRepository extends JpaRepository<BrainSetting, String> {
}
