package com.vndirect.poc.merge.service;

import com.vndirect.poc.merge.domain.*;
import com.vndirect.poc.merge.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class AccountMergeService {

    private final UserRepository userRepo;
    private final AuthMethodRepository authRepo;
    private final TradeOrderRepository orderRepo;
    private final WatchlistRepository watchlistRepo;
    private final AuditEventRepository auditRepo;

    public AccountMergeService(UserRepository userRepo,
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

    public ConflictPreview previewMerge(Long sourceId, Long targetId) {
        User source = require(sourceId);
        User target = require(targetId);
        List<ConflictPreview.FieldConflict> conflicts = new ArrayList<>();
        if (!Objects.equals(source.getPrimaryEmail(), target.getPrimaryEmail())) {
            conflicts.add(new ConflictPreview.FieldConflict("primaryEmail",
                source.getPrimaryEmail(), target.getPrimaryEmail()));
        }
        if (!Objects.equals(source.getDisplayName(), target.getDisplayName())) {
            conflicts.add(new ConflictPreview.FieldConflict("displayName",
                source.getDisplayName(), target.getDisplayName()));
        }
        if (!Objects.equals(source.getPhone(), target.getPhone())) {
            conflicts.add(new ConflictPreview.FieldConflict("phone",
                nullSafe(source.getPhone()), nullSafe(target.getPhone())));
        }
        int srcRows = orderRepo.findByUserId(sourceId).size()
            + watchlistRepo.findByUserId(sourceId).size()
            + authRepo.findByUserId(sourceId).size();
        int tgtRows = orderRepo.findByUserId(targetId).size()
            + watchlistRepo.findByUserId(targetId).size()
            + authRepo.findByUserId(targetId).size();
        return new ConflictPreview(sourceId, targetId, conflicts, srcRows, tgtRows);
    }

    /**
     * Soft merge: source's data is reassigned to target by user_id update across every FK table,
     * and the source user is converted to a MERGED_REDIRECT pointing at target. Never hard-deleted —
     * a future login on the source's auth method still resolves to the surviving user.
     */
    @Transactional
    public MergeReport merge(MergeRequest req) {
        User source = require(req.sourceUserId());
        User target = require(req.targetUserId());
        if (source.getStatus() != UserStatus.ACTIVE || target.getStatus() != UserStatus.ACTIVE) {
            throw new IllegalStateException("Both users must be ACTIVE to merge");
        }
        if (Objects.equals(source.getId(), target.getId())) {
            throw new IllegalArgumentException("source and target are the same");
        }

        int conflicts = applyConflictResolution(target, req);

        Map<String, Integer> reassigned = new LinkedHashMap<>();
        reassigned.put("auth_methods", authRepo.reassignUserId(source.getId(), target.getId()));
        reassigned.put("trade_orders", orderRepo.reassignUserId(source.getId(), target.getId()));
        reassigned.put("watchlists", watchlistRepo.reassignUserId(source.getId(), target.getId()));

        source.setStatus(UserStatus.MERGED_REDIRECT);
        source.setMergedIntoUserId(target.getId());
        source.setMergedAt(Instant.now());
        userRepo.save(source);
        userRepo.save(target);

        auditRepo.save(new AuditEvent(target.getId(), "ACCOUNT_MERGED",
            "absorbed user " + source.getId() + ", reassigned=" + reassigned));
        auditRepo.save(new AuditEvent(source.getId(), "ACCOUNT_MERGED_INTO",
            "redirected to user " + target.getId()));

        return new MergeReport(target.getId(), source.getId(), reassigned, conflicts);
    }

    public Optional<User> resolveLogin(AuthProvider provider, String externalId) {
        return authRepo.findByProviderAndExternalId(provider, externalId)
            .map(am -> userRepo.findById(am.getUserId()).orElseThrow())
            .map(this::followRedirect);
    }

    private User followRedirect(User u) {
        User cur = u;
        int hops = 0;
        while (cur.getStatus() == UserStatus.MERGED_REDIRECT && cur.getMergedIntoUserId() != null) {
            if (++hops > 8) throw new IllegalStateException("redirect cycle for user " + u.getId());
            cur = userRepo.findById(cur.getMergedIntoUserId()).orElseThrow();
        }
        return cur;
    }

    private int applyConflictResolution(User target, MergeRequest req) {
        int n = 0;
        if (req.winningEmail() != null && !req.winningEmail().equals(target.getPrimaryEmail())) {
            target.setPrimaryEmail(req.winningEmail()); n++;
        }
        if (req.winningDisplayName() != null && !req.winningDisplayName().equals(target.getDisplayName())) {
            target.setDisplayName(req.winningDisplayName()); n++;
        }
        if (req.winningPhone() != null && !req.winningPhone().equals(target.getPhone())) {
            target.setPhone(req.winningPhone()); n++;
        }
        return n;
    }

    private User require(Long id) {
        return userRepo.findById(id)
            .orElseThrow(() -> new NoSuchElementException("user " + id + " not found"));
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }
}
