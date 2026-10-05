package io.github.markusluisflores.frontrow.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HoldRequestRepository extends JpaRepository<HoldRequest, HoldRequestId> {}
