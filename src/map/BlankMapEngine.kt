// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

// No map: white field, the grid is drawn by GridModule.
/**
* Provides the empty background used with the application's grid layer.
*
* Public and subclass/module-facing members:
* - [setCamera] - Accepts camera updates without storing them because the background is blank.
* - [Backdrop] - Displays a plain background under application-drawn map layers.
*
* Inherited API: see [IMapEngine] for members not overridden here.
*/
class BlankMapEngine : IMapEngine {

  /**
  * Accepts camera updates without storing them because the background is blank.
  * @param state New immutable camera snapshot.
  * @return Unit; overlay geometry is owned by Maps and MapViewport.
  */
  override fun setCamera(state: CameraState) {}

  /**
  * Displays a plain background under application-drawn map layers.
  * @param modifier Compose layout and drawing modifier applied to the emitted host.
  * @return Unit; call from composition.
  */
  @Composable
  override fun Backdrop(modifier: Modifier) {
    Box(modifier.background(Color.White))
  }
}
