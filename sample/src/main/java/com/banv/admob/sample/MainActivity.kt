package com.banv.admob.sample

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.banv.admob.AdKit
import com.banv.admob.AdLoadingDialog
import com.banv.admob.AdPlacement
import com.banv.admob.AdShowResult
import com.banv.admob.BannerType
import com.banv.admob.NativeTemplate
import com.banv.admob.PlacementOverride
import com.banv.admob.compose.BannerAd
import com.banv.admob.compose.NativeAd
import com.banv.admob.compose.rememberActivity
import com.banv.admob.compose.rememberComposeLoadingDialog

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Splash-equivalent: consent first, then preload what the first screen needs.
        AdKit.gatherConsent(this) { allowed ->
            if (allowed) AdKit.interstitial.preload(this, Ads.InterHome)
        }
        setContent { MaterialTheme { SampleScreen() } }
    }
}

@Composable
private fun SampleScreen() {
    val activity = rememberActivity()
    val config by AdKit.config.collectAsStateWithLifecycle()
    val canRequest by AdKit.canRequestAds.collectAsStateWithLifecycle()
    var coins by remember { mutableIntStateOf(0) }
    var nativeTemplate by remember { mutableStateOf<NativeTemplate>(NativeTemplate.Medium) }

    val composeLoading = rememberComposeLoadingDialog {
        Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text("  Compose loading…", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
    fun toast(r: AdShowResult) = Toast.makeText(activity, r.toString(), Toast.LENGTH_SHORT).show()
    fun toggle(p: AdPlacement) = AdKit.updateConfig {
        val on = it.overrides[p.key]?.enabled ?: p.enabled
        it.copy(overrides = it.overrides + (p.key to PlacementOverride(enabled = !on)))
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("canRequestAds=$canRequest  coins=$coins", style = MaterialTheme.typography.titleMedium)
            SwitchRow("Ads master switch", config.enabled) { on -> AdKit.updateConfig { it.copy(enabled = on) } }
            SwitchRow("Premium (only rewarded stays)", config.premium) { AdKit.setPremium(it) }
            SwitchRow("inter_home enabled", config.isEnabled(Ads.InterHome)) { toggle(Ads.InterHome) }

            Button(onClick = { AdKit.interstitial.show(activity, Ads.InterHome) { toast(it) } }) {
                Text("Interstitial – default loading")
            }
            Button(onClick = {
                AdKit.interstitial.show(activity, Ads.InterResult, AdLoadingDialog.layout(R.layout.sample_loading)) { toast(it) }
            }) { Text("Interstitial – custom XML loading") }
            Button(onClick = {
                AdKit.rewarded.show(activity, Ads.RewardCoins, composeLoading, onReward = { coins += it.amount }) { toast(it) }
            }) { Text("Rewarded – Compose loading") }
            OutlinedButton(onClick = { AdKit.appOpen.show(activity) { toast(it) } }) { Text("App open now (splash style)") }
            if (AdKit.consent.isPrivacyOptionsRequired(activity)) {
                OutlinedButton(onClick = { AdKit.consent.showPrivacyOptionsForm(activity) }) { Text("Privacy settings") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("S" to NativeTemplate.Small, "M" to NativeTemplate.Medium, "L" to NativeTemplate.Large,
                    "Custom" to NativeTemplate.Custom(R.layout.sample_native_custom)).forEach { (label, t) ->
                    OutlinedButton(onClick = { nativeTemplate = t }) { Text(label) }
                }
            }
            NativeAd(
                Ads.NativeHome,
                modifier = Modifier.fillMaxWidth(),
                template = nativeTemplate,
                placeholder = { Box(Modifier.fillMaxWidth().height(72.dp).background(Color(0x11000000))) },
            )
            Text("Adaptive banner:")
            BannerAd(Ads.BannerHome)
            Box(Modifier.height(400.dp))
        }
        // Collapsible banners belong at the edge they are anchored to.
        BannerAd(Ads.BannerCollapsible, type = BannerType.Collapsible(BannerType.Anchor.BOTTOM))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
