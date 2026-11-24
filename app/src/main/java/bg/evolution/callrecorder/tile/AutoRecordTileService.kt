package bg.evolution.callrecorder.tile

import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import bg.evolution.callrecorder.AppGraph
import bg.evolution.callrecorder.core.AppLocale
import bg.evolution.callrecorder.R
import kotlinx.coroutines.launch

/** Quick Settings tile that switches automatic call recording on and off. */
class AutoRecordTileService : TileService() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onStartListening() {
        super.onStartListening()
        refresh(AppGraph.get(this).settings.current.autoRecord)
    }

    override fun onClick() {
        super.onClick()
        val graph = AppGraph.get(this)
        val newValue = !graph.settings.current.autoRecord
        refresh(newValue)
        graph.scope.launch { graph.settings.setAutoRecord(newValue) }
    }

    private fun refresh(enabled: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (enabled) R.string.tile_state_on else R.string.tile_state_off)
        }
        tile.updateTile()
    }
}
