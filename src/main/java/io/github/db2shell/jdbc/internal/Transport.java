package io.github.db2shell.jdbc.internal;

import java.io.InputStream;
import java.io.OutputStream;

/** The byte pipes to a running db2 utility. Abstracted so that the protocol can be unit tested. */
public interface Transport {

    /** The utility's standard output. */
    InputStream getInput();

    /** The utility's standard input. */
    OutputStream getOutput();

    /** Anything written to standard error so far (best effort, may be empty). */
    String getErrorText();

    /** True while the underlying process is running. */
    boolean isAlive();

    /** Forcibly terminates the process. Must be safe to call from any thread, any number of times. */
    void kill();

    /** Politely ends the process (stdin is already closed), waiting a bounded amount of time. */
    void close();
}
