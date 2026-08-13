package com.javafxlogin.core.ipc;

/**
 * Why a request other than an authentication attempt was refused.
 *
 * <p>Unlike {@link DeniedReason} these may be specific, because they answer a request the caller had
 * to be authorised to make in the first place — or, in Bootstrap's case, one whose answer a fresh
 * install reveals anyway.
 */
public enum ErrorCode {

    /** Bootstrap was attempted when the single Administrator already exists. */
    ADMINISTRATOR_EXISTS,

    /**
     * The CredentialStore could not be read or written. Says nothing about why: the caller can only
     * retry or give up either way, and the detail belongs in the service's own record, not in a
     * response an unprivileged client receives.
     */
    STORE_UNAVAILABLE
}
