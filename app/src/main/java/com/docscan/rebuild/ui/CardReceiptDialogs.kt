package com.docscan.rebuild.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.docscan.rebuild.R
import com.docscan.rebuild.model.BusinessCard
import com.docscan.rebuild.model.Receipt

/**
 * A business card's details read from its scan (1.3.4), to check and correct, then add to the phone's contacts
 * (through the contacts app, which asks before saving; DocScan needs no contacts permission) or copy as a vCard.
 */
@Composable
fun ContactDialog(found: BusinessCard.Contact, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(found.name.orEmpty()) }
    var company by remember { mutableStateOf(found.company.orEmpty()) }
    var title by remember { mutableStateOf(found.title.orEmpty()) }
    var phone by remember { mutableStateOf(found.phones.getOrNull(0).orEmpty()) }
    var phone2 by remember { mutableStateOf(found.phones.getOrNull(1).orEmpty()) }
    var email by remember { mutableStateOf(found.emails.firstOrNull().orEmpty()) }
    var website by remember { mutableStateOf(found.website.orEmpty()) }
    var address by remember { mutableStateOf(found.address.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.bizcard_title)) }, modifier = Modifier.testTag("contact-dialog"), text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (found.empty) Text(stringResource(R.string.bizcard_none), style = MaterialTheme.typography.bodySmall)
            listOf(Triple(R.string.contact_name, name) { v: String -> name = v }, Triple(R.string.contact_company, company) { v: String -> company = v },
                Triple(R.string.contact_job, title) { v: String -> title = v }, Triple(R.string.contact_phone, phone) { v: String -> phone = v },
                Triple(R.string.contact_phone2, phone2) { v: String -> phone2 = v }, Triple(R.string.contact_email, email) { v: String -> email = v },
                Triple(R.string.contact_website, website) { v: String -> website = v }, Triple(R.string.contact_address, address) { v: String -> address = v }).forEachIndexed { i, (label, value, set) ->
                OutlinedTextField(value, set, label = { Text(stringResource(label)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("contact-field-$i"))
            }
        }
    }, confirmButton = {
        TextButton({
            val intent = Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE).apply {
                if (name.isNotBlank()) putExtra(ContactsContract.Intents.Insert.NAME, name.trim())
                if (company.isNotBlank()) putExtra(ContactsContract.Intents.Insert.COMPANY, company.trim())
                if (title.isNotBlank()) putExtra(ContactsContract.Intents.Insert.JOB_TITLE, title.trim())
                if (phone.isNotBlank()) putExtra(ContactsContract.Intents.Insert.PHONE, phone.trim())
                if (phone2.isNotBlank()) putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE, phone2.trim())
                if (email.isNotBlank()) putExtra(ContactsContract.Intents.Insert.EMAIL, email.trim())
                if (address.isNotBlank()) putExtra(ContactsContract.Intents.Insert.POSTAL, address.trim())
                if (website.isNotBlank()) putExtra(ContactsContract.Intents.Insert.NOTES, website.trim())
            }
            runCatching { context.startActivity(intent) }.onFailure { Toast.makeText(context, R.string.no_contacts_app, Toast.LENGTH_SHORT).show() }
            onDismiss()
        }, modifier = Modifier.testTag("contact-save")) { Text(stringResource(R.string.add_to_contacts)) }
    }, dismissButton = {
        Row {
            TextButton({ copy(context, vcard(name, company, title, listOf(phone, phone2), email, website, address)) }, modifier = Modifier.testTag("contact-copy")) { Text(stringResource(R.string.copy_vcard)) }
            TextButton(onDismiss) { Text(stringResource(R.string.close)) }
        }
    })
}

/** A vCard 3.0 of the details, for pasting into another app. */
fun vcard(name: String, company: String, title: String, phones: List<String>, email: String, website: String, address: String): String = buildString {
    fun esc(v: String) = v.trim().replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\n", "\\n")
    append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
    if (name.isNotBlank()) { append("FN:${esc(name)}\r\n"); append("N:;${esc(name)};;;\r\n") }
    if (company.isNotBlank()) append("ORG:${esc(company)}\r\n")
    if (title.isNotBlank()) append("TITLE:${esc(title)}\r\n")
    phones.filter { it.isNotBlank() }.forEach { append("TEL:${esc(it)}\r\n") }
    if (email.isNotBlank()) append("EMAIL:${esc(email)}\r\n")
    if (website.isNotBlank()) append("URL:${esc(website)}\r\n")
    if (address.isNotBlank()) append("ADR:;;${esc(address)};;;;\r\n")
    append("END:VCARD\r\n")
}

private fun copy(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("DocScan", text))
    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
}

/**
 * A receipt's shop, date and total read from its scan (1.3.4). "Use as name" names the document after them and
 * tags it as a receipt, so receipts can be found together.
 */
@Composable
fun ReceiptDialog(found: Receipt.Summary, onDismiss: () -> Unit, onName: (String) -> Unit) {
    var shop by remember { mutableStateOf(found.shop.orEmpty()) }
    var date by remember { mutableStateOf(found.date.orEmpty()) }
    var total by remember { mutableStateOf(found.total.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.receipt_title)) }, modifier = Modifier.testTag("receipt-dialog"), text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (found.total == null) Text(stringResource(R.string.receipt_none), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(shop, { shop = it }, label = { Text(stringResource(R.string.receipt_shop)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("receipt-shop"))
            OutlinedTextField(date, { date = it }, label = { Text(stringResource(R.string.receipt_date)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("receipt-date"))
            OutlinedTextField(total, { total = it }, label = { Text(stringResource(R.string.receipt_total)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("receipt-total"))
        }
    }, confirmButton = {
        TextButton({ onName(listOf(shop, date, total.takeIf { it.isNotBlank() }?.let { "₪$it" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")) },
            enabled = shop.isNotBlank() || date.isNotBlank() || total.isNotBlank(), modifier = Modifier.testTag("receipt-name")) { Text(stringResource(R.string.receipt_use_name)) }
    }, dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } })
}
