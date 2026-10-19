package org.veriagent.core.security;

/**
 * Regla anti-suplantación: la identidad de quien decide sale del token. Si el cuerpo trae un campo de
 * identidad (por ejemplo {@code reviewer}), se acepta solo si coincide con el token; si no, la operación
 * se rechaza con 403.
 */
public final class IdentityGuard {
    private IdentityGuard() {}

    /** Devuelve el actor del token; lanza {@link ImpersonationException} si el cuerpo declara a otra persona. */
    public static Actor requireConsistent(String claimedIdentity) {
        Actor actor = CurrentActor.get();
        if (claimedIdentity != null && !claimedIdentity.isBlank() && !claimedIdentity.equals(actor.subject())) {
            throw new ImpersonationException(claimedIdentity, actor.subject());
        }
        return actor;
    }

    /** Intento de actuar en nombre de otra identidad. */
    public static class ImpersonationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ImpersonationException(String claimed, String actual) {
            super("La identidad del cuerpo ('" + claimed + "') no coincide con la del token ('" + actual
                + "'); la identidad de quien decide se toma siempre del token");
        }
    }
}
