package com.vndirect.poc.merge.repo;

import com.vndirect.poc.merge.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}
