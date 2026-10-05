/* SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.settings.picasso.nr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.telephony.SubscriptionInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Device-local presentation. Neither opening the page nor Intent extras issue radio requests. */
class NrSettingsActivity : ComponentActivity() {
    private var manager: NrManager? = null
    private var snapshot by mutableStateOf<NrManager.View?>(null)
    private var airplaneMode by mutableStateOf(false)
    private var restricted by mutableStateOf(false)
    private val owner get() = UserHandle.myUserId() == UserHandle.USER_SYSTEM
    private val session = NrUiSession()
    private var uiGeneration by mutableStateOf(0L)
    // Never save dialog tokens: rotation, backgrounding and mapping changes invalidate them.
    private var confirmation by mutableStateOf<NrUiSession.Token?>(null)
    private var started = false
    private val listener = object : NrManager.Listener {
        override fun onChanged(view: NrManager.View) = updateSnapshot()
    }
    private val restrictionsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateSnapshot()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = (application as NrApplication).manager()
        enableEdgeToEdge()
        setContent {
            val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(this)
                else dynamicLightColorScheme(this)
            MaterialTheme(colorScheme = colors) { ModeScreen() }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        session.start()
        registerReceiver(restrictionsReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(UserManager.ACTION_USER_RESTRICTIONS_CHANGED)
        }, Context.RECEIVER_NOT_EXPORTED)
        // Observe before the first snapshot so a publication cannot be missed.
        manager?.addListener(listener)
        updateSnapshot()
    }

    override fun onStop() {
        started = false
        session.stop()
        uiGeneration = session.generation()
        confirmation = null
        manager?.removeListener(listener)
        unregisterReceiver(restrictionsReceiver)
        super.onStop()
    }

    private fun updateSnapshot() {
        if (!started) return
        val view = manager?.view()
        snapshot = view
        airplaneMode = Settings.Global.getInt(contentResolver,
            Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        restricted = getSystemService(UserManager::class.java)
            .hasUserRestriction(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
        val eligible = mutableMapOf<Int, Int>()
        view?.sims?.forEach { sim ->
            if (view.states[sim.subscriptionId]?.slotId == sim.simSlotIndex) {
                eligible[sim.subscriptionId] = sim.simSlotIndex
            }
        }
        session.update(owner, restricted, airplaneMode, view == null || view.error != null,
            eligible)
        uiGeneration = session.generation()
        confirmation = session.pending()
    }

    private fun choose(token: NrUiSession.Token, mode: Int) {
        updateSnapshot() // Fresh local restrictions/mapping; dispatch also validates live SIMs.
        if (isFinishing) return
        when (session.choose(token, mode)) {
            NrUiSession.Decision.SUBMIT -> manager?.setMode(token.subscriptionId, mode)
            else -> Unit
        }
        confirmation = session.pending()
    }

    private fun confirm(token: NrUiSession.Token) {
        updateSnapshot()
        if (!isFinishing && session.confirm(token) == NrUiSession.Decision.SUBMIT) {
            manager?.setMode(token.subscriptionId, NrModeController.MODE_SA)
        }
        confirmation = session.pending()
    }

    private fun cancelConfirmation() {
        session.cancel()
        confirmation = null
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ModeScreen() {
        val view = snapshot
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = { Text(getString(R.string.nr_title)) },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, getString(R.string.nr_back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface),
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "explanation") { Note(getString(R.string.nr_explanation)) }
                when {
                    !owner -> item(key = "owner") { Note(getString(R.string.nr_owner_only)) }
                    restricted -> item(key = "restricted") {
                        Note(getString(R.string.nr_restricted))
                    }
                    airplaneMode -> item(key = "airplane") {
                        Note(getString(R.string.nr_airplane))
                    }
                    view?.error != null -> item(key = "error") {
                        Note(getString(R.string.nr_unavailable, view.error))
                    }
                }
                if (view == null || view.sims.isEmpty()) {
                    item(key = "no_sim") { Note(getString(R.string.nr_no_sim)) }
                } else {
                    items(view.sims, key = { "sim_${it.subscriptionId}_${it.simSlotIndex}" }) { sim ->
                        SimCard(sim, view.states[sim.subscriptionId])
                    }
                }
                item(key = "readback") { Note(getString(R.string.nr_readback_note)) }
            }
        }
        confirmation?.let { token ->
            val sim = view?.sims?.firstOrNull {
                it.subscriptionId == token.subscriptionId && it.simSlotIndex == token.slotId
            }
            if (sim != null) AlertDialog(
                onDismissRequest = ::cancelConfirmation,
                title = { Text(getString(R.string.nr_sa_warning_title)) },
                text = { Text(simTitle(sim) + "\n\n" + getString(R.string.nr_sa_warning)) },
                confirmButton = {
                    TextButton(onClick = { confirm(token) }) { Text(getString(R.string.nr_apply)) }
                },
                dismissButton = {
                    TextButton(onClick = ::cancelConfirmation) {
                        Text(getString(android.R.string.cancel))
                    }
                },
            )
        }
    }

    @Composable
    private fun Note(text: String) {
        Text(text, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    @Composable
    private fun SimCard(sim: SubscriptionInfo, state: NrModeController.State?) {
        val token = remember(uiGeneration, sim.subscriptionId, sim.simSlotIndex) {
            session.token(sim.subscriptionId, sim.simSlotIndex)
        }
        Card(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(simTitle(sim), style = MaterialTheme.typography.titleMedium)
                Text(statusLabel(state), style = MaterialTheme.typography.bodyMedium,
                    color = if (state?.status == NrModeController.Status.ERROR)
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(getString(R.string.nr_desired, state?.let { modeLabel(it.desiredMode) }
                    ?: getString(R.string.nr_unknown)), style = MaterialTheme.typography.bodyMedium)
                Text(getString(R.string.nr_verified, state?.verifiedMode?.let { modeLabel(it) }
                    ?: getString(R.string.nr_unknown)), style = MaterialTheme.typography.bodyMedium)
                Column(Modifier.selectableGroup()) {
                    for (mode in 0..2) {
                        Row(
                            modifier = Modifier.fillMaxWidth().selectable(
                                selected = state?.desiredMode == mode,
                                enabled = token != null,
                                role = Role.RadioButton,
                                onClick = { token?.let { choose(it, mode) } },
                            ).padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = state?.desiredMode == mode,
                                enabled = token != null, onClick = null)
                            Text(modeLabel(mode), Modifier.padding(start = 12.dp),
                                color = if (token != null) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                        }
                    }
                }
            }
        }
    }

    private fun simTitle(sim: SubscriptionInfo): String = getString(R.string.nr_sim_title,
        sim.simSlotIndex + 1, sim.displayName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.nr_sim_generic))

    private fun modeLabel(mode: Int): String = getString(when (mode) {
        NrModeController.MODE_SA_NSA -> R.string.nr_combined
        NrModeController.MODE_NSA -> R.string.nr_nsa
        NrModeController.MODE_SA -> R.string.nr_sa
        else -> R.string.nr_unknown
    })

    private fun statusLabel(state: NrModeController.State?): String = when (state?.status) {
        NrModeController.Status.WAITING -> getString(R.string.nr_waiting)
        NrModeController.Status.QUERYING -> getString(R.string.nr_querying)
        NrModeController.Status.SETTING -> getString(R.string.nr_setting)
        NrModeController.Status.VERIFYING -> getString(R.string.nr_verifying)
        NrModeController.Status.APPLIED -> getString(if (state.verifiedMode == state.desiredMode)
            R.string.nr_applied else R.string.nr_verifying)
        NrModeController.Status.ERROR -> getString(R.string.nr_failed, state.detail)
        else -> getString(R.string.nr_initializing)
    }
}
