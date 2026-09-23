// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource

/** Registers a lifecycle-aware speech-recognition launcher that uses the device language. */
@Composable
fun rememberVoiceInput(onText: (String) -> Unit): () -> Unit {
  val receiver by rememberUpdatedState(onText)
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { receiver(it) }
  }
  return {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
      .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Languages.systemLocale().toLanguageTag())
    try {
      launcher.launch(intent)
    } catch (_: ActivityNotFoundException) {
      Notify.error(R.string.voice_unavailable)
    }
  }
}

@Composable
fun VoiceButton(onText: (String) -> Unit) {
  val start = rememberVoiceInput(onText)
  IconButton(onClick = start) { Icon(Icons.Outlined.Mic, contentDescription = stringResource(R.string.voice_input)) }
}
