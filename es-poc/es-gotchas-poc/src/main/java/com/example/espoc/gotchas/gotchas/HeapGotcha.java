package com.example.espoc.gotchas.gotchas;

import com.example.espoc.common.web.ApiException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class HeapGotcha implements Gotcha {

    @Override public String name() { return "heap-too-big"; }

    @Override public String explain() {
        return """
                SYMPTOM:  ES with -Xmx64g feels SLOWER than -Xmx30g on the same hardware.

                WHY:      JVM uses compressed object pointers (32-bit pointers + scaling)
                          below ~32 GB heap. Above that boundary it falls back to 64-bit
                          pointers, doubling pointer size and cutting cache density.
                          Effective memory often DROPS above the boundary.

                FIX:      Cap ES heap at 30 GB (some margin below the ~32 GB cliff).
                          If you need more RAM: run TWO ES nodes on the same box, each
                          with 30 GB heap, splitting the data between them. You get the
                          aggregate RAM without losing compressed-oops.

                          Also: set -Xms == -Xmx so the JVM allocates the full heap upfront.
                          ES enforces this in newer versions but is worth being explicit about.
                """;
    }

    @Override
    public Map<String, Object> trigger(Map<String, String> params) {
        throw ApiException.badRequest("NO_BREAK",
                "This gotcha is a config concern — no /break endpoint. Use /explain.");
    }

    @Override
    public Map<String, Object> fix(Map<String, String> params) {
        long max = Runtime.getRuntime().maxMemory();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("appJvmMaxBytes", max);
        r.put("appJvmMaxGb", max / 1024.0 / 1024 / 1024);
        r.put("recommendation", "Cap ES JVM at 30 GB; this number is for the *app*, not ES.");
        return r;
    }
}
