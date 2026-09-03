package com.poc.kyc.statemachine;

import com.poc.kyc.model.KycEvent;
import com.poc.kyc.model.KycState;

public record Transition(KycState from, KycEvent event, KycState to) {
}
