// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/** Окно "О программе" и сбор сведений о лицензиях для него.
    LicenseManager живёт здесь, потому что существует только ради этого окна:
    поставщиков в него записывает XTravelApp при запуске. Описания поддерживают HTML со ссылками. */

data class LicenseInfo(val product: String, val description: String)

/** Реализуется поставщиком, чьи сведения показываются в окне. */
interface ILicenseInfo {
  /** Выключенный поставщик - например движок без своего ключа - возвращает пустой массив. */
  fun licenseInfo(): Array<LicenseInfo>
}

/** Собирает сведения заново при каждом запросе, поэтому введённый ключ сразу влияет на список. */
object LicenseManager {
  private val providers = mutableListOf<ILicenseInfo>()

  fun register(provider: ILicenseInfo) {
    if (providers.none { it === provider }) providers.add(provider)
  }

  fun licenseInfo(): Array<LicenseInfo> = providers.flatMap { it.licenseInfo().asList() }.toTypedArray()
}

@Composable
fun AboutScreen() {
  val links = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
  val licenses = LicenseManager.licenseInfo()
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
    Text(AnnotatedString.fromHtml(stringResource(R.string.about_description), linkStyles = links), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.about_version, appVersion()), style = MaterialTheme.typography.bodyMedium)
    if (licenses.isNotEmpty()) {
      licenses.forEach { SoftwareEntry(it, links) }
    }
  }
}

@Composable
// FOR LOCAL USE: один продукт в списке ПО, собираемом на лету.
private fun SoftwareEntry(info: LicenseInfo, links: TextLinkStyles) {
  Column {
    Text(info.product, style = MaterialTheme.typography.titleLarge)
    Text(AnnotatedString.fromHtml(info.description, linkStyles = links), style = MaterialTheme.typography.bodyMedium)
  }
}
