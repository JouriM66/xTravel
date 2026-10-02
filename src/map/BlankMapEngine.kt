// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Белая подложка до создания движка выбранного поставщика. */
class BlankMapEngine : IMapEngine {

  override fun setCamera(state: CameraState) {}

  @Composable
  override fun Backdrop(modifier: Modifier) {
    Box(modifier.background(Color.White))
  }
}
