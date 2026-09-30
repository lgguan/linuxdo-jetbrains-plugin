package com.lgguan.linuxdo.plugin.net;

import com.intellij.credentialStore.CredentialAttributes;

/** Select the stable JVM overload, rather than the old Kotlin default-argument constructor. */
public final class PasswordSafeAttributes {
    private PasswordSafeAttributes() {}

    public static CredentialAttributes forService(String serviceName) {
        return new CredentialAttributes(serviceName);
    }
}
