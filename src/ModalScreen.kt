// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

// The only full screen modal window of the application.
/**
* Owns the single full-screen modal content and its back-navigation handler.
*
* Public and subclass/module-facing members:
* - [ownerId] - Identifier of the active modal owner, or null when no modal is open.
* - [open] - Replaces the full-screen modal owner and content.
* - [close] - Closes the current full-screen modal content.
* - [Host] - Displays active full-screen content and binds Back to close.
*/
object ModalScreen {

  /**
  * Identifier of the active modal owner, or null when no modal is open.
  * @return Identifier of the active modal owner, or null when no modal is open.
  */
  var ownerId by mutableStateOf<String?>(null)
    private set

  private var content by mutableStateOf<(@Composable () -> Unit)?>(null)

  /**
  * Replaces the full-screen modal owner and content.
  * @param ownerId Identifier used to distinguish the active sheet or modal owner.
  * @param content Composable content emitted by this host in its declared scope.
  * @return Unit; call on the main thread.
  */
  fun open(ownerId: String, content: @Composable () -> Unit) {
    this.ownerId = ownerId
    this.content = content
  }

  /**
  * Closes the current full-screen modal content.
  * @return Unit; clears its owner ID.
  */
  fun close() {
    ownerId = null
    content = null
  }

  /**
  * Displays active full-screen content and binds Back to close.
  * @return Unit; install once above normal screen content.
  */
  @Composable
  fun Host() {
    BackHandler(enabled = ownerId != null) { close() }
    val current = content ?: return
    Surface(Modifier.fillMaxSize(), color = Color.White) {
      Box(Modifier.systemBarsPadding()) { current() }
    }
  }
}
