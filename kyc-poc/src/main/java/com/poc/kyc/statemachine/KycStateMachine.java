package com.poc.kyc.statemachine;

import com.poc.kyc.model.AuditEntry;
import com.poc.kyc.model.KycCase;
import com.poc.kyc.model.KycEvent;
import com.poc.kyc.model.KycState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Custom lightweight state machine. The transition table is the contract:
 * each (state, event) maps to a single next state. Anything else throws
 * IllegalTransitionException — this is intentional, so async events arriving
 * out of order cannot silently corrupt state.
 *
 * The machine is stateless; the KycCase carries its own current state.
 * Transitions are guarded by synchronizing on the case to keep the audit log
 * and state field consistent under concurrent async callbacks.
 */
@Component
public class KycStateMachine {

    private static final Logger log = LoggerFactory.getLogger(KycStateMachine.class);

    private final Map<TransitionKey, KycState> transitions = new HashMap<>();

    public KycStateMachine() {
        buildTransitionTable();
    }

    private void buildTransitionTable() {
        // Happy path
        add(KycState.REGISTERED, KycEvent.UPLOAD_ID, KycState.ID_UPLOADED);
        add(KycState.ID_UPLOADED, KycEvent.START_OCR, KycState.OCR_IN_PROGRESS);
        add(KycState.OCR_IN_PROGRESS, KycEvent.OCR_SUCCESS, KycState.OCR_COMPLETED);
        add(KycState.OCR_COMPLETED, KycEvent.START_ADDRESS_VERIFY, KycState.ADDRESS_VERIFICATION_IN_PROGRESS);
        add(KycState.ADDRESS_VERIFICATION_IN_PROGRESS, KycEvent.ADDRESS_VERIFY_SUCCESS, KycState.ADDRESS_VERIFIED);
        add(KycState.ADDRESS_VERIFIED, KycEvent.SEND_TO_MANUAL_REVIEW, KycState.PENDING_MANUAL_REVIEW);
        add(KycState.PENDING_MANUAL_REVIEW, KycEvent.MANUAL_APPROVE, KycState.APPROVED);
        add(KycState.PENDING_MANUAL_REVIEW, KycEvent.MANUAL_REJECT, KycState.REJECTED);

        // Failure paths
        add(KycState.OCR_IN_PROGRESS, KycEvent.OCR_FAILURE, KycState.OCR_FAILED);
        add(KycState.OCR_FAILED, KycEvent.RETRY, KycState.OCR_IN_PROGRESS);
        add(KycState.OCR_FAILED, KycEvent.MOVE_TO_DLQ, KycState.DEAD_LETTER);

        add(KycState.ADDRESS_VERIFICATION_IN_PROGRESS, KycEvent.ADDRESS_VERIFY_FAILURE, KycState.ADDRESS_VERIFICATION_FAILED);
        add(KycState.ADDRESS_VERIFICATION_FAILED, KycEvent.RETRY, KycState.ADDRESS_VERIFICATION_IN_PROGRESS);
        add(KycState.ADDRESS_VERIFICATION_FAILED, KycEvent.MOVE_TO_DLQ, KycState.DEAD_LETTER);

        // Replay from DLQ — admin can push back to last in-progress state
        add(KycState.DEAD_LETTER, KycEvent.REPLAY_FROM_DLQ, KycState.ID_UPLOADED);
    }

    private void add(KycState from, KycEvent event, KycState to) {
        transitions.put(new TransitionKey(from, event), to);
    }

    public boolean canFire(KycState current, KycEvent event) {
        return transitions.containsKey(new TransitionKey(current, event));
    }

    public Set<KycEvent> allowedEvents(KycState current) {
        return transitions.keySet().stream()
                .filter(k -> k.from() == current)
                .map(TransitionKey::event)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * Fire an event on a case. Synchronized on the case instance so concurrent
     * async callbacks (OCR completing while a retry is being queued) can't
     * interleave and produce an inconsistent state/audit pair.
     */
    public void fire(KycCase kycCase, KycEvent event, String actor, String detail) {
        synchronized (kycCase) {
            KycState current = kycCase.getState();
            KycState next = transitions.get(new TransitionKey(current, event));
            if (next == null) {
                log.warn("Illegal transition on case {} — state={} event={}", kycCase.getId(), current, event);
                throw new IllegalTransitionException(current, event);
            }
            log.info("[{}] {} --({})--> {}  ({})", kycCase.getId(), current, event, next, actor);
            kycCase.setState(next);
            kycCase.addAuditEntry(AuditEntry.of(current, next, event, actor, detail));
        }
    }

    private record TransitionKey(KycState from, KycEvent event) {}
}
