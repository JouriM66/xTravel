// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// FOR LOCAL USE: cached search state and generation counter rejecting obsolete network responses.
private object SearchState {
  var text by mutableStateOf("")
  var found by mutableStateOf<List<DataFound>>(emptyList())
  var answers by mutableStateOf<List<GeoAnswer>>(emptyList())
  var external by mutableStateOf(false)
  var busy by mutableStateOf(false)
  private var generation = 0L

  fun input(value: String) {
    generation++
    text = value
    external = false
    busy = false
    answers = emptyList()
    val variants = TextSearch.variants(value)
    found = DataOwnerManager.search(variants)
  }

  fun request() {
    val query = text.trim()
    if (query.isEmpty() || busy) return
    val request = ++generation
    external = true
    busy = true
    found = emptyList()
    answers = emptyList()
    GeoSearchManager.byText(query) { result ->
      if (generation == request) {
        answers = result
        busy = false
      }
    }
  }

  fun confirmRequest() {
    if (!Settings.askExternalSearch.value) {
      request()
      return
    }
    AppDialog.confirm(
      R.string.external_search_warning,
      icon = Icons.Outlined.TravelExplore,
      confirmLabel = R.string.search_continue,
      checkboxLabel = R.string.dont_ask_again,
      onCheckedConfirm = { if (it) Settings.askExternalSearch.value = false },
      onConfirm = ::request
    )
  }
}

/**
* Number of results the search sheet currently shows.
* @return Number of results the search sheet currently shows.
*/
fun searchResultsCount(): Int = if (SearchState.external) SearchState.answers.size else SearchState.found.size

/**
* Displays in-memory text search and optional external geocoding results.
* @return Unit; search state survives sheet reopening for the process lifetime.
*/
@Composable
fun SearchSheet() {
  var value by remember { mutableStateOf(TextFieldValue(SearchState.text, TextRange(0, SearchState.text.length))) }
  val focus = remember { FocusRequester() }
  val keyboard = LocalSoftwareKeyboardController.current
  val change: (TextFieldValue) -> Unit = {
    val changed = it.text != value.text
    value = it
    if (changed) SearchState.input(it.text)
  }
  val voice = rememberVoiceInput { change(TextFieldValue(it, TextRange(it.length))) }
  LaunchedEffect(Unit) {
    focus.requestFocus()
    keyboard?.show()
  }
  Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(
        value = value,
        onValueChange = change,
        singleLine = true,
        modifier = Modifier.weight(1f).focusRequester(focus),
        placeholder = { Text(stringResource(R.string.search_hint)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
          if (value.text.isEmpty()) {
            IconButton(onClick = voice) { Icon(Icons.Outlined.Mic, contentDescription = stringResource(R.string.voice_input)) }
          } else {
            IconButton(onClick = { change(TextFieldValue("")) }) {
              Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.clear))
            }
          }
        }
      )
      IconButton(onClick = SearchState::confirmRequest, enabled = value.text.isNotBlank() && !SearchState.busy) {
        Icon(Icons.Outlined.TravelExplore, contentDescription = stringResource(R.string.search_maps))
      }
    }
    if (SearchState.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    val listState = rememberLazyListState()
    LazyColumn(state = listState, modifier = Modifier.weight(1f).scrollIndicator(listState)) {
      if (SearchState.external) {
        items(SearchState.answers) { answer ->
          GeoResult(answer)
          HorizontalDivider()
        }
      } else {
        items(SearchState.found) { found ->
          Row(
            modifier = Modifier.fillMaxWidth().clickable {
              keyboard?.hide()
              BottomSheet.close { found.onSelect() }
            }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.Top
          ) {
            Image(
              painterResource(found.icon),
              contentDescription = stringResource(found.sourceLabel),
              modifier = Modifier.padding(end = 10.dp).size(28.dp)
            )
            Text(found.text, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
          }
          HorizontalDivider()
        }
      }
    }
  }
}

// FOR LOCAL USE: external result row and its copy/navigation menu.
@Composable
private fun GeoResult(answer: GeoAnswer) {
  var menu by remember(answer) { mutableStateOf(false) }
  val keyboard = LocalSoftwareKeyboardController.current
  Box {
    Row(Modifier.fillMaxWidth().clickable { menu = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
      Icon(
        Icons.Outlined.TravelExplore,
        contentDescription = stringResource(R.string.search_maps),
        modifier = Modifier.padding(end = 10.dp).size(28.dp)
      )
      Column(Modifier.weight(1f)) {
        answer.point?.let { Text(formatCoordinates(it), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        answer.name?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        answer.description?.takeIf { it.isNotBlank() }?.let {
          Text(it.lineSequence().first(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
    }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
      MenuItem(Icons.Outlined.ContentCopy, stringResource(R.string.search_copy)) {
        menu = false
        val text = listOfNotNull(answer.point?.let(::formatCoordinates), answer.name, answer.description)
          .filter { it.isNotBlank() }.joinToString("\n")
        val clipboard = AppSession.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
      }
      answer.point?.let { point ->
        MenuItem(Icons.Outlined.Place, stringResource(R.string.search_go_to)) {
          menu = false
          keyboard?.hide()
          BottomSheet.close { Maps.centerOn(point) }
        }
      }
    }
  }
}
