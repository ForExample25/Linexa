package com.termux.terminal;

/**
 * The interface for communication between {@link TerminalSession} and its client. It is used to
 * send callbacks to the client when {@link TerminalSession} changes or for sending other
 * back data to the client like logs.
 */
public interface TerminalSessionClient {

    void onTextChanged(TerminalSession changedSession);

    /**
     * Phase M0-A: called once, on the reader thread, when the first bytes are
     * read from the pty master. A default no-op keeps every existing client
     * source-compatible; the app's PTY adapter overrides it for E6.
     */
    default void onFirstOutput(TerminalSession changedSession, int bytesRead) {
    }

    void onTitleChanged(TerminalSession changedSession);

    void onSessionFinished(TerminalSession finishedSession);

    void onCopyTextToClipboard(TerminalSession session, String text);

    void onPasteTextFromClipboard(TerminalSession session);

    void onBell(TerminalSession session);

    void onColorsChanged(TerminalSession session);

    void onTerminalCursorStateChange(boolean state);



    Integer getTerminalCursorStyle();



    void logError(String tag, String message);

    void logWarn(String tag, String message);

    void logInfo(String tag, String message);

    void logDebug(String tag, String message);

    void logVerbose(String tag, String message);

    void logStackTraceWithMessage(String tag, String message, Exception e);

    void logStackTrace(String tag, Exception e);

}
