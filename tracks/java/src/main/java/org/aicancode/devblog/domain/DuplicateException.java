package org.aicancode.devblog.domain;

/** Thrown by a repository when a unique constraint (e-mail, slug) is violated. */
public class DuplicateException extends RuntimeException {

    public DuplicateException(String what) {
        super(what);
    }
}
