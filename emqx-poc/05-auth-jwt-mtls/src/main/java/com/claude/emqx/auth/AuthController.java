package com.claude.emqx.auth;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/provision")
public class AuthController {

    private final JwtIssuer jwt;
    private final CertificateAuthority ca;

    public AuthController(JwtIssuer jwt, CertificateAuthority ca) { this.jwt = jwt; this.ca = ca; }

    /** Issue a short-lived JWT to a device. Real onboarding calls this once at first boot. */
    @PostMapping("/jwt")
    public JwtIssuer.Token issueJwt(@RequestParam String deviceId,
                                    @RequestParam(defaultValue = "tenant-a") String tenant) {
        return jwt.issue(deviceId, tenant);
    }

    /** Generate a per-device X.509 cert + key. Caller stores both on the device. */
    @PostMapping("/cert")
    public CertificateAuthority.DeviceCert issueCert(@RequestParam String deviceId) throws Exception {
        return ca.issueForDevice(deviceId);
    }

    /** CA path so the device can verify the broker's server cert. */
    @GetMapping("/ca")
    public String caPath() { return ca.getCaCertPath().toString(); }
}
