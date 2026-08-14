package com.javafxlogin.core.ipc;

/**
 * A request the AuthenticationService will answer.
 *
 * <p>The set is closed and grows one ticket at a time; a client cannot ask for anything the service
 * has not agreed to answer. Requests carry their password material as {@code char[]} so that it
 * never becomes an interned String, and every implementation redacts itself when printed.
 */
public sealed interface Request permits Authenticate, Bootstrap {}
