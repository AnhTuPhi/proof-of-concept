package com.vndirect.poc.merge.web;

import com.vndirect.poc.merge.domain.*;
import com.vndirect.poc.merge.repo.*;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Component
public class SeedData {

    private final UserRepository userRepo;
    private final AuthMethodRepository authRepo;
    private final TradeOrderRepository orderRepo;
    private final WatchlistRepository watchlistRepo;
    private final AuditEventRepository auditRepo;

    public SeedData(UserRepository userRepo,
                    AuthMethodRepository authRepo,
                    TradeOrderRepository orderRepo,
                    WatchlistRepository watchlistRepo,
                    AuditEventRepository auditRepo) {
        this.userRepo = userRepo;
        this.authRepo = authRepo;
        this.orderRepo = orderRepo;
        this.watchlistRepo = watchlistRepo;
        this.auditRepo = auditRepo;
    }

    @PostConstruct
    public void seed() { reset(); }

    @Transactional
    public void reset() {
        orderRepo.deleteAllInBatch();
        watchlistRepo.deleteAllInBatch();
        authRepo.deleteAllInBatch();
        auditRepo.deleteAllInBatch();
        userRepo.deleteAllInBatch();

        // User A: signed up via Google 2 years ago, forgot
        User a = userRepo.save(new User("Anh Phi", "anh.phi.old@gmail.com", "+84-901-111-111"));
        authRepo.save(new AuthMethod(a.getId(), AuthProvider.GOOGLE, "g-104782311", "anh.phi.old@gmail.com"));
        orderRepo.save(new TradeOrder(a.getId(), "VND", new BigDecimal("100"), new BigDecimal("18.5")));
        orderRepo.save(new TradeOrder(a.getId(), "FPT", new BigDecimal("50"), new BigDecimal("142.0")));
        watchlistRepo.save(new Watchlist(a.getId(), "Old Favorites", "VND,FPT,HPG"));
        auditRepo.save(new AuditEvent(a.getId(), "ACCOUNT_CREATED", "via Google OAuth"));

        // User B: just signed up via email
        User b = userRepo.save(new User("Phi Anh Tu", "phi.anh@vndirect.com.vn", "+84-902-222-222"));
        authRepo.save(new AuthMethod(b.getId(), AuthProvider.EMAIL_PASSWORD, "phi.anh@vndirect.com.vn", "phi.anh@vndirect.com.vn"));
        orderRepo.save(new TradeOrder(b.getId(), "VIC", new BigDecimal("20"), new BigDecimal("45.0")));
        watchlistRepo.save(new Watchlist(b.getId(), "Today", "VIC,MSN,VHM"));
        auditRepo.save(new AuditEvent(b.getId(), "ACCOUNT_CREATED", "via email signup"));
    }
}
