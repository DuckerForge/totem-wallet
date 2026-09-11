package com.clearsign.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Fire-and-forget background work that must outlive a screen (pricing a ledger entry). */
val AppScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
