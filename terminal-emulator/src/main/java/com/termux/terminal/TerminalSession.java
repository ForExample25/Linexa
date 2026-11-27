package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Message;
import android.os.Trace;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A terminal session, consisting of a process coupled to a terminal interface.
 * <p>
 * The subprocess will be executed by the constructor, and when the size is made known by a call to
 * {@link #updateSize(int, int, int, int)} terminal emulation will begin and threads will be spawned to handle the subprocess I/O.
 * All terminal emulation and callback methods will be performed on the main thread.
 * <p>
 * The child process may be exited forcefully by using the {@link #finishIfRunning()} method.
 * <p>
 * NOTE: The terminal session may outlive the EmulatorView, so be careful with callbacks!
 */
public final class TerminalSession extends TerminalOutput {

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_PROCESS_EXITED = 4;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /**
     * A queue written to from a separate thread when the process outputs, and read by main thread to process by
     * terminal emulator.
     */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(4096);
    /**
     * A queue written to from the main thread due to user interaction, and read by another thread which forwards by
     * writing to the {@link #mTerminalFileDescriptor}.
     */
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(4096);

    /**
     * PT-004: user input is copied here and drained by a dedicated pump thread,
     * so a full pty buffer can never block the Android main thread (key events
     * and paste both arrive there). Bounded: if the queue exceeds
     * [MAX_PENDING_INPUT_BYTES] the OLDEST chunks are dropped and an explicit
     * error is logged through the session client — never a silent truncation.
     */
    private static final int MAX_PENDING_INPUT_BYTES = 1024 * 1024;
    private final java.util.ArrayDeque<byte[]> mPendingInput = new java.util.ArrayDeque<>();
    private long mPendingInputBytes;
    private boolean mInputPumpClosed;
    private boolean mInputOverflowReported;

    /**
     * CC-003: guards emulator state that is appended on the Looper thread
     * against the best-effort snapshot reads used by previews/transcript
     * consumers on other threads. The terminal view itself stays single-threaded
     * on the Looper, so read-read concurrency never enters this lock.
     */
    public final Object mEmulatorLock = new Object();

    /** SS-001: bounded wait for a graceful SIGHUP before escalating to SIGKILL. */
    private static final long GRACEFUL_EXIT_TIMEOUT_MS = 750;
    /** Buffer to write translate code points into utf8 before writing to mTerminalToProcessIOQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    /**
     * The pid of the shell process. 0 if not started and -1 if finished running.
     *
     * CC-002: read from the writer thread ({@link #write}) and the UI thread
     * ({@link #getPid}) while {@link #cleanupResources} writes it under the
     * session monitor. {@code volatile} gives those unsynchronised readers the
     * exit value instead of a stale running pid.
     */
    volatile int mShellPid;
    /** The exit status of the shell process. Only valid if ${@link #mShellPid} is -1. */
    int mShellExitStatus;

    /**
     * The file descriptor referencing the master half of a pseudo-terminal pair, resulting from calling
     * {@link JNI#createSubprocess(String, String, String[], String[], int[], int, int, int, int)}.
     *
     * PT-002: set to -1 exactly when the master is closed, so a later resize
     * or thread wake-up can never write to a descriptor number the OS has
     * handed to a different terminal.
     */
    private volatile int mTerminalFileDescriptor = -1;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final MainThreadHandler mMainThreadHandler = new MainThreadHandler();

    /**
     * PT-003: signalled by the reader thread when the pty master reached EOF
     * (or its stream failed). The exit handler uses it to give in-flight
     * output a bounded chance to reach the emulator before the queues and fd
     * are closed.
     */
    private final java.util.concurrent.CountDownLatch mReaderFinished =
        new java.util.concurrent.CountDownLatch(1);
    private static final long OUTPUT_DRAIN_TIMEOUT_MS = 200;
    /** PT-003: cap one handler invocation's drain so a flooding child cannot starve the Looper. */
    private static final int MAX_OUTPUT_DRAIN_BYTES_PER_CALL = 64 * 1024;

    /**
     * LINEXA-PERF-003: at most one MSG_NEW_INPUT is queued at a time. The
     * reader previously posted one message per 4 KiB read, so a single
     * `seq 1 2000` burst produced dozens of main-Looper dispatches that each
     * re-entered the emulator lock; with this latch the reader contributes one
     * dispatch and the handler re-arms itself only while the 64 KiB cap left
     * work behind.
     */
    private final java.util.concurrent.atomic.AtomicBoolean mOutputDrainPending =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    // Linexa diagnostics (2026-08-30, terminal lifecycle): first-occurrence
    // flags so the output pipeline (reader → queue → emulator append) can be
    // proven from a device log without per-frame noise.
    private boolean mFirstRead = true;
    private boolean mFirstAppendLogged = false;

    // Phase M0-A: bounded per-session counters. Plain long increments on the
    // thread that already owns the corresponding pipeline stage; no
    // allocation, no collections. The byte counter is volatile because it is
    // written by the reader thread and read by the UI thread at E7.
    private boolean mFirstOutputNotified = false;
    private volatile long mPerfBytesRead;
    private long mPerfHandlerMessages;
    private long mPerfScreenUpdates;

    private final String mShellPath;
    private final String mCwd;
    private final String[] mArgs;
    private final String[] mEnv;
    private final Integer mTranscriptRows;

    /** RN-006: per-session scheme; null means the process-global default. */
    private TerminalColorScheme mColorScheme;


    private static final String LOG_TAG = "TerminalSession";

    public TerminalSession(String shellPath, String cwd, String[] args, String[] env, Integer transcriptRows, TerminalSessionClient client) {
        this.mShellPath = shellPath;
        this.mCwd = cwd;
        this.mArgs = args;
        this.mEnv = env;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /**
     * RN-006: the session-owned color scheme (from the workspace's
     * `.termux/colors.properties`). Must be set before the emulator is created
     * — the first {@link #updateSize} call starts the child and constructs it.
     * Passing {@code null} keeps the process-global default.
     */
    public void setColorScheme(TerminalColorScheme colorScheme) {
        if (mEmulator != null) throw new IllegalStateException("setColorScheme() after the emulator was created");
        mColorScheme = colorScheme;
    }

    /** Inform the attached pty of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            // PT-002: a closed session's descriptor number may already belong
            // to another terminal. Resizing it would deliver a wrong
            // TIOCSWINSZ (and SIGWINCH) to that unrelated session.
            if (mTerminalFileDescriptor >= 0) {
                JNI.setPtyWindowSize(mTerminalFileDescriptor, rows, columns, cellWidthPixels, cellHeightPixels);
            }
            synchronized (mEmulatorLock) {
                mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
            }
        }
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Set the terminal emulator's window size and start terminal emulation.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient, mColorScheme);

        int[] processId = new int[1];
        mTerminalFileDescriptor = JNI.createSubprocess(mShellPath, mCwd, mArgs, mEnv, processId, rows, columns, cellWidthPixels, cellHeightPixels);
        mShellPid = processId[0];

        // Linexa diagnostic (2026-08-30, terminal lifecycle): first-occurrence
        // provenance for the emulator/output pipeline. The constructing thread's
        // Looper becomes the emulator's WRITE thread (mMainThreadHandler below
        // is adopted by the no-arg Handler constructor), so recording it —
        // together with the emulator identity and the pty fd/pid — is what lets
        // a device log prove the whole bind/output chain when rendering breaks.
        Logger.logInfo(mClient, LOG_TAG,
            "initializeEmulator emulator=" + System.identityHashCode(mEmulator)
                + " cols=" + columns + " rows=" + rows
                + " fd=" + mTerminalFileDescriptor + " pid=" + mShellPid
                + " thread=" + Thread.currentThread().getName());

        final FileDescriptor terminalFileDescriptorWrapped;
        try {
            terminalFileDescriptorWrapped = wrapFileDescriptor(mTerminalFileDescriptor, mClient);
        } catch (RuntimeException e) {
            // PT-008: this session cannot read its pty, so hand the kernel
            // resources back before surfacing the error. Otherwise a hidden-API
            // failure would leak a live child and its master fd while the
            // caller only sees a failed open.
            int fd = mTerminalFileDescriptor;
            mTerminalFileDescriptor = -1;
            mShellPid = -1;
            if (fd >= 0) JNI.close(fd);
            throw e;
        }

        new Thread("TermSessionInputReader[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                try (InputStream termIn = new FileInputStream(terminalFileDescriptorWrapped)) {
                    final byte[] buffer = new byte[4096];
                    while (true) {
                        int read = termIn.read(buffer);
                        if (read == -1) {
                            // Linexa diagnostic: EOF on the pty master — output can
                            // never arrive again after this. Previously a silent
                            // return, indistinguishable from a wedged pipeline.
                            Logger.logWarn(mClient, LOG_TAG, "InputReader EOF fd=" + mTerminalFileDescriptor);
                            return;
                        }
                        mPerfBytesRead += read;
                        if (!mFirstOutputNotified) {
                            mFirstOutputNotified = true;
                            // Phase M0-A (E6): the first bytes actually received
                            // from the pty. Deliberately NOT a prompt-readiness
                            // signal — output can be a banner, an error or nothing
                            // resembling a shell prompt.
                            final boolean traceFirstOutput = TerminalInstrumentation.isEnabled();
                            if (traceFirstOutput) Trace.beginSection("Linexa:firstOutput");
                            try {
                                if (mClient != null) mClient.onFirstOutput(TerminalSession.this, read);
                            } finally {
                                if (traceFirstOutput) Trace.endSection();
                            }
                        }
                        if (!mProcessToTerminalIOQueue.write(buffer, 0, read)) return;
                        if (mFirstRead) {
                            // Linexa diagnostic: proof that the pty output path is
                            // alive (first bytes actually read from the master).
                            mFirstRead = false;
                            Logger.logInfo(mClient, LOG_TAG,
                                "InputReader first read bytes=" + read + " pid=" + mShellPid);
                        }
                        requestOutputDrain();

                    }
                } catch (Exception e) {
                    // Linexa fix (2026-08-30): this swallow was the terminal's only
                    // failure mode with zero diagnostics — a dead reader thread leaves
                    // the session alive and writable while NO output ever reaches the
                    // emulator (black screen with a working shell, exit banner as the
                    // only visible content). Surface it loudly through the client's
                    // logging bridge instead.
                    Logger.logError(mClient, LOG_TAG,
                        "InputReader DIED pid=" + mShellPid + " — no further output can reach the emulator: " + e);
                    Logger.logStackTraceWithMessage(mClient, LOG_TAG, "InputReader death", e);
                } finally {
                    mReaderFinished.countDown();
                }
            }
        }.start();

        new Thread("TermSessionOutputWriter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                try (FileOutputStream termOut = new FileOutputStream(terminalFileDescriptorWrapped)) {
                    while (true) {
                        int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
                        if (bytesToWrite == -1) return;
                        termOut.write(buffer, 0, bytesToWrite);
                    }
                } catch (IOException e) {
                    // Ignore.
                }
            }
        }.start();

        startInputPump();

        new Thread("TermSessionWaiter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                int processExitCode = JNI.waitFor(mShellPid);
                mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, processExitCode));
            }
        }.start();

    }

    /** Write data to the shell process. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (count <= 0 || mShellPid <= 0) return;
        enqueueInput(data, offset, count);
    }

    /**
     * LINEXA-PERF-003: ask the main Looper to drain PTY output, coalescing
     * repeated requests from the reader thread into one queued message.
     */
    private void requestOutputDrain() {
        mMainThreadHandler.requestDrain();
    }

    /**
     * PT-004: bounded, non-blocking enqueue. Ordering is preserved; overflow
     * drops the OLDEST queued input and reports it explicitly instead of
     * freezing the main thread on a non-reading child.
     */
    private void enqueueInput(byte[] data, int offset, int count) {
        byte[] copy = new byte[count];
        System.arraycopy(data, offset, copy, 0, count);
        boolean overflow = false;
        synchronized (mPendingInput) {
            if (mInputPumpClosed) return;
            while (!mPendingInput.isEmpty() && mPendingInputBytes + count > MAX_PENDING_INPUT_BYTES) {
                mPendingInputBytes -= mPendingInput.removeFirst().length;
                overflow = true;
            }
            if (mPendingInputBytes + count > MAX_PENDING_INPUT_BYTES) {
                // A single chunk larger than the whole bound: reject it rather
                // than dropping everything already queued.
                overflow = true;
            } else {
                mPendingInput.addLast(copy);
                mPendingInputBytes += count;
                mPendingInput.notifyAll();
            }
            if (overflow && !mInputOverflowReported) mInputOverflowReported = true;
            else overflow = false;
        }
        if (overflow) {
            Logger.logError(mClient, LOG_TAG,
                "PT-004: terminal input queue exceeded " + MAX_PENDING_INPUT_BYTES
                    + " bytes (child is not reading); oldest queued input was dropped or an oversized chunk was rejected");
        }
    }

    /** PT-004: single ordered pump that performs the potentially blocking PTY write. */
    private void startInputPump() {
        new Thread("TermSessionInputPump[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                while (true) {
                    byte[] next;
                    synchronized (mPendingInput) {
                        while (mPendingInput.isEmpty() && !mInputPumpClosed) {
                            try {
                                mPendingInput.wait();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                        if (mPendingInput.isEmpty()) return;
                        next = mPendingInput.removeFirst();
                        mPendingInputBytes -= next.length;
                    }
                    if (!mTerminalToProcessIOQueue.write(next, 0, next.length)) return;
                }
            }
        }.start();
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mPerfScreenUpdates++;
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        synchronized (mEmulatorLock) {
            mEmulator.reset();
        }
        notifyScreenUpdate();
    }

    /** Finish this terminal session by sending SIGKILL to the shell. */
    public void finishIfRunning() {
        if (isRunning()) {
            try {
                Os.kill(mShellPid, OsConstants.SIGKILL);
            } catch (ErrnoException e) {
                Logger.logWarn(mClient, LOG_TAG, "Failed sending SIGKILL: " + e.getMessage());
            }
        }
    }

    /**
     * SS-001: ask the shell/proot to exit cleanly (SIGHUP), then escalate to
     * SIGKILL if the pty has not reached EOF within a bounded grace. Runs on a
     * short-lived thread so callers (UI/manager teardown) never block.
     */
    public void finishGracefully() {
        final int pid = mShellPid;
        if (pid <= 0) return;
        try {
            Os.kill(pid, OsConstants.SIGHUP);
        } catch (ErrnoException e) {
            Logger.logWarn(mClient, LOG_TAG, "Failed sending SIGHUP: " + e.getMessage());
            finishIfRunning();
            return;
        }
        new Thread("TermSessionGracefulExit[pid=" + pid + "]") {
            @Override
            public void run() {
                try {
                    mReaderFinished.await(GRACEFUL_EXIT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                // The exit waiter owns mShellPid; only force the SAME child if
                // it is still running after the grace period.
                if (isRunning() && mShellPid == pid) {
                    try {
                        Os.kill(pid, OsConstants.SIGKILL);
                    } catch (ErrnoException e) {
                        Logger.logWarn(mClient, LOG_TAG, "Failed sending fallback SIGKILL: " + e.getMessage());
                    }
                }
            }
        }.start();
    }

    /** Cleanup resources when the process exits. */
    void cleanupResources(int exitStatus) {
        int fdToClose;
        synchronized (this) {
            mShellPid = -1;
            mShellExitStatus = exitStatus;
            fdToClose = mTerminalFileDescriptor;
            // PT-002: invalidate before closing so any concurrent resize sees
            // the guard, even while JNI.close is still running.
            mTerminalFileDescriptor = -1;
        }

        // Stop the reader and writer threads, and close the I/O streams
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
        synchronized (mPendingInput) {
            mInputPumpClosed = true;
            mPendingInput.clear();
            mPendingInputBytes = 0;
            mPendingInput.notifyAll();
        }
        if (fdToClose >= 0) {
            JNI.close(fdToClose);
        }
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        // PT-007: pid 0 means "not started". Treating it as running made
        // finishIfRunning() send SIGKILL to pid 0, i.e. this process group.
        return mShellPid > 0;
    }

    /** Only valid if not {@link #isRunning()}. */
    public synchronized int getExitStatus() {
        return mShellExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    public int getPid() {
        return mShellPid;
    }

    /** Phase M0-A: bytes read from the pty master (bounded long, never reset). */
    public long getPerfBytesRead() {
        return mPerfBytesRead;
    }

    /** Phase M0-A: Main Handler messages processed by this session. */
    public long getPerfHandlerMessages() {
        return mPerfHandlerMessages;
    }

    /** Phase M0-A: screen-update notifications emitted by this session. */
    public long getPerfScreenUpdates() {
        return mPerfScreenUpdates;
    }

    /** Returns the shell's working directory or null if it was unavailable. */
    public String getCwd() {
        if (mShellPid < 1) {
            return null;
        }
        try {
            final String cwdSymlink = String.format("/proc/%s/cwd/", mShellPid);
            String outputPath = new File(cwdSymlink).getCanonicalPath();
            String outputPathWithTrailingSlash = outputPath;
            if (!outputPath.endsWith("/")) {
                outputPathWithTrailingSlash += '/';
            }
            if (!cwdSymlink.equals(outputPathWithTrailingSlash)) {
                return outputPath;
            }
        } catch (IOException | SecurityException e) {
            Logger.logStackTraceWithMessage(mClient, LOG_TAG, "Error getting current directory", e);
        }
        return null;
    }

    private static FileDescriptor wrapFileDescriptor(int fileDescriptor, TerminalSessionClient client) {
        FileDescriptor result = new FileDescriptor();
        try {
            Field descriptorField;
            try {
                descriptorField = FileDescriptor.class.getDeclaredField("descriptor");
            } catch (NoSuchFieldException e) {
                // For desktop java:
                descriptorField = FileDescriptor.class.getDeclaredField("fd");
            }
            descriptorField.setAccessible(true);
            descriptorField.set(result, fileDescriptor);
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException e) {
            Logger.logStackTraceWithMessage(client, LOG_TAG, "Error accessing FileDescriptor#descriptor private field", e);
            // PT-008: this used to terminate the whole app process, so a
            // future Android release that blocks the hidden field would kill
            // every session and any running installation. Surface the failure
            // for THIS session instead; the caller reports it like any other
            // open error.
            throw new IllegalStateException(
                "Cannot wrap the PTY descriptor: FileDescriptor#descriptor is not accessible",
                e
            );
        }
        return result;
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[16 * 1024];

        /**
         * LINEXA-PERF-003: coalesce output-drain requests. Only one message is
         * queued; the handler clears the latch BEFORE draining so a write that
         * lands mid-drain posts the next message itself, and re-arms only when
         * the per-call cap was actually reached (otherwise an empty queue must
         * not spin the Looper).
         */
        void requestDrain() {
            if (mOutputDrainPending.compareAndSet(false, true)) {
                sendEmptyMessage(MSG_NEW_INPUT);
            }
        }

        /**
         * PT-003: drain everything currently queued (non-blocking) into the
         * emulator. Any bytes already read by the reader thread must reach the
         * screen even when the exit notification arrives first.
         *
         * @return true when the per-call cap stopped the drain with output
         *         still (possibly) queued, so the caller must schedule another
         *         pass.
         */
        private boolean appendPendingOutput() {
            if (mEmulator == null) return false;
            int bytesRead;
            // Bounded per call: a child that floods faster than this thread
            // appends would otherwise keep the queue non-empty forever and
            // monopolise the Looper (input echo and rendering starve). Any
            // remainder stays queued for the next message / drain iteration.
            int drained = 0;
            boolean appended = false;
            // LINEXA-PERF-003: one lock acquisition per batch instead of one
            // per 4 KiB chunk. The emulator is the only shared state here; the
            // UI's own reads are main-thread reads, exactly as before.
            synchronized (mEmulatorLock) {
                while (drained < MAX_OUTPUT_DRAIN_BYTES_PER_CALL
                        && (bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false)) > 0) {
                    drained += bytesRead;
                    mEmulator.append(mReceiveBuffer, bytesRead);
                    appended = true;
                    if (!mFirstAppendLogged) {
                        // Linexa diagnostic: proof that emulator state actually
                        // received its first output batch (and on which thread —
                        // this Handler's Looper owns all emulator writes).
                        mFirstAppendLogged = true;
                        Logger.logInfo(mClient, LOG_TAG,
                            "first emulator append bytes=" + bytesRead
                                + " emulator=" + System.identityHashCode(mEmulator)
                                + " thread=" + Thread.currentThread().getName());
                    }
                }
            }
            // One screen notification per handler dispatch, not per 4 KiB
            // chunk: each one invalidates the (full-screen) terminal view and
            // bumps the replay bridge's revision StateFlow.
            if (appended) notifyScreenUpdate();
            return drained >= MAX_OUTPUT_DRAIN_BYTES_PER_CALL;
        }

        @Override
        public void handleMessage(Message msg) {
            mPerfHandlerMessages++;
            final boolean traceOutput = TerminalInstrumentation.isEnabled();
            if (traceOutput) Trace.beginSection("Linexa:terminalOutput");
            try {
                // Clear first: anything the reader appends after this point
                // posts its own message and is not lost.
                mOutputDrainPending.set(false);
                if (appendPendingOutput()) requestDrain();
            } finally {
                if (traceOutput) Trace.endSection();
            }

            if (msg.what == MSG_PROCESS_EXITED) {
                int exitCode = (Integer) msg.obj;
                // DEEP-AUDIT (2026-11-26): do NOT wait on the reader thread
                // here. This handler runs on the Android MAIN looper, and the
                // old blocking-await loop could block it for the full 200 ms
                // drain budget per exiting terminal — a visible main-thread
                // stall on every close, multiplied by close-all / workspace
                // teardown. The same bounded window is now spent by
                // re-posting a 10 ms check, so the Looper keeps servicing
                // input, rendering and binder while the reader finishes.
                finishAfterProcessExit(exitCode, System.nanoTime()
                    + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(OUTPUT_DRAIN_TIMEOUT_MS));
            }
        }

        /**
         * PT-003 / DEEP-AUDIT: give the reader thread a bounded, NON-BLOCKING
         * chance to deliver the child's final bytes before the queues and fd
         * are released, then finish the session exactly once.
         */
        private void finishAfterProcessExit(int exitCode, long deadlineNanos) {
            appendPendingOutput();
            // mReaderFinished is counted down by the reader thread on EOF (or
            // its failure path). Reading its count is not a wait.
            if (mReaderFinished.getCount() != 0 && System.nanoTime() < deadlineNanos) {
                postDelayed(() -> finishAfterProcessExit(exitCode, deadlineNanos), 10);
                return;
            }
            appendPendingOutput();

            cleanupResources(exitCode);

            String exitDescription = "\r\n[Process completed";
            if (exitCode > 0) {
                // Non-zero process exit.
                exitDescription += " (code " + exitCode + ")";
            } else if (exitCode < 0) {
                // Negated signal.
                exitDescription += " (signal " + (-exitCode) + ")";
            }
            exitDescription += " - press Enter]";

            byte[] bytesToWrite = exitDescription.getBytes(StandardCharsets.UTF_8);
            synchronized (mEmulatorLock) {
                mEmulator.append(bytesToWrite, bytesToWrite.length);
            }
            notifyScreenUpdate();

            mClient.onSessionFinished(TerminalSession.this);
        }

    }

}
