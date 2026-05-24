package net.minestom.server.instance.chunksystem;

import java.io.Serial;

public class ChunkSystemException extends Exception {

    /**
     * Use serialVersionUID from JDK 1.1 for interoperability.
     */
    @Serial
    private static final long serialVersionUID = 484786118357076600L;

    public ChunkSystemException(String message, Throwable cause) {
        super(message, cause);
    }
}
