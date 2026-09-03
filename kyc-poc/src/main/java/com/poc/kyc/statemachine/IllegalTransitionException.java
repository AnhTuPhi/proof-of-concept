package com.poc.kyc.statemachine;

import com.poc.kyc.model.KycEvent;
import com.poc.kyc.model.KycState;

public class IllegalTransitionException extends RuntimeException {
    public IllegalTransitionException(KycState from, KycEvent event) {
        super("Illegal transition: cannot fire event %s while in state %s".formatted(event, from));
    }
}
