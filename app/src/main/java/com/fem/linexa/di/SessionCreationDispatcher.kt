package com.fem.linexa.di

import javax.inject.Qualifier

/**
 * Marks the [kotlinx.coroutines.CoroutineDispatcher] that terminal-session
 * CONSTRUCTION must run on — the app's main-Looper dispatcher in production
 * (`Dispatchers.Main.immediate`). See [AppModule.Providers.provideSessionCreationDispatcher]
 * and the `withContext(sessionCreationDispatcher)` hop at
 * `PRootContainerRuntime.openTerminal`'s `ptySessionFactory.create` call for
 * why the contract exists (the vendored `TerminalSession` constructor captures
 * the constructing thread's Looper through a no-arg `Handler()` and owns all
 * emulator-state writes through it).
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class SessionCreationDispatcher
