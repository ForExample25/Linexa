package com.fem.linexa.di

import javax.inject.Qualifier

/**
 * The update coordinator's own long-lived scope (`SupervisorJob + IO`), so a
 * download in flight survives screen recreation, navigation and ViewModel
 * teardown — exactly the convention of the workspace supervisors in
 * [AppModule.Providers], which is also why the scope is injected rather than
 * built inside the coordinator (tests substitute a controllable scope).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UpdateScope
