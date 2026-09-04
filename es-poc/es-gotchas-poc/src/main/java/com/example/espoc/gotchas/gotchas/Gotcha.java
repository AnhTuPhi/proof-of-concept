package com.example.espoc.gotchas.gotchas;

import java.util.Map;

public interface Gotcha {
    /** Short slug used in the URL. */
    String name();

    /** Plain-text explanation: symptom, why, fix. */
    String explain();

    /** Trigger the pitfall. Caller may pass parameters via the map. */
    Map<String, Object> trigger(Map<String, String> params) throws Exception;

    /** Apply the resolution. Caller may pass parameters via the map. */
    Map<String, Object> fix(Map<String, String> params) throws Exception;
}
